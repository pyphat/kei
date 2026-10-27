import json
import logging

import httpx
import pytest

from kei_core.config import Settings
from kei_core.translate import CHAT_URL, MODELS_URL, OpenRouterTranslator


def make_transport(routes):
    def handler(req):
        url = str(req.url)
        for prefix, responder in routes.items():
            if url.startswith(prefix):
                return responder(req)
        return httpx.Response(404)
    return httpx.MockTransport(handler)


MODELS = {"data": [
    {"id": "paid/model", "name": "Paid",
     "pricing": {"prompt": "0.001", "completion": "0.002"}},
    {"id": "free/a", "name": "A",
     "pricing": {"prompt": "0", "completion": "0"}},
    {"id": "vendor/b:free", "name": "B", "pricing": {"prompt": "0.01", "completion": "0.01"}},
    {"id": "openrouter/auto", "name": "Auto",
     "pricing": {"prompt": "0", "completion": "0"}},
    {"id": "openrouter/free", "name": "Free router",
     "pricing": {"prompt": "0", "completion": "0"}},
]}


def make_settings(tmp_path, **kw):
    kw.setdefault("api_key", "k")
    kw.setdefault("cache_dir", str(tmp_path))
    return Settings(**kw)


def chat_ok(text_map):
    return httpx.Response(200, json={
        "choices": [{"message": {"content": json.dumps(text_map)}}]})


def test_chain_429_then_fenced(tmp_path):
    calls = []

    def chat(req):
        model = json.loads(req.content)["model"]
        calls.append(model)
        if model == "free/a":
            return httpx.Response(429, headers={"Retry-After": "0"})
        assert model == "vendor/b:free"
        return httpx.Response(200, json={"choices": [{"message": {
            "content": "```json\n{\"x\": \"hello\"}\n```"}}]})

    client = httpx.Client(transport=make_transport(
        {MODELS_URL: lambda r: httpx.Response(200, json=MODELS), CHAT_URL: chat}))
    s = make_settings(tmp_path, model_priority=["free/a"])
    out = OpenRouterTranslator(s, client).translate({"x": "こんにちは"})
    assert out == {"x": "hello"}
    assert calls == ["free/a", "free/a", "vendor/b:free"]  # retried a once


def test_missing_id_reask(tmp_path):
    seen = []

    def chat(req):
        items = json.loads(req.content)["messages"][1]["content"]
        seen.append(items)
        if "a" in items and "b" in items:
            return chat_ok({"a": "A"})
        return chat_ok({"b": "B"})

    client = httpx.Client(transport=make_transport(
        {MODELS_URL: lambda r: httpx.Response(200, json=MODELS), CHAT_URL: chat}))
    out = OpenRouterTranslator(make_settings(tmp_path), client) \
        .translate({"a": "あ", "b": "い"})
    assert out == {"a": "A", "b": "B"}
    assert len(seen) == 2 and "b" in seen[1] and "a" not in seen[1]


def test_401_raises(tmp_path):
    client = httpx.Client(transport=make_transport(
        {MODELS_URL: lambda r: httpx.Response(200, json=MODELS),
         CHAT_URL: lambda r: httpx.Response(401)}))
    with pytest.raises(RuntimeError, match="401"):
        OpenRouterTranslator(make_settings(tmp_path), client).translate({"x": "あ"})


def test_free_models_filtering(tmp_path):
    client = httpx.Client(transport=make_transport(
        {MODELS_URL: lambda r: httpx.Response(200, json=MODELS)}))
    ids = [m for m, _ in OpenRouterTranslator(make_settings(tmp_path), client)
           .list_free_models()]
    assert ids == ["free/a", "vendor/b:free", "openrouter/auto", "openrouter/free"]


def test_logging_no_key_leak(tmp_path, caplog):
    def chat(req):
        model = json.loads(req.content)["model"]
        if model == "free/a":
            return httpx.Response(429, headers={"Retry-After": "0"})
        return chat_ok({"x": "hello"})

    client = httpx.Client(transport=make_transport(
        {MODELS_URL: lambda r: httpx.Response(200, json=MODELS),
         CHAT_URL: chat}))
    s = make_settings(tmp_path, model_priority=["free/a"],
                      api_key="sekret-key-123")
    with caplog.at_level(logging.DEBUG, logger="kei_core.translate"):
        out = OpenRouterTranslator(s, client).translate({"x": "こんにちは"})
    assert out == {"x": "hello"}
    assert "free/a" in caplog.text and "429" in caplog.text
    assert "vendor/b:free" in caplog.text
    assert "sekret-key-123" not in caplog.text


def test_usage_counter(tmp_path):
    client = httpx.Client(transport=make_transport(
        {MODELS_URL: lambda r: httpx.Response(200, json=MODELS),
         CHAT_URL: lambda r: chat_ok({"x": "y"})}))
    t = OpenRouterTranslator(make_settings(tmp_path), client)
    assert t.requests_today() == 0
    t.translate({"x": "あ"})
    assert t.requests_today() == 2  # models list + chat call
    t.translate({"x": "あ"})
    assert t.requests_today() == 3  # model list cached, only chat counted
