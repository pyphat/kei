"""Job runner + ReaderService pool, mirroring the Tkinter worker:
one processing thread, one Event-kicked translator thread."""
import collections
import logging
import queue
import threading
from collections import OrderedDict

from kei_core.service import ReaderService, chapter_key
from kei_core.translate import OpenRouterTranslator

from .events import publish

log = logging.getLogger(__name__)

FOCUS_AHEAD = 5  # focus(p) prefetches p..p+5


class Runner:
    def __init__(self, lib, settings, translator=None, ocr=None):
        self.lib = lib
        self.s = settings
        self.translator = translator or OpenRouterTranslator(settings)
        self.ocr = ocr
        self._svc = OrderedDict()     # cid -> ReaderService (LRU 8)
        self._tasks = queue.Queue()   # callables, run on the process thread
        self._pending = {}            # cid -> deque of page idx to process
        self._prio = collections.deque()  # (cid, idx) focus pages, go first
        self._queued = set()          # (cid, idx) awaiting processing
        self._processing = None       # (cid, idx) currently in process_page
        self.stage = None
        self.state = {}               # cid -> "running" | "paused"
        self._focused = set()         # cids the translator may serve
        self.focus_page = {}
        self.failed = {}              # (cid, idx) -> error message
        self._kick = threading.Event()
        self._stop = False
        self._nokey_sent = False
        threading.Thread(target=self._loop, daemon=True,
                         name="kei-jobs").start()
        threading.Thread(target=self._tx_loop, daemon=True,
                         name="kei-translate").start()

    # --- service pool ---

    def get_service(self, cid):
        ch = self.lib.get_chapter(cid)
        if not ch:
            return None
        if cid in self._svc:
            self._svc.move_to_end(cid)
            return self._svc[cid]
        pages = self.lib.pages(cid)
        if not pages or any(p["present"] != 1 for p in pages):
            return None  # never open an incomplete upload
        svc = ReaderService(self.s, translator=self.translator, ocr=self.ocr)
        svc.open_chapter(self.lib.chapter_dir(ch["series_id"], cid))
        self._svc[cid] = svc
        while len(self._svc) > 8:
            self._svc.popitem(last=False)
        return svc

    def drop_service(self, cid):
        self._svc.pop(cid, None)

    def svc_pos(self, cid, idx):
        """db idx -> ReaderService page index (sorted-position mapping)."""
        idxs = self.lib.page_indices(cid)
        try:
            return idxs.index(idx)
        except ValueError:
            return None

    # --- control ---

    def start(self, cid):
        """(Re)start processing this chapter; also resumes a paused one."""
        self.state[cid] = "running"
        svc = self.get_service(cid)
        pend = collections.deque()
        for idx in self.lib.page_indices(cid):
            pos = self.svc_pos(cid, idx)
            if not (svc and pos is not None and svc._is_processed(pos)):
                pend.append(idx)
                self._queued.add((cid, idx))
        self._pending[cid] = pend
        publish({"type": "chapter", "chapter_id": cid, "state": "running"})
        self._tasks.put(lambda: None)  # wake the loop
        self._kick.set()

    def pause(self, cid):
        self.state[cid] = "paused"
        for idx in self._pending.get(cid, ()):
            self._queued.discard((cid, idx))
        self._pending[cid] = collections.deque()
        publish({"type": "chapter", "chapter_id": cid, "state": "paused"})

    def cancel(self, cid):
        self.pause(cid)
        self.state.pop(cid, None)
        self._focused.discard(cid)
        publish({"type": "chapter", "chapter_id": cid, "state": "idle"})

    def focus(self, cid, page):
        """Prefetch page..page+5 of this chapter, even if it's idle."""
        self._focused.add(cid)
        self.focus_page[cid] = page
        idxs = self.lib.page_indices(cid)
        if page in idxs:
            ahead = idxs[idxs.index(page):idxs.index(page) + FOCUS_AHEAD + 1]
            svc = self.get_service(cid)
            for i in ahead:
                pos = idxs.index(i)
                if (cid, i) not in self._queued and not (
                        svc and svc._is_processed(pos)):
                    self._prio.append((cid, i))
                    self._queued.add((cid, i))
            self._tasks.put(lambda: None)
            self._kick.set()

    def reprocess_page(self, cid, idx):
        """Reset one page's cache and re-queue it for processing."""
        svc = self.get_service(cid)
        pos = self.svc_pos(cid, idx)
        if svc is not None and pos is not None:
            self.call(lambda: svc.reset_page(pos), timeout=60)
        self.failed.pop((cid, idx), None)
        self._queued.discard((cid, idx))
        self._prio.append((cid, idx))
        self._queued.add((cid, idx))
        self._focused.add(cid)
        self.focus_page[cid] = idx
        self._tasks.put(lambda: None)
        self._kick.set()
        publish({"type": "page", "chapter_id": cid, "page": idx,
                 "status": "queued"})

    def reprocess_chapter(self, cid):
        """Clear the whole chapter cache and re-run everything."""
        svc = self.get_service(cid)
        if svc is not None:
            self.call(svc.clear_cache, timeout=60)
        for k in [k for k in self.failed if k[0] == cid]:
            del self.failed[k]
        self.start(cid)

    def call(self, fn, timeout=120):
        """Run fn on the processing thread; returns its result."""
        done = threading.Event()
        box = {}

        def wrap():
            try:
                box["r"] = fn()
            except Exception as e:
                box["e"] = e
            done.set()

        self._tasks.put(wrap)
        if not done.wait(timeout):
            raise TimeoutError("job timed out")
        if "e" in box:
            raise box["e"]
        return box.get("r")

    def chapter_state(self, cid):
        return self.state.get(cid, "idle")

    def page_status(self, cid, idx):
        p = self.lib.get_page(cid, idx)
        if not p or not p["present"]:
            return "missing"
        if (cid, idx) in self.failed:
            return "failed"
        if self._processing == (cid, idx):
            return "processing"
        if (cid, idx) in self._queued:
            return "queued"
        svc = self.get_service(cid)
        pos = self.svc_pos(cid, idx)
        if svc and pos is not None and svc._is_processed(pos):
            todo = [b for b in svc.get_blocks(pos) if b.source]
            return ("translated" if all(b.translation for b in todo)
                    else "processed")
        return "pending"

    def chapter_stats(self, cid):
        pages = self.lib.pages(cid)
        processed = translated = 0
        for p in pages:
            st = self.page_status(cid, p["idx"])
            processed += st in ("processed", "translated")
            translated += st == "translated"
        return {"page_count": len(pages),
                "present_count": sum(p["present"] for p in pages),
                "processed_count": processed,
                "translated_count": translated,
                "state": self.chapter_state(cid)}

    # --- processing loop ---

    def _next_unit(self):
        if self._prio:
            return self._prio.popleft()
        for cid in list(self._pending):
            if self.state.get(cid) != "running":
                continue
            q = self._pending[cid]
            if q:
                return cid, q.popleft()
            del self._pending[cid]
        return None

    def _loop(self):
        while not self._stop:
            unit = self._next_unit()
            if unit is None:
                try:
                    cmd = self._tasks.get(timeout=0.5)
                    cmd()
                except queue.Empty:
                    pass
                continue
            while True:
                try:
                    self._tasks.get_nowait()()
                except queue.Empty:
                    break
            self._do_page(*unit)

    def _do_page(self, cid, idx):
        self._queued.discard((cid, idx))
        svc = self.get_service(cid)
        pos = self.svc_pos(cid, idx)
        if svc is None or pos is None:
            return
        self._processing = (cid, idx)
        self.failed.pop((cid, idx), None)

        stages = []

        def prog(stage):
            stages.append(stage)
            self.stage = stage
            publish({"type": "page", "chapter_id": cid, "page": idx,
                     "status": "processing", "stage": stage})

        try:
            svc.process_page(pos, on_progress=prog)
            publish({"type": "page", "chapter_id": cid, "page": idx,
                     "status": self.page_status(cid, idx)})
            if stages:  # cache hits fire no callbacks; nothing new to translate
                self._kick.set()
        except Exception as e:
            log.exception("process page %s/%s failed", cid, idx)
            self.failed[(cid, idx)] = str(e)
            publish({"type": "page", "chapter_id": cid, "page": idx,
                     "status": "failed"})
            publish({"type": "error",
                     "message": f"chapter {cid} page {idx}: {e}"})
        finally:
            self._processing = None
            self.stage = None

    # --- translator loop ---

    def _active_chapters(self):
        return [c for c, s in self.state.items() if s == "running"] + \
               [c for c in self._focused if self.state.get(c) != "running"]

    def _tx_loop(self):
        while not self._stop:
            self._kick.wait()
            self._kick.clear()
            if self._stop:
                return
            if not self.s.openrouter_key:
                if not self._nokey_sent:
                    self._nokey_sent = True
                    publish({"type": "error",
                             "message": "No OpenRouter API key set"})
                continue
            self._nokey_sent = False
            for cid in self._active_chapters():
                svc = self.get_service(cid)
                if svc is None:
                    continue
                pos = self.svc_pos(cid, self.focus_page.get(cid, 0)) or 0
                try:
                    while True:
                        updated = svc.translate_pending(start=pos)
                        publish({"type": "usage",
                                 "requests_today": svc.requests_today()})
                        if not updated:
                            break
                        idxs = self.lib.page_indices(cid)
                        for i in updated:
                            if i >= len(idxs):
                                continue  # chapter deleted mid-translate
                            publish({"type": "page", "chapter_id": cid,
                                     "page": idxs[i],
                                     "status": self.page_status(
                                         cid, idxs[i])})
                except Exception as e:
                    log.exception("translate failed")
                    publish({"type": "error", "message": str(e)})
                    break  # wait for the next kick
