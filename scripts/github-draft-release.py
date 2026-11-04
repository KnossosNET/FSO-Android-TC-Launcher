"""Create or update a tag's draft GitHub release with every generated standalone APK."""
import argparse
import json
import os
import subprocess
from pathlib import Path


def release(root, tag, repository, runner=subprocess.run):
    if not tag or not repository:
        raise ValueError("A tag and GH_REPO are required")
    expected = set()
    for folder in (root / "versions").iterdir():
        if (folder / "build.gradle").is_file():
            spec = json.loads((folder / "version.json").read_text("utf-8"))
            filename = spec["applicationId"] + "-signed.apk"
            if filename in expected:
                raise ValueError("Each configured game must have a distinct application ID")
            expected.add(filename)
    apks = sorted((root / "dist").glob("*.apk"))
    if not expected or {path.name for path in apks} != expected:
        raise ValueError("dist must contain exactly one signed APK for every configured game")
    if any(path.stat().st_size == 0 for path in apks):
        raise ValueError("An APK is empty")
    view = runner(["gh", "release", "view", tag, "--repo", repository, "--json", "isDraft"],
                  capture_output=True, text=True, check=False)
    files = [str(path) for path in apks]
    if view.returncode == 0:
        if not json.loads(view.stdout).get("isDraft"):
            raise ValueError("The existing release is published; it will not be modified")
        runner(["gh", "release", "upload", tag, *files, "--repo", repository, "--clobber"], check=True)
    else:
        runner(["gh", "release", "create", tag, *files, "--repo", repository, "--verify-tag",
                "--draft", "--title", "FSO Android TC Launcher " + tag, "--generate-notes"], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    args = parser.parse_args()
    try:
        release(Path(__file__).resolve().parents[1], args.tag, os.environ.get("GH_REPO", ""))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Draft release failed: {error}\n")


if __name__ == "__main__":
    main()
