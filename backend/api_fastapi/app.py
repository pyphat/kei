import asyncio
import hashlib
import hmac
import ipaddress
import io
import json
import logging
import os
import secrets
import shutil
import socket
import tempfile
import time
import zipfile
from contextlib import asynccontextmanager
from dataclasses import asdict
from pathlib import Path
from urllib.parse import quote

import segno
from fastapi import Body, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, JSONResponse, Response, StreamingResponse
from PIL import Image, ImageFont
from zeroconf import ServiceInfo, Zeroconf

from kei_core.config import Settings, setup_logging
from kei_core.library import EXTS, Library, natkey, norm_ext


def _ext_field(e):
    e = str(e).lower().lstrip(".")
    return ("jpg" if e == "jpeg" else e) if e in EXTS else None
from kei_core.service import chapter_key

from .events import publish, subscribe, unsubscribe
from .jobs import Runner

log = logging.getLogger(__name__)

MAX_PAGE_BYTES = 50 * 1024 * 1024
MAX_ARCHIVE_BYTES = 1024 * 1024 * 1024
MAX_ARCHIVE_EXPANDED = 2 * 1024 * 1024 * 1024
_IMMUTABLE = {"Cache-Control": "public, max-age=31536000, immutable"}


def _env_settings():
    s = Settings.load()
    if os.environ.get("KEI_LIBRARY_DIR"):
        s.library_dir = os.environ["KEI_LIBRARY_DIR"]
    if os.environ.get("KEI_CACHE_DIR"):
        s.cache_dir = os.environ["KEI_CACHE_DIR"]
    return s


def _lan_ip():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("10.255.255.255", 1))
            return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"


# shortcut: resized renditions are recomputed per request, add a disk cache if phones feel slow
def _jpeg_w(img, w) -> bytes:
    width = max(64, min(4096, w))
    resized = img.resize(
        (width, max(1, round(img.height * width / img.width))),
        Image.Resampling.LANCZOS)
    buf = io.BytesIO()
    resized.convert("RGB").save(buf, "JPEG", quality=85)
    return buf.getvalue()


