import logging

import cv2
import numpy as np

from .detect import REPO_ROOT
from .mask import _pad

log = logging.getLogger(__name__)

LAMA_PATH = REPO_ROOT / "models" / "big-lama.pt"
LAMA_URL = "https://github.com/enesmsahin/simple-lama-inpainting/releases/download/v0.1.0/big-lama.pt"

_lama_models = {}


def _lama_model(device):
    if device not in _lama_models:
        import torch

        if not LAMA_PATH.exists():
            LAMA_PATH.parent.mkdir(parents=True, exist_ok=True)
            torch.hub.download_url_to_file(LAMA_URL, LAMA_PATH)
        m = torch.jit.load(str(LAMA_PATH), map_location=device)
        m.eval()
        _lama_models[device] = m
    return _lama_models[device]


def _lama(crop_bgr, mask, device):
    import torch

    img = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2RGB).astype(np.float32) / 255
    img = img.transpose(2, 0, 1)
    msk = (mask > 0).astype(np.float32)[None]
    oh, ow = img.shape[1:]
    ph = (-oh) % 8
    pw = (-ow) % 8
    img = np.pad(img, ((0, 0), (0, ph), (0, pw)), mode="symmetric")
    msk = np.pad(msk, ((0, 0), (0, ph), (0, pw)), mode="symmetric")
    model = _lama_model(device)
    with torch.inference_mode():
        out = model(torch.from_numpy(img)[None].to(device),
                    torch.from_numpy(msk)[None].to(device))
    res = out[0].permute(1, 2, 0).cpu().numpy()
    res = np.clip(res * 255, 0, 255).astype(np.uint8)[:oh, :ow]
    return cv2.cvtColor(res, cv2.COLOR_RGB2BGR)


def _flat_fill(crop, mask):
    """If the ring around the mask is uniform and light, return its median BGR."""
    ring = cv2.dilate(mask, np.ones((5, 5), np.uint8)) & ~mask
    if not ring.any():
        return None
    px = crop[ring > 0]
    if px.std(axis=0).max() >= 12:
        return None
    med = np.median(px, axis=0)
    if med.mean() < 170:
        return None
    return med


def _paste(out, x1, y1, patch, mask, feather=3):
    if feather > 0:
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE,
                                      (2 * feather + 1, 2 * feather + 1))
        soft = cv2.GaussianBlur(cv2.dilate(mask, k),
                                (2 * feather + 1, 2 * feather + 1),
                                0).astype(np.float32) / 255
        # everything inside the mask is fully replaced; only the grown
        # edge blends outward
        a = np.maximum(soft, mask.astype(np.float32) / 255)
    else:
        a = (mask > 0).astype(np.float32)
    roi = out[y1:y1 + patch.shape[0], x1:x1 + patch.shape[1]]
    out[y1:y1 + patch.shape[0], x1:x1 + patch.shape[1]] = (
        patch * a[..., None] + roi * (1 - a[..., None])).astype(np.uint8)


def clean(img_bgr, mask, boxes, inpainter="auto", device="cpu", feather=3):
    """Inpaint masked pixels. Per box: flat fill -> cv2.inpaint or LaMa,
    routed by inpainter: opencv = flat|cv2, lama = always LaMa, auto = flat|LaMa."""
    out = img_bgr.copy()
    counts = {"flat": 0, "lama": 0, "opencv": 0}
    for box in boxes:
        x1, y1, x2, y2 = _pad(box, 96, img_bgr.shape)
        m = mask[y1:y2, x1:x2]
        if not m.any():
            continue
        crop = out[y1:y2, x1:x2]
        if inpainter != "lama":
            fill = _flat_fill(crop, m)
            if fill is not None:
                crop[m > 0] = fill
                counts["flat"] += 1
                continue
        if inpainter == "opencv":
            res = cv2.inpaint(crop, m, 3, cv2.INPAINT_TELEA)
            counts["opencv"] += 1
        else:
            res = _lama(crop, m, device)
            counts["lama"] += 1
        _paste(out, x1, y1, res, m, feather)
    log.info("inpaint routing flat=%d lama=%d opencv=%d",
             counts["flat"], counts["lama"], counts["opencv"])
    return out
