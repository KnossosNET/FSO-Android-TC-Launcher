"""Create a standalone game module from the template."""
import argparse
import json
import shutil
import re
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("folder")
parser.add_argument("application_id")
parser.add_argument("nebula_id")
parser.add_argument("display_name")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
if not args.folder.isidentifier() or not re.fullmatch(r"com\.fsolauncher\.[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)*", args.application_id):
    raise SystemExit("Use a simple folder name and a com.fsolauncher.* application ID")
target = root / "versions" / args.folder
if target.exists():
    raise SystemExit("Version folder already exists")
for config in (root / "versions").glob("*/version.json"):
    if json.loads(config.read_text("utf-8"))["applicationId"] == args.application_id:
        raise SystemExit("Application ID is already used by another version")
shutil.copytree(root / "template/game", target)
version_file = target / "version.json"
version = json.loads(version_file.read_text("utf-8"))
version.update(applicationId=args.application_id, displayName=args.display_name)
version_file.write_text(json.dumps(version, indent=2) + "\n", "utf-8")
game_file = target / "src/main/assets/game.json"
game = json.loads(game_file.read_text("utf-8"))
game.update(displayName=args.display_name, nebulaId=args.nebula_id)
game_file.write_text(json.dumps(game, indent=2) + "\n", "utf-8")
print(f"Created {target}. Import an engine and replace the icon before building.")
