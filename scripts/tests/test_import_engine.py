"""Regression tests for ABI discovery, build configuration and safe engine replacement."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "import_engine", Path(__file__).resolve().parents[1] / "import-engine.py")
importer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(importer)


class ImportEngineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="fso-import-test-")
        self.root = Path(self.temp.name)
        self.version = self.root / "versions/Fotg"
        self.version.mkdir(parents=True)
        self.original = {"applicationId": "com.fsolauncher.fotg",
                         "displayName": "Fate of the Galaxy", "versionCode": 8,
                         "versionName": "0.8.0", "abis": ["arm64-v8a"]}
        self.config = self.version / "version.json"
        self.config.write_text(json.dumps(self.original), "utf-8")
        self.source = self.root / "source"
        self.source.mkdir()
        self.native = self.version / "src/main/jniLibs"
        old = self.native / "arm64-v8a"
        old.mkdir(parents=True)
        (old / "libfso.so").write_bytes(b"previous engine")
        (old / "obsolete.so").write_bytes(b"obsolete dependency")
        self.provenance = self.version / "engine-provenance.json"
        self.provenance.write_text('{"previous": true}', "utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def input(self, abi, sdl=True):
        folder = self.source / abi
        folder.mkdir()
        (folder / f"libfs2_open_new_{abi}.so").write_bytes(("engine " + abi).encode())
        (folder / f"libfs2_open_new_{abi}-DEBUG.so").write_bytes(b"unused debug engine")
        (folder / "libdependency.so").write_bytes(("dependency " + abi).encode())
        if sdl:
            (folder / "libSDL3.so").write_bytes(b"SDL3")
        return folder

    def assert_original(self):
        self.assertEqual(self.original, json.loads(self.config.read_text("utf-8")))
        self.assertEqual(b"previous engine", (self.native / "arm64-v8a/libfso.so").read_bytes())
        self.assertTrue((self.native / "arm64-v8a/obsolete.so").is_file())
        self.assertEqual({"previous": True}, json.loads(self.provenance.read_text("utf-8")))
        self.assertEqual([], list(self.version.glob(".engine-import-*")))

    def test_discovers_all_four_abis_and_updates_packaging_and_hashes(self):
        for abi in importer.SUPPORTED_ABIS:
            self.input(abi)
        (self.source / "unrelated-folder").mkdir()
        result = importer.import_engine(self.root, "Fotg", self.source)
        self.assertEqual(list(importer.SUPPORTED_ABIS), result)
        configuration = json.loads(self.config.read_text("utf-8"))
        self.assertEqual(result, configuration["abis"])
        self.assertEqual(8, configuration["versionCode"])
        provenance = json.loads(self.provenance.read_text("utf-8"))
        self.assertEqual(set(result), set(provenance["abis"]))
        for abi in result:
            target = self.native / abi
            self.assertEqual(("engine " + abi).encode(), (target / "libfso.so").read_bytes())
            self.assertEqual({"libfso.so", "libSDL3.so", "libdependency.so"},
                             {p.name for p in target.iterdir()})
            self.assertEqual(importer.sha256(target / "libfso.so"),
                             provenance["abis"][abi]["sha256"]["libfso.so"])

    def test_explicit_subset_controls_which_abis_gradle_packages(self):
        for abi in importer.SUPPORTED_ABIS:
            self.input(abi)
        result = importer.import_engine(self.root, "Fotg", self.source, ["arm64-v8a", "x86_64"])
        self.assertEqual(["arm64-v8a", "x86_64"], result)
        self.assertEqual(result, json.loads(self.config.read_text("utf-8"))["abis"])
        self.assertFalse((self.native / "x86").exists())

    def test_preserves_non_library_files_and_directories(self):
        self.input("arm64-v8a")
        old = self.native / "arm64-v8a"
        (old / "readme.txt").write_text("Keep this file", "utf-8")
        (old / "notes").mkdir()
        (old / "notes/build.txt").write_text("Keep these notes", "utf-8")
        importer.import_engine(self.root, "Fotg", self.source)
        self.assertEqual("Keep this file", (old / "readme.txt").read_text("utf-8"))
        self.assertEqual("Keep these notes", (old / "notes/build.txt").read_text("utf-8"))
        self.assertFalse((old / "obsolete.so").exists())

    def test_invalid_later_abi_does_not_modify_any_previous_engine(self):
        self.input("arm64-v8a")
        self.input("x86", sdl=False)
        with self.assertRaisesRegex(ValueError, "Missing SDL3 for x86"):
            importer.import_engine(self.root, "Fotg", self.source)
        self.assert_original()

    def test_empty_source_does_not_modify_existing_configuration(self):
        with self.assertRaisesRegex(ValueError, "No supported ABI"):
            importer.import_engine(self.root, "Fotg", self.source)
        self.assert_original()

    def test_missing_explicit_abi_is_an_error(self):
        self.input("arm64-v8a")
        with self.assertRaisesRegex(ValueError, "Missing source ABI folder: x86"):
            importer.import_engine(self.root, "Fotg", self.source, ["arm64-v8a", "x86"])
        self.assert_original()

    def test_ambiguous_engine_is_an_error_before_replacement(self):
        source = self.input("arm64-v8a")
        (source / "libfs2_open_other.so").write_bytes(b"other engine")
        with self.assertRaisesRegex(ValueError, "Expected one release engine"):
            importer.import_engine(self.root, "Fotg", self.source)
        self.assert_original()

    def test_same_directory_import_is_refused_without_deleting_libraries(self):
        with self.assertRaisesRegex(ValueError, "separate safe directories"):
            importer.import_engine(self.root, "Fotg", self.native)
        self.assert_original()

    def test_rename_failure_restores_engines_configuration_and_provenance(self):
        self.input("arm64-v8a")
        self.input("x86_64")
        real_replace = importer.os.replace
        destination = (self.native / "x86_64").resolve()

        def fail_once(source, target):
            if Path(target) == destination and "native" in Path(source).parts:
                raise OSError("Simulated replacement failure")
            return real_replace(source, target)

        with patch.object(importer.os, "replace", side_effect=fail_once):
            with self.assertRaisesRegex(OSError, "Simulated replacement failure"):
                importer.import_engine(self.root, "Fotg", self.source)
        self.assert_original()
        self.assertFalse((self.native / "x86_64").exists())


if __name__ == "__main__":
    unittest.main()
