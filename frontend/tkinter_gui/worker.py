import logging
import queue
import threading

log = logging.getLogger(__name__)


class TranslatorThread(threading.Thread):
    """Drains translate_pending on its own thread so slow HTTP calls never
    stall page processing. Kicked via an Event by the processing thread."""

    def __init__(self, svc, out):
        super().__init__(daemon=True)
        self.svc = svc
        self.out = out
        self.auto = False
        self.armed = True
        self.current = 0
        self._kick = threading.Event()
        self._stop = False

    def kick(self):
        self.armed = True
        self._kick.set()

    def stop(self):
        self._stop = True
        self._kick.set()

    def run(self):
        while True:
            self._kick.wait()
            self._kick.clear()
            if self._stop:
                return
            if not (self.auto and self.armed):
                continue
            if not self.svc.s.openrouter_key:
                self.out.put(("no_key",))
                self.auto = False
                self.armed = False
                continue
            self.armed = False  # disarm until the next kick
            try:
                while True:
                    updated = self.svc.translate_pending(start=self.current)
                    self.out.put(("usage", self.svc.requests_today()))
                    if not updated:
                        break
                    self.out.put(("translated", updated))
            except Exception as e:
                log.exception("translate failed")
                self.out.put(("translate_error", str(e)))


class Worker(threading.Thread):
    """Owns slow ReaderService calls except translation, which runs on
    TranslatorThread. GUI posts commands on `q`, results come back on `out`."""

    def __init__(self, svc, q, out):
        super().__init__(daemon=True)
        self.svc = svc
        self.q = q
        self.out = out
        self.current = 0
        self.translator = TranslatorThread(svc, out)
        self.translator.start()

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
                self.translator.stop()
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
            self.translator.current = i
            self._process(i)
            ahead = max(3, self.svc.s.batch_pages)
            for j in range(i + 1, min(i + 1 + ahead, self.svc.page_count)):
                if not self.q.empty():  # newer work arrived; abandon prefetch
                    break
                self._process(j)
        elif c == "translate":
            self.translator.kick()
        elif c == "auto":
            self.translator.auto = cmd[1]
            if self.translator.auto:
                self.translator.kick()
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
            self.translator.kick()
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
            self.translator.kick()
