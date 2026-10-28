import cv2
import numpy as np

from kei_core import mask as mask_mod
from kei_core.mask import build_mask

BOX = (60, 60, 140, 140)


def _page():
    img = np.full((200, 200, 3), 255, np.uint8)
    cv2.putText(img, "X", (80, 80), cv2.FONT_HERSHEY_SIMPLEX, 2, (0, 0, 0), 4)
    return img


def test_threshold_marks_text():
    m = build_mask(_page(), [BOX], masker="threshold", dilate=0)
    assert m.sum() > 0 and not m[:56].any()


def test_dilate_grows_mask():
    img = _page()
    a = build_mask(img, [BOX], masker="threshold", dilate=0)
    b = build_mask(img, [BOX], masker="threshold", dilate=8)
    assert (b > 0).sum() > (a > 0).sum()


def test_model_output_limited_to_boxes(monkeypatch):
    # model "sees" text everywhere — mask must stay inside padded boxes
    monkeypatch.setattr(mask_mod, "_model_prob",
                        lambda img: np.full(img.shape[:2], 0.9, np.float32))
    m = build_mask(_page(), [BOX], masker="model", dilate=0)
    assert m[100, 100] > 0
    assert not m[:50].any() and not m[150:].any()
    assert not m[:, :50].any() and not m[:, 150:].any()


def test_combined_is_union(monkeypatch):
    prob = np.zeros((200, 200), np.float32)
    prob[95:105, 120:130] = 0.9  # model-only region threshold won't catch
    monkeypatch.setattr(mask_mod, "_model_prob", lambda img: prob)
    img = _page()
    c = build_mask(img, [BOX], masker="combined", dilate=0)
    t = build_mask(img, [BOX], masker="threshold", dilate=0)
    assert (c[95:105, 120:130] > 0).all()   # model part present
    assert (c[t > 0] > 0).all()             # threshold part ⊂ union
    assert (c > 0).sum() > (t > 0).sum()


def test_model_failure_falls_back(monkeypatch):
    def boom(img):
        raise RuntimeError("dnn exploded")
    monkeypatch.setattr(mask_mod, "_model_prob", boom)
    monkeypatch.setattr(mask_mod, "_net_failed", False)
    m = build_mask(_page(), [BOX], masker="model", dilate=0)
    assert m.sum() > 0  # threshold mask used instead


def test_model_none_falls_back(monkeypatch):
    monkeypatch.setattr(mask_mod, "_model_prob", lambda img: None)
    monkeypatch.setattr(mask_mod, "_net_failed", False)
    m = build_mask(_page(), [BOX], masker="model", dilate=0)
    assert m.sum() > 0
