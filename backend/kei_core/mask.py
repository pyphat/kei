import cv2
import numpy as np


def _pad(box, pad, shape):
    x1, y1, x2, y2 = box
    h, w = shape[:2]
    return (max(0, x1 - pad), max(0, y1 - pad), min(w, x2 + pad), min(h, y2 + pad))


def build_mask(img_bgr, boxes, pad=4):
    """Full-page uint8 mask of dark text pixels inside each box (Otsu inverse)."""
    mask = np.zeros(img_bgr.shape[:2], np.uint8)
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    k = np.ones((3, 3), np.uint8)
    for box in boxes:
        x1, y1, x2, y2 = _pad(box, pad, img_bgr.shape)
        crop = gray[y1:y2, x1:x2]
        if crop.size == 0:
            continue
        _, th = cv2.threshold(crop, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
        th = cv2.dilate(th, k, iterations=2)
        mask[y1:y2, x1:x2] = np.maximum(mask[y1:y2, x1:x2], th)
    return mask
