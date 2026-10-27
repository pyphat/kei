# kei web frontend

1. Run the backend: `python -m uvicorn api_fastapi.app:app --host 127.0.0.1 --port 8000` (from repo root, `pip install -e backend` first).
2. Here: `npm install && npm run dev`, open http://localhost:3000.
3. Backend URL env var: `KEI_API_URL` (default `http://127.0.0.1:8000`). The browser only talks to :3000 — `/api/*` is proxied.
4. Pure-logic tests: `node --test lib/*.test.ts` (Node ≥ 20). E2E: `npx playwright test`.
