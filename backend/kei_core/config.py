import json
import os
from dataclasses import dataclass, field, asdict
from pathlib import Path

import torch

CONFIG_PATH = Path.home() / ".kei" / "config.json"
DEFAULT_CACHE = Path.home() / ".kei" / "cache"
LOG_DIR = Path.home() / ".kei" / "logs"
LOG_PATH = LOG_DIR / "kei.log"


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
    det_conf: float = 0.4
    batch_pages: int = 5

    @property
    def openrouter_key(self) -> str:
        return self.api_key or os.environ.get("OPENROUTER_API_KEY", "")

    @property
    def cache_path(self) -> Path:
        return Path(self.cache_dir) if self.cache_dir else DEFAULT_CACHE

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
