# kei FastAPI backend

Run from repo root after `pip install -e backend`:

    python -m uvicorn api_fastapi.app:app --host 127.0.0.1 --port 8000

Optional env: `KEI_LIBRARY_DIR`, `KEI_CACHE_DIR` (default `~/.kei/...`).
The Next.js frontend proxies `/api/*` to this server.
