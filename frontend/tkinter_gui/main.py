import argparse
import logging
import sys

import torch

from kei_core.config import Settings, setup_logging
from kei_core.service import ReaderService

from .app import ReaderApp

log = logging.getLogger(__name__)


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
