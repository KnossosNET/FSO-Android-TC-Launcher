"""Test draft-only release behavior and signing setup without sending anything to GitHub."""
import base64
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


signing = module("ci_signing", "ci-signing.py")
drafts = module("draft_release", "github-draft-release.py")


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="fso-release-test-")
        self.root = Path(self.temp.name)
        (self.root / "dist").mkdir()
        for folder, app in (("Fotg", "fotg"), ("fs2demo", "fs2demo")):
            version = self.root / "versions" / folder
            version.mkdir(parents=True)
            (version / "build.gradle").write_text("// Test module", "utf-8")
            (version / "version.json").write_text(json.dumps({"applicationId": "com.fsolauncher." + app}), "utf-8")
            (self.root / "dist" / ("com.fsolauncher." + app + "-signed.apk")).write_bytes(b"test apk")
        self.calls = []

    def tearDown(self):
        self.temp.cleanup()

    def runner(self, status=None):
        def run(arguments, **kwargs):
            self.calls.append(arguments)
            return subprocess.CompletedProcess(arguments, 1 if status is None else 0,
                                               "" if status is None else json.dumps({"isDraft": status}))
        return run

    def test_creates_a_draft_with_every_game_apk_and_an_existing_tag(self):
        drafts.release(self.root, "v0.2.1", "owner/repo", self.runner())
        creation = self.calls[-1]
        self.assertIn("--draft", creation)
        self.assertIn("--verify-tag", creation)
        self.assertEqual(2, sum(arg.endswith(".apk") for arg in creation))

    def test_reruns_update_only_an_existing_draft(self):
        drafts.release(self.root, "v0.2.1", "owner/repo", self.runner(True))
        self.assertEqual(["gh", "release", "upload"], self.calls[-1][:3])
        self.assertIn("--clobber", self.calls[-1])

    def test_published_release_is_never_modified(self):
        with self.assertRaisesRegex(ValueError, "published"):
            drafts.release(self.root, "v0.2.1", "owner/repo", self.runner(False))
        self.assertEqual(1, len(self.calls))

    def test_missing_game_apk_stops_before_any_github_call(self):
        (self.root / "dist/com.fsolauncher.fs2demo-signed.apk").unlink()
        with self.assertRaisesRegex(ValueError, "every configured game"):
            drafts.release(self.root, "tag", "owner/repo", self.runner())
        self.assertEqual([], self.calls)

    def test_missing_secrets_do_not_create_signing_files(self):
        with self.assertRaisesRegex(ValueError, "ANDROID_KEYSTORE_BASE64"):
            signing.prepare(self.root, {})
        self.assertFalse((self.root / "signing.properties").exists())

    def test_application_id_collision_stops_before_any_github_call(self):
        path = self.root / "versions/fs2demo/version.json"
        path.write_text(json.dumps({"applicationId": "com.fsolauncher.fotg"}), "utf-8")
        with self.assertRaisesRegex(ValueError, "distinct application ID"):
            drafts.release(self.root, "tag", "owner/repo", self.runner())
        self.assertEqual([], self.calls)

    def test_existing_local_signing_configuration_is_preserved(self):
        path = self.root / "signing.properties"
        path.write_text("Existing private signing configuration", "ascii")
        values = dict.fromkeys(signing.SECRET_NAMES, "test")
        values["ANDROID_KEYSTORE_BASE64"] = base64.b64encode(b"test keystore").decode()
        with self.assertRaisesRegex(ValueError, "will not be replaced"):
            signing.prepare(self.root, values)
        self.assertEqual("Existing private signing configuration", path.read_text("ascii"))

    def test_ci_signing_escapes_passwords_and_cleanup_removes_only_generated_files(self):
        values = dict.fromkeys(signing.SECRET_NAMES, "test")
        values["ANDROID_KEYSTORE_BASE64"] = base64.b64encode(b"test keystore").decode()
        values["ANDROID_KEYSTORE_PASSWORD"] = "with space\\slash\nnewline"
        signing.prepare(self.root, values)
        config = (self.root / "signing.properties").read_text("ascii")
        self.assertIn("storePassword=with\\ space\\\\slash\\nnewline", config)
        self.assertEqual(b"test keystore", (self.root / "signing/ci-release.jks").read_bytes())
        signing.clean(self.root)
        self.assertFalse((self.root / "signing.properties").exists())
        self.assertFalse((self.root / "signing/ci-release.jks").exists())


if __name__ == "__main__":
    unittest.main()
