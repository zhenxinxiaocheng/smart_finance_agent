import asyncio
import importlib.util
import json
import multiprocessing
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from app import provider_calls as calls


@pytest.fixture
def adapter(monkeypatch, tmp_path):
    if hasattr(calls, "close_workers"):
        calls.close_workers()
    if hasattr(calls, "open_workers"):
        calls.open_workers()
    path = tmp_path / "akshare.py"
    path.write_text('''
import os, time
def identity(value=None, delay=0, crash=False):
    time.sleep(delay)
    if crash: os._exit(3)
    return {"pid": os.getpid(), "value": value}
def http_snapshot(url):
    import requests
    response = requests.get(url, timeout=10)
    response.raise_for_status()
    return response.json()
def stock_zh_a_hist(**kwargs):
    time.sleep(1)
    return None
''', encoding="utf-8")
    spec = importlib.util.spec_from_file_location("akshare", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    monkeypatch.setitem(__import__("sys").modules, "akshare", module)
    monkeypatch.syspath_prepend(str(tmp_path))
    monkeypatch.setenv("AKSHARE_WORKER_COUNT", "1")
    calls._failures.clear()
    yield module
    if hasattr(calls, "close_workers"):
        calls.close_workers()
    calls._failures.clear()


def invoke(adapter, name="identity", timeout=5, **kwargs):
    return calls.call_akshare(name, kwargs, timeout_seconds=timeout, ak_module=adapter)


def test_successful_calls_reuse_process_without_reusing_the_previous_result(adapter):
    first = invoke(adapter, value="first")
    second = invoke(adapter, value="second")
    assert first["pid"] == second["pid"]
    assert (first["value"], second["value"]) == ("first", "second")


def test_timeout_replaces_only_the_stuck_worker_and_next_call_succeeds(adapter):
    first = invoke(adapter)
    with pytest.raises(calls.AkshareCallTimeout):
        invoke(adapter, timeout=0.2, delay=5)
    assert first["pid"] not in {process.pid for process in multiprocessing.active_children()}
    next_result = invoke(adapter, value="recovered")
    assert next_result["value"] == "recovered"
    assert next_result["pid"] != first["pid"]


def test_crashed_worker_is_replaced_without_poisoning_future_calls(adapter):
    with pytest.raises(calls.AkshareCallError, match="worker unavailable"):
        invoke(adapter, crash=True)
    assert invoke(adapter, value="after crash")["value"] == "after crash"


def test_timeout_does_not_terminate_another_active_worker(adapter, monkeypatch):
    monkeypatch.setenv("AKSHARE_WORKER_COUNT", "2")
    with ThreadPoolExecutor(max_workers=2) as executor:
        warm = list(executor.map(lambda _: invoke(adapter, delay=0.1), range(2)))
        assert len({result["pid"] for result in warm}) == 2
        healthy = executor.submit(invoke, adapter, value="healthy", delay=0.5)
        with pytest.raises(calls.AkshareCallTimeout):
            invoke(adapter, timeout=0.2, delay=5)
        result = healthy.result(timeout=3)
    assert result["value"] == "healthy"
    assert result["pid"] in {process.pid for process in multiprocessing.active_children()}


def test_concurrent_calls_remain_bounded_and_do_not_exchange_results(adapter, monkeypatch):
    monkeypatch.setenv("AKSHARE_WORKER_COUNT", "2")
    with ThreadPoolExecutor(max_workers=6) as executor:
        results = list(executor.map(lambda value: invoke(adapter, value=value, delay=0.1), range(6)))
    assert [result["value"] for result in results] == list(range(6))
    assert len({result["pid"] for result in results}) <= 2


def test_recycling_preserves_results_and_shutdown_reclaims_children(adapter, monkeypatch):
    monkeypatch.setenv("AKSHARE_WORKER_MAX_TASKS", "2")
    results = [invoke(adapter, value=value) for value in range(3)]
    assert results[0]["pid"] == results[1]["pid"]
    assert results[2]["pid"] != results[1]["pid"]
    assert [result["value"] for result in results] == [0, 1, 2]
    calls.close_workers()
    assert not {result["pid"] for result in results} & {
        process.pid for process in multiprocessing.active_children()}


@pytest.fixture
def http_source():
    entered = threading.Event()
    release = threading.Event()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self):
            if self.path == "/slow":
                time.sleep(0.2)
            if self.path == "/blocked":
                entered.set()
                release.wait(5)
            payload = json.dumps({"port": self.client_address[1],
                                  "cookie": self.headers.get("Cookie")}).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Set-Cookie", "task_cookie=private; Path=/")
            try:
                self.end_headers()
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{server.server_port}", entered, release
    release.set()
    server.shutdown()
    server.server_close()
    thread.join(2)


