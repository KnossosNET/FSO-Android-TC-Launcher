"""Import native engines, automatically configuring every ABI found in the source."""
import argparse
import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path

SUPPORTED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def within_version(path, version):
    """Check resolved mutation paths before moving or recursively removing anything."""
    resolved = path.resolve()
    if resolved == version or not resolved.is_relative_to(version):
        raise ValueError(f"Destination leaves the version folder: {path}")
    return resolved


def install_staged(changes, work, version):
    """Replace all staged outputs, restoring the previous set if a rename fails."""
    operations = []
    try:
        for index, (staged, destination) in enumerate(changes):
            destination = within_version(destination, version)
            staged = within_version(staged, version)
            destination.parent.mkdir(parents=True, exist_ok=True)
            backup = within_version(work / "backups" / str(index), version)
            backup.parent.mkdir(parents=True, exist_ok=True)
            operation = {"target": destination, "backup": backup, "saved": False, "installed": False}
            operations.append(operation)
            if destination.exists():
                os.replace(destination, backup)
                operation["saved"] = True
            os.replace(staged, destination)
            operation["installed"] = True
    except BaseException as failure:
        recovery_errors = []
        for operation in reversed(operations):
            try:
                target = within_version(operation["target"], version)
                if operation["installed"]:
                    if target.is_dir():
                        shutil.rmtree(target)
                    else:
                        target.unlink()
                if operation["saved"]:
                    os.replace(within_version(operation["backup"], version), target)
            except OSError as error:
                recovery_errors.append(str(error))
        if recovery_errors:
            # Preserve backups instead of letting temporary-directory cleanup discard them.
            raise RuntimeError(f"Import failed. Recovery files remain at {work}: {recovery_errors}") from failure
        raise


def import_engine(root, version_name, source_root, selected_abis=None, engine_name=None):
    versions_root = (root / "versions").resolve()
    version = (versions_root / version_name).resolve()
    spec_path = version / "version.json"
    if version.parent != versions_root or not spec_path.is_file():
        raise ValueError("Unknown version folder")
    source_root = source_root.resolve()
    if not source_root.is_dir():
        raise ValueError(f"Source directory does not exist: {source_root}")
    if selected_abis:
        abis = list(dict.fromkeys(selected_abis))
        if any(abi not in SUPPORTED_ABIS for abi in abis):
            raise ValueError("Unsupported Android ABI")
    else:
        abis = [abi for abi in SUPPORTED_ABIS if (source_root / abi).is_dir()]
    if not abis:
        raise ValueError("No supported ABI folders found in the source directory")
    if engine_name and (Path(engine_name).name != engine_name or "/" in engine_name or "\\" in engine_name):
        raise ValueError("--engine must be a filename, not a path")

    spec = json.loads(spec_path.read_text("utf-8"))
    within_version(spec_path, version)
    within_version(version / "engine-provenance.json", version)
    native_root = within_version(version / "src/main/jniLibs", version)
    inputs = []
    # Validate every ABI before touching any existing engine, configuration or provenance.
    for abi in abis:
        source = (source_root / abi).resolve()
        target = within_version(native_root / abi, version)
        if target.parent != native_root or target.name != abi:
            raise ValueError(f"ABI destination must remain inside jniLibs: {abi}")
        if not source.is_dir():
            raise ValueError(f"Missing source ABI folder: {abi}")
        if source.is_relative_to(native_root) or native_root.is_relative_to(source):
            raise ValueError("Engine source and destination must be separate safe directories")
        engines = sorted(p for p in source.glob("libfs2_open*.so")
                         if p.is_file() and "debug" not in p.name.lower())
        if engine_name:
            engines = [source / engine_name]
        elif not engines and (source / "libfso.so").is_file():
            engines = [source / "libfso.so"]
        if len(engines) != 1 or not engines[0].is_file():
            raise ValueError(f"Expected one release engine for {abi}: {[p.name for p in engines]}")
        if not (source / "libSDL3.so").is_file():
            raise ValueError(f"Missing SDL3 for {abi}")
        libraries = sorted(p for p in source.glob("*.so")
                           if p.is_file() and "debug" not in p.name.lower()
                           and not p.name.startswith("libfs2_open") and p.name != "libfso.so")
        inputs.append((abi, target, engines[0], libraries))

    work = within_version(Path(tempfile.mkdtemp(prefix=".engine-import-", dir=version)), version)
    preserve_recovery = False
    try:
        provenance = {"source": str(source_root), "abis": {}}
        changes = []
        for abi, target, engine, libraries in inputs:
            staged = work / "native" / abi
            staged.mkdir(parents=True)
            entries = {}
            for library in libraries:
                output = staged / library.name
                shutil.copy2(library, output)
                entries[library.name] = sha256(output)
            shutil.copy2(engine, staged / "libfso.so")
            entries["libfso.so"] = sha256(staged / "libfso.so")
            provenance["abis"][abi] = {"engine": engine.name, "sha256": entries}
            # Keep non-library files and directories already stored alongside the binaries.
            if target.is_dir():
                for item in target.iterdir():
                    if item.is_dir():
                        shutil.copytree(item, staged / item.name, symlinks=True)
                    elif item.is_file() and item.suffix != ".so":
                        shutil.copy2(item, staged / item.name)
            changes.append((staged, target))
        spec["abis"] = abis
        for name, data in (("version.json", spec), ("engine-provenance.json", provenance)):
            staged = work / name
            staged.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", "utf-8")
            changes.append((staged, version / name))
        try:
            install_staged(changes, work, version)
        except RuntimeError:
            preserve_recovery = True
            raise
    finally:
        if not preserve_recovery:
            shutil.rmtree(within_version(work, version))
    return abis


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", help="Folder under versions, such as Fotg")
    parser.add_argument("source", type=Path, help="jniLibs directory containing Android ABI subfolders")
    parser.add_argument("--abis", nargs="+", choices=SUPPORTED_ABIS,
                        help="Import only these ABIs; defaults to every supported ABI found in the source")
    parser.add_argument("--engine", help="Exact engine filename; otherwise select one release engine per ABI")
    args = parser.parse_args()
    try:
        abis = import_engine(Path(__file__).resolve().parents[1], args.version,
                             args.source, args.abis, args.engine)
    except (ValueError, OSError, RuntimeError) as error:
        parser.exit(1, f"Import failed: {error}\n")
    print(f"Imported {len(abis)} ABI(s) for {args.version}: {', '.join(abis)}.")
    print("Updated version.json and engine-provenance.json. Rebuild the APK to use these engines.")


if __name__ == "__main__":
    main()
