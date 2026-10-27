import json
import logging
import re
import threading
import time
from datetime import date
from pathlib import Path

import httpx

log = logging.getLogger(__name__)

MODELS_URL = "https://openrouter.ai/api/v1/models"
CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"

_SYSTEM = (
    "You are a professional manga translator. Translate Japanese manga dialogue "
    "into natural, concise {target_lang} suitable for speech bubbles. Keep each "
    "speaker's tone, keep honorifics like -san and -kun, and translate sound "
    "effects briefly. The input is a JSON object mapping bubble IDs to Japanese "
    "text, in reading order across consecutive pages; use earlier lines as "
    "context. Reply with ONLY a JSON object mapping every same ID to its "
    "translation, with no commentary and no code fences."
)


def _parse(text):
    text = re.sub(r"```(?:json)?", "", text).strip()
    i, j = text.find("{"), text.rfind("}")
    if i < 0 or j <= i:
        return {}
    try:
        return json.loads(text[i:j + 1])
    except json.JSONDecodeError:
        return {}


class Translator:
    def translate(self, items: dict) -> dict:
        raise NotImplementedError


class OpenRouterTranslator(Translator):
    def __init__(self, settings, client=None):
        self.s = settings
        self.client = client or httpx.Client(timeout=60)
        self._free = None
        self._lock = threading.Lock()

    # --- model list / usage counter ---

    def list_free_models(self):
        if self._free is None:
            r = self._request("GET", MODELS_URL)
            r.raise_for_status()
            free = []
            for m in r.json().get("data", []):
                p = m.get("pricing") or {}
                zero = str(p.get("prompt")) == "0" and str(p.get("completion")) == "0"
                if zero or m["id"].endswith(":free"):
                    free.append((m["id"], m.get("name") or m["id"]))
            self._free = free
        return self._free

    def _chain(self):
        free = [mid for mid, _ in self.list_free_models()]
        chain = [m for m in self.s.model_priority if m in free]
        chain += [m for m in free if m not in chain and m != "openrouter/free"]
        if "openrouter/free" in free:
            chain.append("openrouter/free")
        return [m for m in chain if m != "openrouter/auto"]

    def _usage_file(self):
        return self.s.cache_path / "usage.json"

    def _request(self, method, url, **kw):
        with self._lock:
            f = self._usage_file()
            f.parent.mkdir(parents=True, exist_ok=True)
            try:
                data = json.loads(f.read_text())
            except Exception:
                data = {}
            today = date.today().isoformat()
            data[today] = data.get(today, 0) + 1
            f.write_text(json.dumps(data))
        return self.client.request(method, url, **kw)

    def requests_today(self):
        try:
            return json.loads(self._usage_file().read_text()).get(date.today().isoformat(), 0)
        except Exception:
            return 0

    # --- chat ---

    def _ask(self, model, items, headers):
        body = {
            "model": model,
            "temperature": 0.3,
            "messages": [
                {"role": "system", "content": _SYSTEM.format(target_lang=self.s.target_lang)},
                {"role": "user", "content": json.dumps(items, ensure_ascii=False)},
            ],
        }
        return self._request("POST", CHAT_URL, json=body, headers=headers)

    def _ask_once(self, model, items, headers):
        """One logical attempt = POST, retry once on 429. Returns parsed dict or None."""
        for attempt in range(2):
            t = time.time()
            try:
                r = self._ask(model, items, headers)
            except httpx.HTTPError as e:
                log.info("model %s attempt %d: %s %.1fs", model, attempt,
                         type(e).__name__, time.time() - t)
                raise
            dt = time.time() - t
            log.info("model %s attempt %d: HTTP %d %.1fs",
                     model, attempt, r.status_code, dt)
            if r.status_code == 401:
                raise RuntimeError("OpenRouter rejected the API key (401)")
            if r.status_code == 429:
                if attempt == 0:
                    try:
                        wait = min(float(r.headers.get("Retry-After", 0)), 10)
                    except ValueError:
                        wait = 0
                    time.sleep(wait)
                    continue
                return None
            if r.status_code >= 500 or r.status_code == 404 or (
                r.status_code == 400 and "model" in r.text.lower()
            ):
                return None
            if r.status_code >= 400:
                return None
            try:
                content = r.json()["choices"][0]["message"]["content"]
            except Exception:
                return None
            log.debug("model %s response: %.500s", model, content)
            parsed = _parse(content)
            return parsed if parsed else None
        return None

    def translate(self, items: dict) -> dict:
        if not items:
            return {}
        if not self.s.openrouter_key:
            raise RuntimeError("No OpenRouter API key (set it in settings or OPENROUTER_API_KEY)")
        headers = {
            "Authorization": f"Bearer {self.s.openrouter_key}",
            "HTTP-Referer": "https://github.com/kei",
            "X-Title": "kei",
        }
        last = "no models tried"
        for model in self._chain():
            try:
                parsed = self._ask_once(model, items, headers)
            except httpx.HTTPError:
                continue
            if parsed is None:
                last = f"{model} failed"
                continue
            missing = {k: v for k, v in items.items() if k not in parsed}
            if missing:
                log.info("model %s missing %d ids, re-asking",
                         model, len(missing))
                try:
                    again = self._ask_once(model, missing, headers)
                except httpx.HTTPError:
                    again = None
                if again:
                    parsed.update(again)
            log.info("model %s succeeded", model)
            log.info("requests today: %d", self.requests_today())
            return {k: str(v) for k, v in parsed.items() if k in items}
        log.info("all models failed (%s)", last)
        raise RuntimeError(f"All free models failed ({last})")
