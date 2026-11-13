# kei FastAPI backend

Run from repo root after `pip install -e backend`:

    python -m uvicorn api_fastapi.app:app --host 0.0.0.0 --port 8000

Optional env: `KEI_LIBRARY_DIR`, `KEI_CACHE_DIR` (default `~/.kei/...`).
The Next.js frontend proxies `/api/*` to this server.
Phones must pair in Settings → Devices in the web app.
Set `KEI_PORT` to match uvicorn's `--port` if you change it.
