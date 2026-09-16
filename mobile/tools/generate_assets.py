"""Build Android palette assets from Pebrel's single desktop theme authority."""
from pathlib import Path
import argparse
import json
import re
import shutil


def generate(output: Path) -> None:
    root = Path(__file__).resolve().parents[2]
    source = (root / "nebula_settings/src/themes.rs").read_text(encoding="utf-8")
    names_block = source.split("pub const BUILTIN:", 1)[1].split("];", 1)[0]
    names = re.findall(r"Self::(\w+)", names_block)
    palettes = {}
    for name, body in re.findall(r"Self::(\w+)\s*=>\s*ReviewedPalette\s*\{(.*?)\n\s*\}", source, re.S):
        colors = {}
        for key, values in re.findall(r"(\w+):\s*\[([^\]]+)\]", body):
            colors[key] = [int(value.strip(), 0) for value in values.split(",") if value.strip()]
        if name in names:
            for key in ["shell", "background", "foreground", "accent"]:
                if len(colors.get(key, [])) != 3:
                    raise ValueError(f"Missing authoritative {name}.{key}")
            palettes[name] = colors
    if set(palettes) != set(names):
        raise ValueError(f"Unparsed built-in palettes: {set(names) - set(palettes)}")
    output.mkdir(parents=True, exist_ok=True)
    (output / "themes.json").write_text(json.dumps(palettes, separators=(",", ":")), encoding="utf-8")
    shutil.copyfile(root / "assets/fonts/MapleMonoNormal-NF-CN-Regular.ttf", output / "terminal.ttf")
    notices = output / "licenses"
    notices.mkdir(exist_ok=True)
    for license_file in (root / "mobile/android/third_party/licenses").glob("*.txt"):
        shutil.copyfile(license_file, notices / license_file.name)
    for name in ("LICENSE.md", "UPSTREAM.md"):
        shutil.copyfile(root / "mobile/android/third_party/termux" / name, notices / ("Termux-" + name))
    shutil.copyfile(root / "mobile/android/third_party/THIRD-PARTY-NOTICES.md", notices / "THIRD-PARTY-NOTICES.md")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    generate(parser.parse_args().output)