def test_http_connection_is_reused_but_task_cookies_are_not(adapter, http_source):
    url, _, _ = http_source
    first = invoke(adapter, "http_snapshot", url=url)
    second = invoke(adapter, "http_snapshot", url=url)
    assert first["port"] == second["port"]
    assert first["cookie"] is None
    assert second["cookie"] is None


def test_each_http_task_applies_its_own_request_timeout(adapter, http_source):
    url, _, _ = http_source
    invoke(adapter, "http_snapshot", url=url)
    with pytest.raises(calls.AkshareCallError, match="ReadTimeout"):
        calls.call_akshare("http_snapshot", {"url": url + "/slow"}, timeout_seconds=5,
                          request_timeout_seconds=0.05, ak_module=adapter)
    result = calls.call_akshare("http_snapshot", {"url": url + "/slow"}, timeout_seconds=5,
                               request_timeout_seconds=1, ak_module=adapter)
    assert result["cookie"] is None


def test_capacity_timeout_does_not_cool_down_a_healthy_source(adapter, http_source):
    url, entered, release = http_source
    invoke(adapter, "http_snapshot", url=url)
    with ThreadPoolExecutor(max_workers=1) as executor:
        running = executor.submit(invoke, adapter, "http_snapshot", url=url + "/blocked")
        try:
            assert entered.wait(3)
            with pytest.raises(calls.AkshareCallTimeout):
                calls.call_akshare("http_snapshot", {"url": url}, timeout_seconds=0.1,
                                  ak_module=adapter, failure_threshold=1, cooldown_seconds=60)
            with pytest.raises(calls.AkshareCallTimeout):
                calls.call_akshare("http_snapshot", {"url": url}, timeout_seconds=0.1,
                                  ak_module=adapter, failure_threshold=1, cooldown_seconds=60)
        finally:
            release.set()
        assert running.result(timeout=5)["cookie"] is None
    assert calls.call_akshare("http_snapshot", {"url": url}, timeout_seconds=5,
                             ak_module=adapter, failure_threshold=1, cooldown_seconds=60)["cookie"] is None


def test_shutdown_rejects_fallback_calls_until_explicitly_reopened(adapter, http_source):
    url, entered, release = http_source
    worker_pid = invoke(adapter)["pid"]
    def fetch_with_fallback():
        try:
            return invoke(adapter, "http_snapshot", url=url + "/blocked")
        except calls.AkshareCallError:
            return invoke(adapter, value="fallback")
    with ThreadPoolExecutor(max_workers=1) as executor:
        running = executor.submit(fetch_with_fallback)
        try:
            assert entered.wait(3)
            calls.close_workers()
            with pytest.raises(calls.AkshareCallError, match="shutting down"):
                running.result(timeout=3)
            with pytest.raises(calls.AkshareCallError, match="shutting down"):
                invoke(adapter)
        finally:
            release.set()
    assert worker_pid not in {process.pid for process in multiprocessing.active_children()}
    calls.open_workers()
    assert invoke(adapter, value="reopened")["value"] == "reopened"


def test_quality_history_uses_deadline_and_skips_a_cooling_source(adapter, monkeypatch):
    from app import providers
    limits = {"history_call_timeout_seconds": 0.2, "history_http_timeout_seconds": 0.2,
              "source_failure_threshold": 1, "source_cooldown_seconds": 60}
    monkeypatch.setattr(providers, "_provider_integer", limits.__getitem__)
    invoke(adapter)  # Exclude cold interpreter startup from the adapter timeout.
    def fetch():
        return providers.AkshareProvider().daily_quality_batch(
            "600000", "SSE", "STOCK", date(2026, 1, 1), date(2026, 2, 1),
            "QFQ", datetime.now(timezone.utc))
    with pytest.raises(providers.ProviderUnavailable, match="source timeout"):
        fetch()
    with pytest.raises(providers.ProviderUnavailable, match="cooling down"):
        fetch()


def test_api_shutdown_reclaims_the_actual_adapter_process(adapter):
    from app.main import app
    calls.close_workers()
    async def run():
        async with app.router.lifespan_context(app):
            worker_pid = invoke(adapter)["pid"]
            assert worker_pid in {process.pid for process in multiprocessing.active_children()}
        assert worker_pid not in {process.pid for process in multiprocessing.active_children()}
    asyncio.run(run())
