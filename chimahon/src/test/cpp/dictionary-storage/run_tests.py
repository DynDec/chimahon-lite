"""Run the already-built native tests with fresh generated fixtures each time."""
import argparse
import pathlib
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--native-dir", required=True, type=pathlib.Path)
args = parser.parse_args()
suffix = ".exe" if sys.platform == "win32" else ""
native = args.native_dir.resolve()
with tempfile.TemporaryDirectory(prefix="dictionary-storage-") as directory:
    root = pathlib.Path(directory)
    subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name("generate_fixtures.py")), str(root)], check=True)
    subprocess.run([str(native / f"legacy_import{suffix}"), str(root / "large.zip"), str(root / "legacy"), "low"], check=True)
    subprocess.run([str(native / f"storage_tests{suffix}"), str(root)], check=True)
