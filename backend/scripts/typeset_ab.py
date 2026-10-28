"""A/B typeset render from cached page state (no OpenRouter): old per-block
fit vs the new widened/median render. Writes debug_out/typeset/."""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np

from kei_core.config import Settings
from kei_core.models import TextBlock
from kei_core import typeset as T

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / ".devdata" / "cache"
OUT = Path(__file__).resolve().parents[1] / "debug_out" / "typeset"


def load_page(d):
    bj = json.loads((d / "blocks.json").read_text("utf-8"))
    tr = json.loads((d / "translations.json").read_text("utf-8"))
    blocks = [TextBlock.from_dict(b) for b in bj.get("blocks", [])]
    for b in blocks:
        b.translation = tr.get(b.id, "")
    clean = Image.open(d / "clean.png").convert("RGB")
    return clean, blocks


def old_render(clean_img, blocks, s):
    """Pre-change behaviour: own _render_box, independent max-fit size."""
    out = clean_img.copy().convert("RGB")
    draw = ImageDraw.Draw(out)
    gray = np.array(out.convert("L"))
    for b in blocks:
        if b.translation:
            box = T._render_box(gray, b.bbox)
            size, _, _ = T._fit(draw, b.translation, box,
                                s.resolved_font, s.font_min, s.font_max)
            T._draw_block(draw, b.translation, box, s.resolved_font, size)
    return out


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    s = Settings.load()
    # pages: (subdir, tag, render_before?)
    pages = [("cef1af47d389/98b5c23c2359", "target", True),
             ("cef1af47d389/08c34945708a", "p2", True),
             ("cef1af47d389/fcf4efe935af", "p3", True)]
    for sub, tag, before in pages:
        d = CACHE / sub
        clean, blocks = load_page(d)
        print(tag, sub, "blocks:", len(blocks),
              "translated:", sum(1 for b in blocks if b.translation))
        T.render(clean, blocks, s).save(OUT / f"{tag}_after.png")
        if before:
            old_render(clean, blocks, s).save(OUT / f"{tag}_before.png")
    print("wrote", OUT)


if __name__ == "__main__":
    main()
