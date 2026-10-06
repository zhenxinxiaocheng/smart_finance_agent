"""Bounded calls to existing AKShare adapters; this module owns no ingestion state."""

from __future__ import annotations

import atexit
import inspect
import logging
import math
import multiprocessing
import os
import threading
import time
from types import ModuleType
from typing import Any


class AkshareCallError(RuntimeError):
    pass


class AkshareCallTimeout(AkshareCallError):
    pass


_failure_lock = threading.Lock()
_failures = {}
_pool_lock = threading.Lock()
_pool = None
_accepting_calls = True
_logger = logging.getLogger(__name__)


def _akshare_worker(connection):
    session = None
    try:
        import requests

        original_request = requests.sessions.Session.request

        def bounded_request(session, method, url, **options):
            timeout = options.get("timeout")
            if isinstance(timeout, tuple):
                options["timeout"] = tuple(min(float(value), request_timeout)
                                            if value is not None else request_timeout for value in timeout)
            else:
                options["timeout"] = min(float(timeout), request_timeout) if timeout else request_timeout
            return original_request(session, method, url, **options)

        # Child-local patch: concurrent requests in the API process keep their own policies.
        requests.sessions.Session.request = bounded_request
        session = requests.Session()

        def pooled_request(method, url, **options):
            return session.request(method, url, **options)

        requests.api.request = pooled_request
        requests.request = pooled_request
        import akshare
        while True:
            task = connection.recv()
            if task is None:
                break
            function_name, kwargs, request_timeout = task
            session.cookies.clear()
            try:
                connection.send((True, getattr(akshare, function_name)(**kwargs)))
            except Exception as exc:
                connection.send((False, f"{type(exc).__name__}: {exc}"))
            finally:
                session.cookies.clear()
    except (EOFError, OSError):
        pass
    finally:
        if session is not None:
            session.close()
        connection.close()


def _configured_limit(name, default, maximum):
    value = int(os.environ.get(name, default))
    if not 1 <= value <= maximum:
        raise ValueError(f"{name} must be between 1 and {maximum}")
    return value


class _Worker:
    def __init__(self, context):
        self.connection, child = context.Pipe(duplex=True)
        self.process = context.Process(target=_akshare_worker, args=(child,), daemon=True)
        self.busy = True
        self.tasks = 0
        self._close_lock = threading.Lock()
        self._closed = False
        try:
            self.process.start()
        except BaseException:
            self.connection.close()
            self.process.close()
            raise
        finally:
            child.close()

    def alive(self):
        return not self._closed and self.process.is_alive()

    def close(self):
        with self._close_lock:
            if self._closed:
                return
            self._closed = True
            self.connection.close()
            if self.process.is_alive():
                self.process.terminate()
            self.process.join(timeout=1)
            if self.process.is_alive():
                self.process.kill()
                self.process.join(timeout=1)
            self.process.close()


class _WorkerPool:
    def __init__(self):
        self.limit = _configured_limit("AKSHARE_WORKER_COUNT", 4, 16)
        self.max_tasks = _configured_limit("AKSHARE_WORKER_MAX_TASKS", 100, 10000)
        self.context = multiprocessing.get_context("spawn")
        self.condition = threading.Condition()
        self.workers = []
        self.closed = False

    def acquire(self, deadline, function_name):
        with self.condition:
            while True:
                if self.closed:
                    raise AkshareCallError("AKShare workers are shutting down")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise AkshareCallTimeout(f"{function_name}: worker capacity wait timed out")
                for worker in tuple(self.workers):
                    if not worker.busy:
                        if worker.alive():
                            worker.busy = True
                            return worker
                        self.workers.remove(worker)
                        worker.close()
                if len(self.workers) < self.limit:
                    worker = _Worker(self.context)
                    self.workers.append(worker)
                    return worker
                self.condition.wait(remaining)

    def release(self, worker, reusable):
        with self.condition:
            worker.tasks += 1
            retire = self.closed or not reusable or worker.tasks >= self.max_tasks
            if retire:
                # Keep the slot reserved until its actual process has exited.
                worker.close()
                if worker in self.workers:
                    self.workers.remove(worker)
            else:
                worker.busy = False
            self.condition.notify_all()

    def close(self):
        with self.condition:
            self.closed = True
            for worker in self.workers:
                worker.close()
            self.workers.clear()
            self.condition.notify_all()


