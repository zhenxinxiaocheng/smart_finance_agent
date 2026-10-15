import http.server
import os
from pathlib import Path
import subprocess
import threading
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[1]
BASH = "C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else "/bin/bash"

class HealthcheckTests(unittest.TestCase):
    def test_running_application_is_healthy_with_its_real_unauthenticated_response_contract(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                # JwtInterceptor returns 401 for /me. GlobalExceptionHandler
                # wraps a GET to the POST-only /login endpoint in HTTP 200.
                self.send_response(401 if self.path == "/api/auth/me" else 200)
                self.end_headers()
            def log_message(self, *args):
                pass
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            config = yaml.safe_load((ROOT / "runtime/compose.yaml").read_text(encoding="utf-8"))
            command = config["services"]["backend"]["healthcheck"]["test"][1].replace("$$", "$")
            command = command.replace("http://127.0.0.1:8080", f"http://127.0.0.1:{server.server_port}")
            result = subprocess.run([BASH, "-c", command], capture_output=True, text=True,
                                    env={**os.environ, "NO_PROXY": "127.0.0.1", "no_proxy": "127.0.0.1"}, timeout=10)
            self.assertEqual(result.returncode, 0, "Running application must not be reported unhealthy")
        finally:
            server.shutdown()
            server.server_close()

if __name__ == "__main__":
    unittest.main()
