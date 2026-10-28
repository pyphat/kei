"""Visual A/B for mask+feather rework: old mask/paste vs new threshold /
model / combined, on real pages. Writes backend/debug_out/clean_quality/."""
import sys
import time
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from kei_core.detect import detect, order_blocks
from kei_core import mask as M
from kei_core import inpaint as I
from kei_core.mask import build_mask

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parents[1] / "debug_out" / "clean_quality"
DEVICE = "cuda"


def old_clean(img, boxes):
    """Pre-rework behaviour: Otsu + 3x3 dilate x2 mask, inward-blur paste."""
    mask = np.zeros(img.shape[:2], np.uint8)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    k = np.ones((3, 3), np.uint8)
    for b in boxes:
        x1, y1, x2, y2 = M._pad(b, 4, img.shape)
        _, th = cv2.threshold(gray[y1:y2, x1:x2], 0, 255,
                              cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
        mask[y1:y2, x1:x2] = np.maximum(mask[y1:y2, x1:x2],
                                        cv2.dilate(th, k, iterations=2))
    out = img.copy()
    for b in boxes:
        x1, y1, x2, y2 = M._pad(b, 96, img.shape)
        m = mask[y1:y2, x1:x2]
        if not m.any():
            continue
        crop = out[y1:y2, x1:x2]
        fill = I._flat_fill(crop, m)
        if fill is not None:
            crop[m > 0] = fill
            continue
        res = I._lama(crop, m, DEVICE)
        a = cv2.GaussianBlur(m, (5, 5), 0).astype(np.float32) / 255
        out[y1:y2, x1:x2] = (
            res * a[..., None] + out[y1:y2, x1:x2] * (1 - a[..., None])
        ).astype(np.uint8)
    return out


def run(name):
    page = ROOT / "test" / f"{name}.jpg"
    img = cv2.imread(str(page))
    frames, texts = detect(img, 0.4)
    _, blocks = order_blocks(frames, texts, int(name), True)
    boxes = [b.bbox for b in blocks]
    print(f"{name}: {len(blocks)} blocks")

    t = time.time()
    cv2.imwrite(str(OUT / f"{name}_old.png"), old_clean(img, boxes))
    print(f"  old:     {time.time() - t:.1f}s")

    for masker in ("threshold", "model", "combined"):
        t = time.time()
        m = build_mask(img, boxes, masker=masker, dilate=4, thresh=0.3)
        t_mask = time.time() - t
        t = time.time()
        c = I.clean(img, m, boxes, "auto", DEVICE, feather=3)
        t_clean = time.time() - t
        cv2.imwrite(str(OUT / f"{name}_{masker}.png"), c)
        if masker == "combined":
            cv2.imwrite(str(OUT / f"{name}_mask.png"), m)
        print(f"  {masker:9s} mask={t_mask:.1f}s clean={t_clean:.1f}s")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    t = time.time()
    M._ctd_net()  # force model load, measure it
    print(f"CTD load: {time.time() - t:.1f}s")
    for name in sys.argv[1:] or ("001", "015"):
        run(name)


if __name__ == "__main__":
    main()
