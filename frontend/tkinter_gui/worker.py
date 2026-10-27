import logging
import queue
import threading

log = logging.getLogger(__name__)


class Worker(threading.Thread):
    """Owns every slow ReaderService call. GUI posts commands on `q`,
    results come back on `out`."""

    def __init__(self, svc, q, out):
        super().__init__(daemon=True)
        self.svc = svc
        self.q = q
        self.out = out
        self.auto = False
        self.armed = True   # no-spin guard: re-armed by a newly processed page
        self.current = 0

    def run(self):
        while True:
            cmd = self.q.get()
            log.debug("worker cmd %s", cmd)
            try:
                self._do(cmd)
            except Exception as e:
                log.exception("worker command failed: %s", cmd)
                self.out.put(("error", f"{cmd[0]}: {e}"))
            if cmd[0] == "stop":
                return

    def _do(self, cmd):
        c = cmd[0]
        if c == "open":
            self.out.put(("opened", self.svc.open_chapter(cmd[1])))
        elif c == "goto":
            i = cmd[1]
            later = []
            while True:  # drop stale gotos, keep other commands
                try:
                    nxt = self.q.get_nowait()
                except queue.Empty:
                    break
                if nxt[0] == "goto":
                    log.debug("dropped stale goto %d", i)
                    i = nxt[1]
                else:
                    later.append(nxt)
            for nxt in later:
                self.q.put(nxt)
            self.current = i
            self._process(i)
            for j in range(i + 1, min(i + 4, self.svc.page_count)):
                if not self.q.empty():  # newer work arrived; abandon prefetch
                    break
                self._process(j)
            self._maybe_translate(i)  # one batched request for the page + prefetched pages
        elif c == "translate":
            self.armed = True
            self._maybe_translate(self.current)
        elif c == "auto":
            self.auto = cmd[1]
            if self.auto:
                self.armed = True
                self._maybe_translate(self.current)
        elif c == "add_block":
            self.svc.add_block(cmd[1], cmd[2])
            self.out.put(("page_ready", cmd[1]))
        elif c == "delete_block":
            self.svc.delete_block(cmd[1], cmd[2])
            self.out.put(("page_ready", cmd[1]))
        elif c == "reset":
            self.svc.reset_page(cmd[1])
        elif c == "clear_cache":
            self.svc.clear_cache()
            self.armed = True  # cleared pages need translating again
            self.out.put(("cleared",))
        elif c == "models":
            self.out.put(("models", self.svc.list_free_models()))
        elif c == "export":
            self.svc.export(cmd[1], cmd[2])
            self.out.put(("done", "export"))

    def _process(self, i):
        stages = []
        self.svc.process_page(
            i, on_progress=lambda s: (stages.append(s),
                                      self.out.put(("progress", i, s))))
        self.out.put(("page_ready", i))
        if stages:  # did real work (cache hit fires no callbacks)
            self.armed = True

    def _maybe_translate(self, i):
        if not (self.auto and self.armed):
            return
        if not self.svc.s.openrouter_key:
            self.out.put(("no_key",))
            self.auto = False
            self.armed = False
            return
        self.armed = False
        try:
            updated = self.svc.translate_pending(start=i)
        except Exception as e:
            self.out.put(("translate_error", str(e)))
            return
        self.out.put(("usage", self.svc.requests_today()))
        if updated:
            self.out.put(("translated", updated))