def _get_pool():
    global _pool
    with _pool_lock:
        if not _accepting_calls:
            raise AkshareCallError("AKShare workers are shutting down")
        if _pool is None:
            _pool = _WorkerPool()
        return _pool


def open_workers():
    global _accepting_calls
    with _pool_lock:
        _accepting_calls = True


def close_workers():
    global _pool, _accepting_calls
    with _pool_lock:
        _accepting_calls = False
        pool, _pool = _pool, None
        if pool is not None:
            pool.close()


atexit.register(close_workers)


def call_akshare(function_name: str, kwargs: dict[str, Any] | None = None,
                 *, timeout_seconds: float, request_timeout_seconds: float | None = None,
                 ak_module=None, failure_threshold: int = 0, cooldown_seconds: float = 0):
    """Bound real adapters in reusable, isolated processes, including multi-page calls.

    Injected offline adapters execute inline. A real ``akshare`` module always uses
    the bounded path. Capacity waiting counts toward the deadline but not source
    failures. HTTP patches stay inside child processes; stuck workers are replaced.
    """
    timeout = float(timeout_seconds)
    request_timeout = timeout if request_timeout_seconds is None else min(float(request_timeout_seconds), timeout)
    if not math.isfinite(timeout) or not math.isfinite(request_timeout) or min(timeout, request_timeout) <= 0:
        raise ValueError("AKShare timeouts must be finite positive numbers")
    arguments = dict(kwargs or {})
    if ak_module is None:
        try:
            import akshare as ak_module
        except ImportError as exc:
            raise AkshareCallError("AKShare is not installed") from exc
    function = getattr(ak_module, function_name)
    parameters = inspect.signature(function).parameters
    if "timeout" in parameters:
        arguments["timeout"] = min(float(arguments["timeout"]), request_timeout) if arguments.get("timeout") else request_timeout
    if not isinstance(ak_module, ModuleType) or ak_module.__name__ != "akshare":
        return function(**arguments)
    if failure_threshold > 0:
        with _failure_lock:
            if _failures.get(function_name, (0, 0))[1] > time.monotonic():
                raise AkshareCallError(f"{function_name}: source temporarily cooling down")
    started = time.monotonic()
    deadline = started + timeout
    pool = _get_pool()
    # Local capacity exhaustion says nothing about the health of the upstream.
    worker = pool.acquire(deadline, function_name)
    acquired = time.monotonic()
    reusable = False
    try:
        worker.connection.send((function_name, arguments, request_timeout))
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not worker.connection.poll(remaining):
            raise AkshareCallTimeout(f"{function_name}: source timeout after {timeout:g}s")
        success, result = worker.connection.recv()
        reusable = True
        if not success:
            raise AkshareCallError(f"{function_name}: {result}")
        with _failure_lock:
            _failures.pop(function_name, None)
        return result
    except (EOFError, OSError) as exc:
        _record_failure(function_name, failure_threshold, cooldown_seconds)
        raise AkshareCallError(f"{function_name}: worker unavailable ({type(exc).__name__})") from exc
    except AkshareCallError:
        _record_failure(function_name, failure_threshold, cooldown_seconds)
        raise
    finally:
        pool.release(worker, reusable)
        _logger.debug("AKShare function=%s capacity_wait_ms=%.0f adapter_ms=%.0f total_ms=%.0f",
                      function_name, (acquired - started) * 1000,
                      (time.monotonic() - acquired) * 1000, (time.monotonic() - started) * 1000)


def _record_failure(name, threshold, cooldown):
    if threshold <= 0:
        return
    with _failure_lock:
        count = _failures.get(name, (0, 0))[0] + 1
        _failures[name] = (count, time.monotonic() + cooldown if count >= threshold else 0)
