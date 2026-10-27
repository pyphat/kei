"""Draw detector frames (blue) and text boxes (red, numbered in reading order)
for every page in test/ into backend/debug_out/boxes_XXX.jpg."""
import sys
from pathlib import Path

import cv2

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from kei_core.config import Settings
from kei_core.detect import detect, order_blocks

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parents[1] / "debug_out"


def main():
    OUT.mkdir(exist_ok=True)
    s = Settings.load()
    for i, page in enumerate(sorted((ROOT / "test").glob("*.jpg"))):
        img = cv2.imread(str(page))
        frames, texts = detect(img, s.det_conf)
        ordered_frames, blocks = order_blocks(frames, texts, i, s.reading_rtl)
        for n, (x1, y1, x2, y2) in enumerate(ordered_frames):
            cv2.rectangle(img, (x1, y1), (x2, y2), (255, 0, 0), 2)
            cv2.putText(img, f"f{n}", (x1, y1 + 18),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 0, 0), 2)
        for n, b in enumerate(blocks):
            x1, y1, x2, y2 = b.bbox
            cv2.rectangle(img, (x1, y1), (x2, y2), (0, 0, 255), 2)
            cv2.putText(img, str(n), (x1, y1 - 4),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)
        dest = OUT / f"boxes_{page.name}"
        cv2.imwrite(str(dest), img)
        print(page.name, f"{len(ordered_frames)} frames {len(blocks)} texts")


if __name__ == "__main__":
    main()