def create_app(settings=None, translator=None, ocr=None, mdns=False):
    if not logging.getLogger().handlers:
        setup_logging()
    s = settings or _env_settings()
    lib = Library(s.library_path)
    runner = Runner(lib, s, translator=translator, ocr=ocr)
    pair = {}

    @asynccontextmanager
    async def lifespan(_app):
        zc = info = None
        if mdns:
            try:
                hostname = socket.gethostname()
                # shortcut: KEI_PORT must match uvicorn's --port, pass it if you change the port
                port = int(os.environ.get("KEI_PORT", "8000"))
                info = ServiceInfo(
                    "_kei._tcp.local.",
                    f"kei on {hostname}._kei._tcp.local.",
                    addresses=[socket.inet_aton(_lan_ip())],
                    port=port,
                    properties={"api": "1"})
                zc = Zeroconf()
                await run_in_threadpool(zc.register_service, info)
            except Exception as e:
                log.warning("mDNS registration failed: %r", e)
                if zc:
                    try:
                        await run_in_threadpool(zc.close)
                    except Exception as close_error:
                        log.warning("mDNS close failed: %s", close_error)
                    zc = None
        try:
            yield
        finally:
            if zc and info:
                try:
                    await run_in_threadpool(zc.unregister_service, info)
                except Exception as e:
                    log.warning("mDNS unregister failed: %s", e)
                try:
                    await run_in_threadpool(zc.close)
                except Exception as e:
                    log.warning("mDNS close failed: %s", e)

    app = FastAPI(title="kei", lifespan=lifespan)
    app.state.runner = runner
    app.state.lib = lib
    app.state.settings = s

    def is_local(request):
        try:
            return ipaddress.ip_address(request.client.host).is_loopback
        except (AttributeError, ValueError):
            return False

    def token_device(request):
        header = request.headers.get("authorization", "")
        scheme, sep, token = header.partition(" ")
        if not sep or scheme.lower() != "bearer" or not token:
            return None
        return lib.device_by_token(hashlib.sha256(token.encode()).hexdigest())

    def mark_device(request, device):
        request.state.device_id = device["id"]
        now = time.time()
        if now - device["last_seen"] > 60:
            lib.touch_device(device["id"], now)

    @app.middleware("http")
    async def require_device(request: Request, call_next):
        path = request.url.path
        if not path.startswith("/api/"):
            return await call_next(request)
        request.state.device_id = None
        if is_local(request):
            return await call_next(request)
        if ((request.method == "GET" and path == "/api/server")
                or (request.method == "POST" and path == "/api/devices/pair")
                or (request.method == "GET" and path == "/api/devices/pair-code")):
            return await call_next(request)
        device = token_device(request)
        if not device:
            return JSONResponse({"detail": "pairing required"}, status_code=401)
        mark_device(request, device)
        return await call_next(request)

    @app.get("/api/server")
    def server_info(request: Request):
        auth = "local" if is_local(request) else "required"
        if auth != "local":
            device = token_device(request)
            if device:
                mark_device(request, device)
                auth = "ok"
        return {"name": "kei", "hostname": socket.gethostname(),
                "version": "0.1.0", "api": 1, "auth": auth}

    @app.get("/api/devices/pair-code")
    def pair_code(request: Request):
        if not is_local(request):
            raise HTTPException(403, "pairing code is local only")
        now = time.time()
        pin = f"{secrets.randbelow(1_000_000):06d}"
        secret = secrets.token_urlsafe(16)
        pair.update(pin=pin, secret=secret, expires=now + 300, fails=0)
        lan_ip = _lan_ip()
        base = f"http://{lan_ip}:{request.url.port or 8000}"
        payload = f"kei://pair?u={quote(base)}&s={secret}"
        return {"pin": pin, "expires": pair["expires"], "url": base,
                "qr": payload,
                "qr_svg": segno.make(payload, error="m").svg_inline(
                    scale=6, border=2, dark="#000", light="#fff")}

    @app.post("/api/devices/pair")
    def pair_device(body: dict = Body(...)):
        now = time.time()
        pin, secret = body.get("pin"), body.get("secret")
        valid = bool(pair and now < pair["expires"]) and (
            isinstance(pin, str) and pin.isascii()
            and hmac.compare_digest(pin, pair["pin"])
            or isinstance(secret, str)
            and secret.isascii()
            and hmac.compare_digest(secret, pair["secret"]))
        if not valid:
            if pair:
                pair["fails"] += 1
                if pair["fails"] >= 5:
                    pair.clear()
            raise HTTPException(403, "invalid or expired code")
        pair.clear()
        name = body.get("name")
        name = name.strip()[:60] if isinstance(name, str) else ""
        name = name or "Android device"
        token = secrets.token_urlsafe(32)
        device_id = lib.add_device(
            name, hashlib.sha256(token.encode()).hexdigest())
        publish({"type": "devices"})
        return {"device_id": device_id, "token": token, "name": name}

    @app.get("/api/devices")
    def devices_list(request: Request):
        return [{"id": d["id"], "name": d["name"], "created": d["created"],
                 "last_seen": d["last_seen"],
                 "current": d["id"] == request.state.device_id}
                for d in lib.list_devices()]

    @app.patch("/api/devices/{device_id}")
    def device_rename(device_id: int, body: dict = Body(...)):
        if not lib.get_device(device_id):
            raise HTTPException(404, "device not found")
        name = body.get("name")
        if not isinstance(name, str):
            raise HTTPException(422, "name must be a string")
        name = name.strip()[:60] or "Android device"
        lib.rename_device(device_id, name)
        publish({"type": "devices"})
        return {"id": device_id, "name": name}

    @app.delete("/api/devices/{device_id}")
    def device_delete(device_id: int):
        if not lib.get_device(device_id):
            raise HTTPException(404, "device not found")
        lib.delete_device(device_id)
        publish({"type": "devices"})
        return {"ok": True}

    def series_json(row):
        pr = lib.get_progress(row["id"])
        return {
            "id": row["id"], "title": row["title"], "author": row["author"],
            "tags": json.loads(row["tags"] or "[]"),
            "cover_url": (f"/api/series/{row['id']}/cover?v={row['updated']}"
                          if row["has_cover"] else None),
            "chapter_count": row["chapter_count"] if "chapter_count" in row.keys()
            else len(lib.list_chapters(row["id"])),
            "page_count": row["page_count"] if "page_count" in row.keys()
            else lib.series_page_count(row["id"]),
            "updated": row["updated"],
            "progress": {"chapter_id": pr["chapter_id"], "page": pr["page"],
                         "percent": pr["percent"]} if pr else None,
        }

    def get_series_or_404(sid):
        row = lib.get_series(sid)
        if not row:
            raise HTTPException(404, "series not found")
        return row

    def get_chapter_or_404(cid):
        row = lib.get_chapter(cid)
        if not row:
            raise HTTPException(404, "chapter not found")
        return row

    def maybe_autocover(cid):
        ch = lib.get_chapter(cid)
        ser = lib.get_series(ch["series_id"])
        if not ser["has_cover"] and lib.chapter_complete(cid):
            idxs = lib.page_indices(cid)
            f = lib.page_file(cid, idxs[0])
            if f:
                lib.save_cover(ser["id"], Image.open(f))
                publish({"type": "library"})

    def drop_chapter_files(cid):
        ch = lib.get_chapter(cid)
        if not ch:
            return
        try:  # service cache dir, if it exists
            ck = chapter_key(lib.chapter_dir(ch["series_id"], cid))
            shutil.rmtree(s.cache_path / ck, ignore_errors=True)
        except Exception as e:
            log.warning("could not remove service cache for %s: %s", cid, e)
        runner.drop_service(cid)
        shutil.rmtree(lib.chapter_dir(ch["series_id"], cid), ignore_errors=True)

    # ---------- library ----------

    @app.get("/api/series")
    def series_list():
        return [series_json(r) for r in lib.list_series()]

    @app.get("/api/series/{sid}")
    def series_get(sid: int):
        row = get_series_or_404(sid)
        chapters = [{"id": c["id"], "number": c["number"], "title": c["title"],
                     **runner.chapter_stats(c["id"])}
                    for c in lib.list_chapters(sid)]
        return {**series_json(row), "chapters": chapters}

    @app.patch("/api/series/{sid}")
    def series_patch(sid: int, body: dict = Body(...)):
        get_series_or_404(sid)
        tags = body.get("tags")
        if tags is not None and not isinstance(tags, list):
            raise HTTPException(400, "tags must be a list")
        lib.update_series(sid, body.get("title"), body.get("author"), tags)
        publish({"type": "library"})
        return series_json(lib.get_series(sid))

    @app.delete("/api/series/{sid}")
    def series_delete(sid: int):
        get_series_or_404(sid)
        for c in lib.list_chapters(sid):
            drop_chapter_files(c["id"])
        lib.delete_series(sid)
        shutil.rmtree(lib.series_dir(sid), ignore_errors=True)
        publish({"type": "library"})
        return {"ok": True}

    @app.get("/api/series/{sid}/cover")
    def cover_get(sid: int, w: int | None = None):
        get_series_or_404(sid)
        f = lib.cover_file(sid)
        if not f.exists():
            raise HTTPException(404, "no cover")
        if w is not None:
            w = max(64, min(4096, w))
            with Image.open(f) as img:
                if w < img.width:
                    return Response(_jpeg_w(img, w), media_type="image/jpeg",
                                    headers=_IMMUTABLE)
        return FileResponse(f, media_type="image/jpeg", headers=_IMMUTABLE)

    @app.post("/api/series/{sid}/cover")
    async def cover_post(sid: int, file: UploadFile = File(...)):
        get_series_or_404(sid)
        data = await file.read()
        if len(data) > 20 * 1024 * 1024:
            raise HTTPException(400, "cover too large")
        try:
            img = Image.open(io.BytesIO(data))
            img.load()
        except Exception:
            raise HTTPException(400, "not an image")
        lib.save_cover(sid, img)
        publish({"type": "library"})
        return {"cover_url": f"/api/series/{sid}/cover?v={lib.get_series(sid)['updated']}"}

    @app.post("/api/series/{sid}/cover/from-page")
    def cover_from_page(sid: int, body: dict = Body(...)):
        get_series_or_404(sid)
        f = lib.page_file(int(body["chapter_id"]), int(body["page"]))
        if not f:
            raise HTTPException(404, "page not found")
        lib.save_cover(sid, Image.open(f))
        publish({"type": "library"})
        return {"cover_url": f"/api/series/{sid}/cover?v={lib.get_series(sid)['updated']}"}

    @app.get("/api/chapters/{cid}")
    def chapter_get(cid: int):
        ch = get_chapter_or_404(cid)
        svc = runner.get_service(cid)
        pages = []
        for p in lib.pages(cid):
            pos = runner.svc_pos(cid, p["idx"]) if svc else None
            pages.append({
                "idx": p["idx"], "width": p["width"], "height": p["height"],
                "status": runner.page_status(cid, p["idx"]),
                "rev": svc.page_rev(pos) if svc and pos is not None else 0,
            })
        return {"id": cid, "series_id": ch["series_id"], "number": ch["number"],
                "title": ch["title"], "state": runner.chapter_state(cid),
                "pages": pages}

    @app.patch("/api/chapters/{cid}")
    def chapter_patch(cid: int, body: dict = Body(...)):
        get_chapter_or_404(cid)
        lib.update_chapter(cid, body.get("title"), body.get("number"),
                           body.get("sort"))
        publish({"type": "library"})
        return {"ok": True}

    @app.delete("/api/chapters/{cid}")
    def chapter_delete(cid: int):
        get_chapter_or_404(cid)
        drop_chapter_files(cid)
        lib.delete_chapter(cid)
        publish({"type": "library"})
        return {"ok": True}

    # ---------- upload ----------

    def _extract_archive(tmpname, series_id, series_title,
                         chapter_number, chapter_title, name):
        """Blocking zip/PIL work; runs in a threadpool. Member names are
        never used as filesystem paths."""
        with zipfile.ZipFile(tmpname) as z:
            members = []
            total = 0
            for m in z.infolist():
                if m.is_dir():
                    continue
                base = m.filename.rsplit("/", 1)[-1]
                if m.filename.startswith("__MACOSX") or base.startswith("."):
                    continue
                if norm_ext(base) is None:
                    continue
                if m.file_size > MAX_PAGE_BYTES:
                    raise HTTPException(400, f"{base} is too large")
                total += m.file_size
                if total > MAX_ARCHIVE_EXPANDED:
                    raise HTTPException(400, "archive expands over 2 GB")
                members.append(m.filename)
            members.sort(key=natkey)
            if not members:
                raise HTTPException(400, "archive has no images")
            sid = series_id if series_id is not None else lib.create_series(
                series_title or Path(name).stem or "Untitled")
            get_series_or_404(sid)
            row = lib.get_chapter_by_number(sid, chapter_number)
            cid = row["id"] if row else lib.create_chapter(
                sid, chapter_number, chapter_title)
            i = 0
            for m in members:
                data = z.read(m)
                try:
                    img = Image.open(io.BytesIO(data))
                    img.load()
                except Exception:
                    continue
                sha = hashlib.sha256(data).hexdigest()
                lib.upsert_page(cid, i, norm_ext(m), sha)
                lib.store_page(cid, i, data)
                lib.mark_page_present(cid, i, img.width, img.height)
                i += 1
        return sid, cid

    @app.post("/api/uploads")
    def uploads(body: dict = Body(...)):
        sid = body.get("series_id")
        if sid is None:
            sid = lib.create_series(body.get("series_title") or "Untitled")
        else:
            get_series_or_404(sid)
        out = []
        for ch in body.get("chapters", []):
            files = ch.get("files", [])
            if len(files) > 2000:
                raise HTTPException(400, "too many files in chapter")
            for f in files:
                if _ext_field(f.get("ext", "")) is None:
                    raise HTTPException(400, f"bad ext: {f.get('ext')}")
                if int(f.get("size", 0)) > MAX_PAGE_BYTES:
                    raise HTTPException(400, "file too large")
            row = lib.get_chapter_by_number(sid, float(ch["number"]))
            cid = row["id"] if row else lib.create_chapter(
                sid, float(ch["number"]), ch.get("title") or "")
            for f in files:
                lib.upsert_page(cid, int(f["idx"]), _ext_field(f["ext"]),
                                str(f["sha256"]))
            out.append({"chapter_id": cid, "number": ch["number"],
                        "missing": lib.missing(cid)})
        publish({"type": "library"})
        return {"series_id": sid, "chapters": out}

    @app.put("/api/chapters/{cid}/pages/{idx}/file")
    async def page_put(cid: int, idx: int, file: UploadFile = File(...)):
        get_chapter_or_404(cid)
        p = lib.get_page(cid, idx)
        if not p:
            raise HTTPException(404, "page not declared")
        data = await file.read()
        if len(data) > MAX_PAGE_BYTES:
            raise HTTPException(400, "file too large")

        def verify_and_store():
            if hashlib.sha256(data).hexdigest() != p["sha256"]:
                raise HTTPException(400, "sha256 mismatch")
            try:
                img = Image.open(io.BytesIO(data))
                img.load()
            except Exception:
                raise HTTPException(400, "not an image")
            lib.store_page(cid, idx, data)
            lib.mark_page_present(cid, idx, img.width, img.height)

        await run_in_threadpool(verify_and_store)
        publish({"type": "page", "chapter_id": cid, "page": idx,
                 "status": runner.page_status(cid, idx)})
        maybe_autocover(cid)
        return {"ok": True}

    @app.post("/api/uploads/archive")
    async def archive(file: UploadFile = File(...),
                      series_id: int | None = Form(None),
                      series_title: str | None = Form(None),
                      chapter_number: float = Form(...),
                      chapter_title: str = Form("")):
        name = file.filename or ""
        if Path(name).suffix.lower() not in (".cbz", ".zip"):
            raise HTTPException(400, "expected .cbz or .zip")
        tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".zip")
        size = 0
        try:
            while chunk := await file.read(1 << 20):
                size += len(chunk)
                if size > MAX_ARCHIVE_BYTES:
                    raise HTTPException(400, "archive too large")
                tmp.write(chunk)
            tmp.close()
            sid, cid = await run_in_threadpool(
                _extract_archive, tmp.name, series_id, series_title,
                chapter_number, chapter_title, name)
        finally:
            tmp.close()
            os.unlink(tmp.name)
        maybe_autocover(cid)
        publish({"type": "library"})
        return {"series_id": sid, "chapter_id": cid}

    # ---------- pages ----------

    @app.get("/api/chapters/{cid}/pages/{idx}/image")
    def page_image(cid: int, idx: int, view: str = "original",
                   w: int | None = None):
        get_chapter_or_404(cid)
        p = lib.get_page(cid, idx)
        if not p or not p["present"]:
            raise HTTPException(404, "page not found")
        f = lib.page_file(cid, idx)
        if w is not None:
            w = max(64, min(4096, w))
        if view == "original":
            if w is not None:
                with Image.open(f) as img:
                    if w < img.width:
                        return Response(_jpeg_w(img, w), media_type="image/jpeg",
                                        headers=_IMMUTABLE)
            return FileResponse(f, headers=_IMMUTABLE)
        svc = runner.get_service(cid)
        pos = runner.svc_pos(cid, idx)
        if svc is None or pos is None or not svc._is_processed(pos):
            # no-store: this URL must not stay cached once the page is processed
            if w is not None:
                with Image.open(f) as img:
                    if w < img.width:
                        r = Response(_jpeg_w(img, w), media_type="image/jpeg",
                                     headers={"Cache-Control": "no-store"})
                        r.headers["X-Kei-Status"] = "pending"
                        return r
            r = FileResponse(f, headers={"Cache-Control": "no-store"})
            r.headers["X-Kei-Status"] = "pending"
            return r
        img = svc.get_page(pos, view)
        if w is not None and w < img.width:
            return Response(_jpeg_w(img, w), media_type="image/jpeg",
                            headers={"Cache-Control": "no-cache"})
        buf = io.BytesIO()
        img.save(buf, "PNG")
        return Response(buf.getvalue(), media_type="image/png",
                        headers={"Cache-Control": "no-cache"})

    @app.get("/api/chapters/{cid}/pages/{idx}/blocks")
    def page_blocks(cid: int, idx: int):
        p = lib.get_page(cid, idx)
        if not p:
            raise HTTPException(404, "page not found")
        blocks = []
        svc = runner.get_service(cid)
        pos = runner.svc_pos(cid, idx)
        if svc and pos is not None and svc._is_processed(pos):
            blocks = [{"id": b.id, "bbox": list(b.bbox), "source": b.source,
                       "translation": b.translation}
                      for b in svc.get_blocks(pos)]
        return {"width": p["width"], "height": p["height"], "blocks": blocks}

    @app.get("/api/chapters/{cid}/search")
    def chapter_search(cid: int, q: str = ""):
        get_chapter_or_404(cid)
        needle = q.strip().casefold()
        svc = runner.get_service(cid)
        hits = []
        if not needle or svc is None:
            return hits
        for p in lib.pages(cid):
            pos = runner.svc_pos(cid, p["idx"])
            if pos is None or not svc._is_processed(pos):
                continue
            for b in svc.get_blocks(pos):
                if needle in b.source.casefold() or needle in b.translation.casefold():
                    hits.append({"idx": p["idx"], "block_id": b.id,
                                 "source": b.source, "translation": b.translation})
        return hits

    @app.patch("/api/chapters/{cid}/pages/{idx}/blocks/{bid}")
    def block_patch(cid: int, idx: int, bid: str, body: dict = Body(...)):
        svc = runner.get_service(cid)
        pos = runner.svc_pos(cid, idx)
        if svc is None or pos is None or not svc._is_processed(pos):
            raise HTTPException(404, "page not processed")
        runner.call(lambda: svc.update_translation(pos, bid,
                                                   str(body["translation"])))
        publish({"type": "page", "chapter_id": cid, "page": idx,
                 "status": runner.page_status(cid, idx)})
        return {"ok": True}

    @app.post("/api/chapters/{cid}/pages/{idx}/blocks/{bid}/retranslate")
    def block_retranslate(cid: int, idx: int, bid: str):
        get_chapter_or_404(cid)
        svc = runner.get_service(cid)
        pos = runner.svc_pos(cid, idx)
        if svc is None or pos is None or not svc._is_processed(pos):
            raise HTTPException(404, "page not processed")
        block = next((b for b in svc.get_blocks(pos) if b.id == bid), None)
        if block is None or not block.source.strip():
            raise HTTPException(404, "block not found")
        try:
            text = runner.translator.translate({bid: block.source}).get(bid)
            if not text or not str(text).strip():
                raise ValueError("empty translation")
        except Exception as e:
            raise HTTPException(502, str(e))
        runner.call(lambda: svc.update_translation(pos, bid, text))
        publish({"type": "page", "chapter_id": cid, "page": idx,
                 "status": runner.page_status(cid, idx)})
        return {"translation": text}

    @app.post("/api/chapters/{cid}/pages/{idx}/reprocess")
    def page_reprocess(cid: int, idx: int):
        get_chapter_or_404(cid)
        if not lib.get_page(cid, idx):
            raise HTTPException(404, "page not found")
        runner.reprocess_page(cid, idx)
        return {"ok": True}

    @app.post("/api/chapters/{cid}/reprocess")
    def chapter_reprocess(cid: int):
        get_chapter_or_404(cid)
        runner.reprocess_chapter(cid)
        return {"ok": True}

    # ---------- translation ----------

    @app.post("/api/chapters/{cid}/translate")
    def chapter_translate(cid: int, body: dict = Body(...)):
        get_chapter_or_404(cid)
        a = body.get("action")
        if a in ("start", "resume"):
            runner.start(cid)
        elif a == "pause":
            runner.pause(cid)
        else:
            raise HTTPException(400, "action must be start|pause|resume")
        return {"state": runner.chapter_state(cid)}

    @app.get("/api/jobs")
    def jobs_list():
        items = []
        for cid in list(runner.state):
            ch = lib.get_chapter(cid)
            if not ch:
                continue
            ser = lib.get_series(ch["series_id"])
            items.append({
                "chapter_id": cid, "series_id": ch["series_id"],
                "series_title": ser["title"], "chapter_number": ch["number"],
                "chapter_title": ch["title"], **runner.chapter_stats(cid),
                "failed": [{"page": idx, "error": msg}
                           for (c, idx), msg in list(runner.failed.items())
                           if c == cid],
                "current": ({"page": runner._processing[1],
                             "stage": runner.stage}
                            if runner._processing
                            and runner._processing[0] == cid else None),
            })
        return sorted(items, key=lambda item: (
            item["state"] != "running", item["chapter_id"]))

    @app.post("/api/jobs/{cid}/{action}")
    def job_action(cid: int, action: str):
        if action not in ("pause", "resume", "cancel"):
            raise HTTPException(400, "action must be pause|resume|cancel")
        get_chapter_or_404(cid)
        if action == "pause":
            runner.pause(cid)
        elif action == "resume":
            runner.start(cid)
        else:
            runner.cancel(cid)
        return {"state": runner.chapter_state(cid)}

    @app.post("/api/chapters/{cid}/focus")
    def chapter_focus(cid: int, body: dict = Body(...)):
        get_chapter_or_404(cid)
        runner.focus(cid, int(body["page"]))
        return {"ok": True}

    @app.get("/api/status")
    def status():
        fn = getattr(runner.translator, "requests_today", None)
        return {"device": s.resolved_device,
                "requests_today": fn() if fn else 0,
                "has_key": bool(s.openrouter_key),
                "inpainter": s.inpainter, "target_lang": s.target_lang}

    @app.get("/api/usage")
    def usage():
        t = runner.translator
        requests_today = getattr(t, "requests_today", None)
        daily_limit = getattr(t, "free_daily_limit", None)
        return {
            "requests_today": requests_today() if callable(requests_today) else 0,
            "daily_limit": daily_limit() if callable(daily_limit) else None,
            "model": getattr(t, "last_model", None)
            or (s.model_priority[0] if s.model_priority else None),
        }

    @app.get("/api/models")
    def models():
        try:
            return [{"id": i, "name": n}
                    for i, n in runner.translator.list_free_models()]
        except Exception as e:
            raise HTTPException(502, f"OpenRouter unreachable: {e}")

    @app.get("/api/settings")
    def settings_get():
        d = {k: v for k, v in asdict(s).items() if k != "api_key"}
        d["has_key"] = bool(s.openrouter_key)
        return d

    def drop_renders():
        for svc in list(runner._svc.values()):
            with svc._lock:
                svc._lru.clear()

    @app.patch("/api/settings")
    def settings_patch(body: dict = Body(...)):
        known = set(Settings.__dataclass_fields__)
        coerced = {}
        for k, v in body.items():
            if k not in known:
                continue
            cur = getattr(s, k)
            if isinstance(cur, list):
                if not isinstance(v, list):
                    raise HTTPException(422, f"{k} must be a list")
                coerced[k] = v
            elif isinstance(cur, bool):
                if not isinstance(v, bool):
                    raise HTTPException(422, f"{k} must be a bool")
                coerced[k] = v
            else:
                try:
                    coerced[k] = (int(v) if isinstance(cur, int)
                                  else float(v) if isinstance(cur, float)
                                  else str(v))
                except (TypeError, ValueError):
                    raise HTTPException(422, f"bad value for {k}: {v!r}")
        for k in ("masker", "inpainter", "device"):
            choices = {"masker": ("threshold", "model", "combined"),
                       "inpainter": ("auto", "opencv", "lama"),
                       "device": ("auto", "cuda", "cpu")}[k]
            if k in coerced and coerced[k] not in choices:
                raise HTTPException(422, f"{k} must be one of {choices}")
        for k in ("mask_dilate", "mask_feather"):
            if k in coerced and not 0 <= coerced[k] <= 20:
                raise HTTPException(422, f"{k} must be 0..20")
        for k in ("mask_thresh", "det_conf"):
            if k in coerced and not 0 <= coerced[k] <= 1:
                raise HTTPException(422, f"{k} must be 0..1")
        for k, v in coerced.items():
            setattr(s, k, v)
        if coerced.keys() & {"font_path", "font_min", "font_max"}:
            drop_renders()
        s.save()
        if hasattr(runner.translator, "_free"):
            runner.translator._free = None
        if "api_key" in coerced and hasattr(runner.translator, "_daily_limit_at"):
            runner.translator._daily_limit_at = None
        return settings_get()

    @app.post("/api/settings/font")
    async def font_upload(file: UploadFile = File(...)):
        ext = Path(file.filename or "").suffix.lower()
        if ext not in (".ttf", ".otf", ".ttc"):
            raise HTTPException(400, "expected .ttf, .otf or .ttc")
        data = await file.read()
        if len(data) > MAX_PAGE_BYTES:
            raise HTTPException(400, "file too large")
        try:
            ImageFont.truetype(io.BytesIO(data), 12)
        except Exception:
            raise HTTPException(400, "not a valid font")
        d = s.library_path / "fonts"
        d.mkdir(parents=True, exist_ok=True)
        f = d / f"{hashlib.sha256(data).hexdigest()[:16]}{ext}"
        f.write_bytes(data)
        s.font_path = str(f)
        drop_renders()
        s.save()
        return settings_get()

    # ---------- progress ----------

    @app.get("/api/progress/{sid}")
    def progress_get(sid: int):
        row = lib.get_progress(sid)
        return dict(row) if row else None

    @app.put("/api/progress/{sid}")
    def progress_put(sid: int, body: dict = Body(...)):
        get_series_or_404(sid)
        row = lib.set_progress(sid, int(body["chapter_id"]), int(body["page"]),
                               float(body["percent"]),
                               str(body.get("device_id", "")),
                               float(body["ts"]))
        publish({"type": "progress", "series_id": sid, **dict(row)})
        return dict(row)

    @app.get("/api/continue")
    def continue_reading():
        return [{"series_id": r["series_id"], "series_title": r["series_title"],
                 "cover_url": (f"/api/series/{r['series_id']}/cover"
                               f"?v={r['s_updated']}" if r["has_cover"]
                               else None),
                 "chapter_id": r["chapter_id"],
                 "chapter_title": r["chapter_title"],
                 "chapter_number": r["chapter_number"],
                 "page": r["page"], "percent": r["percent"], "ts": r["ts"]}
                for r in lib.continue_list()]

    # ---------- events ----------

    @app.get("/api/events")
    async def sse():
        async def gen():
            q = subscribe()
            try:
                while True:
                    try:
                        ev = await asyncio.wait_for(q.get(), 15)
                        yield f"data: {json.dumps(ev, ensure_ascii=False)}\n\n"
                    except asyncio.TimeoutError:
                        yield ": ping\n\n"
            finally:
                unsubscribe(q)
        return StreamingResponse(gen(), media_type="text/event-stream")

    return app


app = create_app(mdns=True)
