import argparse
import logging
import sys
from logging.handlers import RotatingFileHandler

import torch

from kei_core.config import LOG_DIR, LOG_PATH, Settings
from kei_core.service import ReaderService

from .app import ReaderApp

_QUIET = ("httpx", "httpcore", "urllib3", "PIL", "ultralytics",
          "transformers", "manga_ocr")

log = logging.getLogger(__name__)


def setup_logging(debug=False):
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    if sys.stderr:  # Windows console is cp1252; OCR text in debug logs would error
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    fmt = logging.Formatter(
        "%(asctime)s %(levelname)-5s %(threadName)s %(name)s: %(message)s")
    root = logging.getLogger()
    root.setLevel(logging.DEBUG if debug else logging.INFO)
    for h in (logging.StreamHandler(),
              RotatingFileHandler(LOG_PATH, maxBytes=1_000_000,
                                  backupCount=3, encoding="utf-8")):
        h.setFormatter(fmt)
        root.addHandler(h)
    if not debug:
        for name in _QUIET:
            logging.getLogger(name).setLevel(logging.WARNING)
    return LOG_PATH


def main():
    ap = argparse.ArgumentParser(prog="kei")
    ap.add_argument("chapter", nargs="?")
    ap.add_argument("--debug", action="store_true")
    args = ap.parse_args()
    log_path = setup_logging(args.debug)
    s = Settings.load()
    log.info("kei starting python=%s torch=%s cuda=%s device=%s inpainter=%s "
             "log=%s", sys.version.split()[0], torch.__version__,
             torch.cuda.is_available(), s.device, s.inpainter, log_path)
    app = ReaderApp(ReaderService(s))
    if args.chapter:
        app.after(200, lambda: app.open_path(args.chapter))
    app.mainloop()


if __name__ == "__main__":
    main()
