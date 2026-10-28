import logging
import threading

import cv2
import numpy as np

from .detect import REPO_ROOT

log = logging.getLogger(__name__)

CTD_PATH = REPO_ROOT / "models" / "comictextdetector.pt.onnx"
CTD_URL = ("https://github.com/zyddnys/manga-image-translator/"
           "releases/download/beta-0.3/comictextdetector.pt.onnx")

_net = None
_net_lock = threading.Lock()
_net_failed = False


def _ctd_net():
    """Lazy comic-text-detector ONNX net; None (logged once) if unusable."""
    global _net, _net_failed
    if _net is None and not _net_failed:
        try:
            if not CTD_PATH.exists():
                import torch
                CTD_PATH.parent.mkdir(parents=True, exist_ok=True)
                torch.hub.download_url_to_file(CTD_URL, CTD_PATH)
            _net = cv2.dnn.readNetFromONNX(str(CTD_PATH))
            if cv2.ocl.haveOpenCL():
                # GPU via OpenCL: ~1s/page vs ~28s on CPU dnn.
                cv2.ocl.setUseOpenCL(True)
                _net.setPreferableTarget(cv2.dnn.DNN_TARGET_OPENCL_FP16)
        except Exception as e:
            _net_failed = True
            log.warning("comic text detector unavailable, "
                        "falling back to threshold mask: %s", e)
    return _net


def _model_prob(img_bgr):
    """float32 HxW in [0,1]. Letterboxed 1024x1024, per dmMaze
    comic-text-detector preprocess_img/postprocess_mask."""
    net = _ctd_net()
    if net is None:
        return None
    h, w = img_bgr.shape[:2]
    r = min(1024 / h, 1024 / w)
    nh, nw = max(1, round(h * r)), max(1, round(w * r))
    rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
    resized = cv2.resize(rgb, (nw, nh), interpolation=cv2.INTER_LINEAR)
    padded = np.zeros((1024, 1024, 3), np.uint8)
    padded[:nh, :nw] = resized
    blob = cv2.dnn.blobFromImage(padded, 1 / 255.0, (1024, 1024))
    with _net_lock:
        net.setInput(blob)
        outs = net.forward(net.getUnconnectedOutLayersNames())
    seg = next((o for o in outs if o.shape == (1, 1, 1024, 1024)), None)
    if seg is None:
        seg = outs[0]
        if seg.ndim == 4 and seg.shape[1] != 1:
            seg = seg[:, :1]
    seg = seg[0, 0, :nh, :nw]
    return cv2.resize(seg, (w, h), interpolation=cv2.INTER_LINEAR)


def _prob(img_bgr):
    """_model_prob with failure -> None (logs once per process)."""
    global _net_failed
    if _net_failed:
        return None
    try:
        return _model_prob(img_bgr)
    except Exception as e:
        _net_failed = True
        log.warning("comic text detector failed, "
                    "falling back to threshold mask: %s", e)
        return None


def _pad(box, pad, shape):
    x1, y1, x2, y2 = box
    h, w = shape[:2]
    return (max(0, x1 - pad), max(0, y1 - pad), min(w, x2 + pad), min(h, y2 + pad))


def build_mask(img_bgr, boxes, masker="threshold", dilate=4, thresh=0.3,
               pad=4):
    """Full-page uint8 text mask. masker:
    threshold = per-box Otsu inverse; model = comic-text-detector prob;
    combined = union. Model output is kept only inside the padded boxes
    (text outside detected boxes isn't translated — don't erase SFX)."""
    mask = np.zeros(img_bgr.shape[:2], np.uint8)
    boxes = [tuple(b) for b in boxes]

    if masker in ("threshold", "combined"):
        gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
        for box in boxes:
            x1, y1, x2, y2 = _pad(box, pad, img_bgr.shape)
            crop = gray[y1:y2, x1:x2]
            if crop.size == 0:
                continue
            _, th = cv2.threshold(crop, 0, 255,
                                  cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
            mask[y1:y2, x1:x2] = np.maximum(mask[y1:y2, x1:x2], th)

    if boxes and masker in ("model", "combined"):
        prob = _prob(img_bgr)
        if prob is not None:
            pm = (prob > thresh).astype(np.uint8) * 255
            keep = np.zeros_like(mask)
            for box in boxes:
                x1, y1, x2, y2 = _pad(box, pad, img_bgr.shape)
                keep[y1:y2, x1:x2] = 255
            mask = np.maximum(mask, cv2.bitwise_and(pm, keep))
        elif masker == "model":  # model unavailable (already logged)
            return build_mask(img_bgr, boxes, "threshold", dilate, thresh, pad)

    if dilate > 0:
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE,
                                      (2 * dilate + 1, 2 * dilate + 1))
        mask = cv2.dilate(mask, k)
    return mask
