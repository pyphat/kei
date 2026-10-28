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


def test_feather_fully_replaces_inside_mask():
    # mask is the box crop — same shape as the patch, like clean() calls it
    img = np.zeros((60, 60, 3), np.uint8)
    m = np.zeros((40, 40), np.uint8)
    m[10:20, 10:20] = 255
    patch = np.full((40, 40, 3), 200, np.uint8)
    out = img.copy()
    inpaint._paste(out, 10, 10, patch, m, feather=3)
    # every pixel inside the mask is fully replaced — no original survives
    assert (out[20:30, 20:30] == 200).all()
    # the edge blends outward: ring just outside is between 0 and 200
    ring = out[17:20, 17:33]
    assert (ring > 0).any() and (ring < 200).any()


def test_feather_zero_is_hard_paste():
    img = np.zeros((60, 60, 3), np.uint8)
    m = np.zeros((40, 40), np.uint8)
    m[10:20, 10:20] = 255
    patch = np.full((40, 40, 3), 200, np.uint8)
    out = img.copy()
    inpaint._paste(out, 10, 10, patch, m, feather=0)
    assert (out[20:30, 20:30] == 200).all()
    assert (out[19, 20:30] == 0).all()  # no blend outside the mask
