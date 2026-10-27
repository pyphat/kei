"""Real-model smoke run on test/015.jpg: detect -> manga-ocr -> LaMa and
OpenCV cleans -> fake-translated render. Writes to backend/debug_out/."""
import sys
import time
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from kei_core.config import Settings
from kei_core.detect import detect, order_blocks
from kei_core.mask import build_mask
from kei_core.inpaint import clean
from kei_core.typeset import render

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parents[1] / "debug_out"
PAGE = ROOT / "test" / "015.jpg"


def main():
    OUT.mkdir(exist_ok=True)
    s = Settings.load()
    img = cv2.imread(str(PAGE))
    frames, texts = detect(img, s.det_conf)
    frames, blocks = order_blocks(frames, texts, 15, s.reading_rtl)
    print(f"{len(frames)} frames, {len(blocks)} text blocks")

    from kei_core.ocr import get_ocr
    ocr = get_ocr()
    page = Image.open(PAGE)
    for b in blocks:
        t = time.time()
        b.source = ocr(page.crop(b.bbox))
        print(f"{b.id} ({time.time() - t:.2f}s): {b.source}")

    boxes = [b.bbox for b in blocks]
    m = build_mask(img, boxes)

    t = time.time()
    clean_l = clean(img, m, boxes, "lama", s.resolved_device)
    print(f"lama clean: {time.time() - t:.1f}s")
    cv2.imwrite(str(OUT / "clean_015_lama.png"), clean_l)

    t = time.time()
    clean_o = clean(img, m, boxes, "opencv", s.resolved_device)
    print(f"opencv clean: {time.time() - t:.1f}s")
    cv2.imwrite(str(OUT / "clean_015_opencv.png"), clean_o)

    for b in blocks:
        b.translation = f"EN:{b.id}"
    rendered = render(Image.fromarray(cv2.cvtColor(clean_l, cv2.COLOR_BGR2RGB)),
                      blocks, s)
    rendered.save(OUT / "translated_015_fake.png")
    print("wrote", OUT)


if __name__ == "__main__":
    main()
