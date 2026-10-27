"""In-process event bus. Worker threads publish(); the SSE endpoint fans out
to per-subscriber asyncio queues via call_soon_threadsafe."""
import asyncio
import logging
import threading

log = logging.getLogger(__name__)

_lock = threading.Lock()
_subs = []  # [(loop, asyncio.Queue)]


def publish(event: dict):
    with _lock:
        subs = list(_subs)
    for loop, q in subs:
        try:
            loop.call_soon_threadsafe(q.put_nowait, event)
        except RuntimeError:  # loop closed
            unsubscribe(q)


def subscribe():
    loop = asyncio.get_running_loop()
    q = asyncio.Queue()
    with _lock:
        _subs.append((loop, q))
    return q


def unsubscribe(q):
    with _lock:
        _subs[:] = [(l, s) for l, s in _subs if s is not q]
