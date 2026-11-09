"""Builds the NVDA add-on package: python build_addon.py [output_dir]"""
import configparser
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
CONTENT = ["manifest.ini", "NOTICE", "globalPlugins", "synthDrivers"]
SKIP_DIRS = {"__pycache__", "tests"}


def addon_version() -> str:
    parser = configparser.ConfigParser()
    with open(os.path.join(ROOT, "manifest.ini"), encoding="utf-8") as f:
        parser.read_string("[addon]\n" + f.read())
    return parser["addon"]["version"].strip().strip('"')


def collect_files() -> list[str]:
    files = []
    for entry in CONTENT:
        path = os.path.join(ROOT, entry)
        if os.path.isfile(path):
            files.append(entry)
            continue
        for d, dirs, names in os.walk(path):
            dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
            for n in names:
                if not n.endswith((".pyc", ".pyo")):
                    files.append(os.path.relpath(os.path.join(d, n), ROOT))
    return sorted(files)


def main() -> None:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else ROOT
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, f"edgeTtsForNvda-{addon_version()}.nvda-addon")
    files = collect_files()
    for f in files:
        if f.endswith(".py"):
            with open(os.path.join(ROOT, f), encoding="utf-8") as src:
                compile(src.read(), f, "exec")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for f in files:
            z.write(os.path.join(ROOT, f), f.replace(os.sep, "/"))
    print(out)


if __name__ == "__main__":
    main()
