"""Regression tests for ABI discovery, build configuration and safe engine replacement."""
import importlib.util
import io
import json
import stat
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
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

    def run_cli(self, *args):
        stdout, stderr = io.StringIO(), io.StringIO()
        code = 0
        with patch.object(importer, "__file__", str(self.root / "scripts/import-engine.py")), \
                redirect_stdout(stdout), redirect_stderr(stderr):
            try:
                importer.main(list(args))
            except SystemExit as error:
                code = error.code
        return code, stdout.getvalue(), stderr.getvalue()

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

    @unittest.skipUnless(importer.os.name == "nt", "Windows read-only directory attributes")
    def test_repeated_import_removes_read_only_backups(self):
        source = self.input("arm64-v8a")
        old = self.native / "arm64-v8a"
        # Windows/OneDrive directories can be read-only even when their files are writable.
        old.chmod(stat.S_IREAD)
        (old / "obsolete.so").chmod(stat.S_IREAD)
        self.config.chmod(stat.S_IREAD)
        self.provenance.chmod(stat.S_IREAD)
        (source / "libSDL3.so").chmod(stat.S_IREAD)
        for _ in range(2):
            importer.import_engine(self.root, "Fotg", self.source)
            self.assertEqual([], list(self.version.glob(".engine-import-*")))
            self.assertEqual(b"engine arm64-v8a", (old / "libfso.so").read_bytes())

    def test_cleanup_retries_temporary_permission_errors(self):
        self.input("arm64-v8a")
        real_rmtree = importer.shutil.rmtree
        attempts = 0

        def locked_once(path, **kwargs):
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                raise PermissionError("Temporarily locked by sync")
            return real_rmtree(path, **kwargs)

        with patch.object(importer.shutil, "rmtree", side_effect=locked_once), \
                patch.object(importer.time, "sleep"):
            importer.import_engine(self.root, "Fotg", self.source)
        self.assertEqual(2, attempts)
        self.assertEqual([], list(self.version.glob(".engine-import-*")))

    @unittest.skipUnless(importer.os.name == "nt", "Windows read-only file attributes")
    def test_rollback_removes_read_only_imported_files(self):
        source = self.input("arm64-v8a")
        (source / "libSDL3.so").chmod(stat.S_IREAD)
        real_replace = importer.os.replace

        def fail_install(source, target):
            if Path(target) == self.config.resolve() and Path(source).name == "version.json":
                raise OSError("Simulated metadata replacement failure")
            return real_replace(source, target)

        with patch.object(importer.os, "replace", side_effect=fail_install):
            with self.assertRaisesRegex(OSError, "Simulated metadata replacement failure"):
                importer.import_engine(self.root, "Fotg", self.source)
        self.assert_original()

    def test_failed_rollback_preserves_recovery_backups(self):
        self.input("arm64-v8a")
        self.input("x86_64")
        real_replace = importer.os.replace

        def fail_install_and_restore(source, target):
            if (Path(target) == (self.native / "x86_64").resolve() or
                    Path(source).parent.name == "backups"):
                raise OSError("Simulated locked destination")
            return real_replace(source, target)

        with patch.object(importer.os, "replace", side_effect=fail_install_and_restore):
            with self.assertRaisesRegex(RuntimeError, "Recovery files remain"):
                importer.import_engine(self.root, "Fotg", self.source)
        work, = self.version.glob(".engine-import-*")
        self.assertEqual(b"previous engine", (work / "backups/0/libfso.so").read_bytes())
        self.assertEqual(self.original, json.loads(self.config.read_text("utf-8")))

    def test_all_discovers_versions_and_imports_every_source_abi(self):
        self.input("arm64-v8a")
        self.input("x86_64")
        other = self.root / "versions/wcsaga"
        other.mkdir()
        (other / "version.json").write_text(json.dumps(self.original), "utf-8")
        (self.root / "versions/not-a-module").mkdir()
        for _ in range(2):
            code, stdout, stderr = self.run_cli("all", str(self.source))
            self.assertEqual(0, code, stderr)
            self.assertIn("for Fotg:", stdout)
            self.assertIn("for wcsaga:", stdout)
            for version in (self.version, other):
                spec = json.loads((version / "version.json").read_text("utf-8"))
                self.assertEqual(["arm64-v8a", "x86_64"], spec["abis"])
                self.assertEqual(8, spec["versionCode"])
                provenance = json.loads((version / "engine-provenance.json").read_text("utf-8"))
                for abi in spec["abis"]:
                    engine = version / "src/main/jniLibs" / abi / "libfso.so"
                    self.assertEqual(("engine " + abi).encode(), engine.read_bytes())
                    self.assertEqual(importer.sha256(engine), provenance["abis"][abi]["sha256"]["libfso.so"])
                self.assertEqual([], list(version.glob(".engine-import-*")))

    def test_all_supports_engine_and_abi_filters(self):
        source = self.input("arm64-v8a")
        (source / "custom.so").write_bytes(b"selected engine")
        self.input("x86_64")
        other = self.root / "versions/wcsaga"
        other.mkdir()
        (other / "version.json").write_text(json.dumps(self.original), "utf-8")
        code, _, stderr = self.run_cli("all", str(self.source), "--engine", "custom.so",
                                       "--abis", "arm64-v8a")
        self.assertEqual(0, code, stderr)
        for version in (self.version, other):
            self.assertEqual(b"selected engine",
                             (version / "src/main/jniLibs/arm64-v8a/libfso.so").read_bytes())
            self.assertFalse((version / "src/main/jniLibs/x86_64").exists())

    def test_all_reports_failures_and_continues_with_other_versions(self):
        self.input("arm64-v8a")
        broken = self.root / "versions/broken"
        broken.mkdir()
        (broken / "version.json").write_text("invalid JSON", "utf-8")
        code, stdout, stderr = self.run_cli("all", str(self.source))
        self.assertEqual(1, code)
        self.assertIn("Import failed for broken:", stderr)
        self.assertIn("for Fotg:", stdout)
        self.assertEqual("invalid JSON", (broken / "version.json").read_text("utf-8"))
        self.assertFalse((broken / "src").exists())

    def test_all_without_versions_is_an_error(self):
        self.config.unlink()
        code, _, stderr = self.run_cli("all", str(self.source))
        self.assertEqual(1, code)
        self.assertIn("No version.json files found", stderr)

    def test_cli_still_accepts_one_version(self):
        self.input("arm64-v8a")
        code, stdout, stderr = self.run_cli("Fotg", str(self.source))
        self.assertEqual(0, code, stderr)
        self.assertIn("Imported 1 ABI(s) for Fotg: arm64-v8a.", stdout)


if __name__ == "__main__":
    unittest.main()
