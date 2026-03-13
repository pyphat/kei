import os
import threading

os.environ["TRANSFORMERS_OFFLINE"] = "1"
os.environ["HF_HUB_OFFLINE"] = "1"
from google import genai
from manga_ocr import MangaOcr
from ultralytics import YOLO

API_KEY = "..."

_mangaOcr = None
_model = None
_client = None

_manga_ocr_lock = threading.Lock()
_model_lock = threading.Lock()
_init_event = threading.Event()
_yolo_lock = threading.Lock()


def _init_heavy_models():
    """Load MangaOCR and YOLO in background so startup is not blocked."""
    get_model()
    get_manga_ocr()
    _init_event.set()
    print("[DEBUG] background model init complete")


def warm_up():
    """Call once at startup to begin loading models in the background."""
    t = threading.Thread(target=_init_heavy_models, daemon=True)
    t.start()


def get_client():
    global _client
    if _client is None:
        _client = genai.Client(api_key=API_KEY)
    return _client


def get_manga_ocr():
    global _mangaOcr
    if _mangaOcr is None:
        with _manga_ocr_lock:
            if _mangaOcr is None:
                print("[DEBUG] initializing manga-ocr")
                _mangaOcr = MangaOcr()
    return _mangaOcr


def get_model():
    global _model
    if _model is None:
        with _model_lock:
            if _model is None:
                print("[DEBUG] initializing YOLO model")
                _model = YOLO(r"C:\Coding\Python\kei\src\models\manga109.pt")
    return _model


def scan(cropped):
    """Run MangaOCR on a cropped image region."""
    return get_manga_ocr()(cropped)


def translate(text: str) -> str:
    """Translate Japanese text to English via Gemini."""
    try:
        response = get_client().models.generate_content(
            model="gemini-2.0-flash",
            contents=(
                f"Translate the following from Japanese to English, "
                f"no need for other answers. Just the translation: {text}"
            ),
        )
        return str(response.text)
    except Exception as e:
        print(f"[DEBUG] translation error: {e}")
        return "Translation failed. 🎉🎉🎉"
