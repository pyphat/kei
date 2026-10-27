import cv2
import numpy as np

from kei_core import inpaint
from kei_core.mask import build_mask


def test_flat_fill_skips_lama(monkeypatch):
    monkeypatch.setattr(inpaint, "_lama_model",
                        lambda d: (_ for _ in ()).throw(AssertionError("lama loaded")))
    img = np.full((200, 200, 3), 255, np.uint8)
    cv2.putText(img, "X", (80, 120), cv2.FONT_HERSHEY_SIMPLEX, 1.5, (0, 0, 0), 3)
    box = (60, 60, 140, 150)
    m = build_mask(img, [box])
    out = inpaint.clean(img, m, [box], inpainter="auto", device="cpu")
    assert (out[m > 0] > 240).all()


def test_lama_mode_uses_lama(monkeypatch):
    calls = []
    monkeypatch.setattr(inpaint, "_lama", lambda c, m, d: calls.append(1) or c)
    img = np.full((60, 60, 3), 255, np.uint8)
    m = np.zeros((60, 60), np.uint8)
    m[10:20, 10:20] = 255
    inpaint.clean(img, m, [(5, 5, 25, 25)], inpainter="lama", device="cpu")
    assert calls
