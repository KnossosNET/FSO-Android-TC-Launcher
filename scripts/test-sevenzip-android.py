"""Run the core installer and decoder tests on a booted Android device/emulator.

Set JAVA_HOME to a Gradle-compatible JDK and ANDROID_HOME to the SDK. This only
pushes a disposable test harness to /data/local/tmp; it does not install an APK.
Example: python scripts/test-sevenzip-android.py --serial emulator-5554
"""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def run(*args, **kwargs):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    sdk = Path(os.environ["ANDROID_HOME"])
    java = Path(os.environ["JAVA_HOME"]) / "bin/java"
    adb = sdk / "platform-tools/adb"
    if os.name == "nt":
        java = java.with_suffix(".exe")
        adb = adb.with_suffix(".exe")
    gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    run(gradle, ":core:prepareSevenZAndroidProbe", ":sfeh:assembleDebug", "--offline", "--no-daemon")
    staging = ROOT / "shared/core/build/sevenzip-probe"
    classes = staging / "tests.jar"
    with zipfile.ZipFile(classes, "w") as jar:
        for path in (staging / "classes").rglob("*"):
            if path.is_file():
                jar.write(path, path.relative_to(staging / "classes").as_posix())
    d8 = sdk / "build-tools/36.0.0/lib/d8.jar"
    output = staging / "dex"
    output.mkdir(exist_ok=True)
    run(java, "-cp", d8, "com.android.tools.r8.D8", "--min-api", "28", "--output", output,
        "--lib", sdk / "platforms/android-36/android.jar", classes, *sorted((staging / "libs").glob("*.jar")))
    probe = staging / "probe.jar"
    with zipfile.ZipFile(probe, "w") as jar:
        for dex in output.glob("*.dex"):
            jar.write(dex, dex.name)
        for resource in (staging / "classes/fixtures").rglob("*"):
            if resource.is_file():
                jar.write(resource, "fixtures/" + resource.name)
    abi = subprocess.check_output([str(adb), "-s", args.serial, "shell", "getprop", "ro.product.cpu.abi"], text=True).strip()
    apk = next((ROOT / "versions/sfeh/build/outputs/apk/debug").glob("*.apk"))
    files = [probe]
    with zipfile.ZipFile(apk) as package:
        for name in ("libarchive7z.so", "libc++_shared.so"):
            entry = f"lib/{abi}/{name}"
            if name == "libc++_shared.so" and entry not in package.namelist():
                continue
            path = staging / name
            path.write_bytes(package.read(entry))
            files.append(path)
    remote = "/data/local/tmp/fso-sevenzip-probe"
    run(adb, "-s", args.serial, "shell", "mkdir", "-p", remote)
    for path in files:
        run(adb, "-s", args.serial, "push", path, remote + "/")
    run(adb, "-s", args.serial, "shell", f"LD_LIBRARY_PATH={remote} dalvikvm -Djava.io.tmpdir={remote} -Djava.library.path={remote} -cp {remote}/probe.jar com.fsolauncher.core.SevenZExtractionTest")


if __name__ == "__main__":
    main()
