import json
import logging
import os
from dataclasses import dataclass, field, asdict
from logging.handlers import RotatingFileHandler
from pathlib import Path

import torch

CONFIG_PATH = Path.home() / ".kei" / "config.json"
DEFAULT_CACHE = Path.home() / ".kei" / "cache"
LOG_DIR = Path.home() / ".kei" / "logs"
LOG_PATH = LOG_DIR / "kei.log"
DEFAULT_LIBRARY = Path.home() / ".kei" / "library"

_QUIET = ("httpx", "httpcore", "urllib3", "PIL", "ultralytics",
          "transformers", "manga_ocr")


def setup_logging(debug=False):
    """App-side logging: console + rotating file. Libraries must not call this."""
    LOG_DIR.mkdir(parents=True, exist_ok=True)
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


def _default_font() -> str:
    for name in ("comic.ttf", "arial.ttf"):
        p = Path("C:/Windows/Fonts") / name
        if p.exists():
            return str(p)
    return ""  # PIL default


def resolve_device(device: str) -> str:
    if device in ("auto", "cuda"):
        return "cuda" if torch.cuda.is_available() else "cpu"
    return "cpu"


@dataclass
class Settings:
    api_key: str = ""  # falls back to OPENROUTER_API_KEY env
    model_priority: list[str] = field(default_factory=list)
    target_lang: str = "English"
    reading_rtl: bool = True
    font_path: str = ""  # "" -> comic.ttf, then arial.ttf, then PIL default
    font_min: int = 10
    font_max: int = 28
    inpainter: str = "auto"  # auto | opencv | lama
    device: str = "auto"  # auto | cuda | cpu
    cache_dir: str = ""
    library_dir: str = ""
    det_conf: float = 0.4
    batch_pages: int = 5
    masker: str = "combined"  # threshold | model | combined
    mask_dilate: int = 4      # px to grow the text mask
    mask_feather: int = 3     # px soft edge, outside the mask
    mask_thresh: float = 0.3  # text-model probability cutoff

    @property
    def openrouter_key(self) -> str:
        return self.api_key or os.environ.get("OPENROUTER_API_KEY", "")

    @property
    def cache_path(self) -> Path:
        return Path(self.cache_dir) if self.cache_dir else DEFAULT_CACHE

    @property
    def library_path(self) -> Path:
        return Path(self.library_dir) if self.library_dir else DEFAULT_LIBRARY

    @property
    def resolved_font(self) -> str:
        return self.font_path or _default_font()

    @property
    def resolved_device(self) -> str:
        return resolve_device(self.device)

    @classmethod
    def load(cls, path: Path = CONFIG_PATH) -> "Settings":
        if path.exists():
            known = {f for f in cls.__dataclass_fields__}
            data = {k: v for k, v in json.loads(path.read_text()).items() if k in known}
            return cls(**data)
        return cls()

    def save(self, path: Path = CONFIG_PATH):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(asdict(self), indent=2))
