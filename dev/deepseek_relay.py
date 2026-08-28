#!/usr/bin/env python3
import json
import os
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


HOST = "127.0.0.1"
PORT = int(os.environ.get("DEEPSEEK_RELAY_PORT", "8765"))
API_KEY = os.environ.get("DEEPSEEK_API_KEY", "")
MODEL = os.environ.get("DEEPSEEK_MODEL", "deepseek-v4-flash")


class RelayHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/chat/completions":
            self._send_json(404, {"error": "not_found"})
            return
        authorization = self.headers.get("Authorization") or (
            f"Bearer {API_KEY}" if API_KEY else ""
        )
        if not authorization:
            self._send_json(500, {"error": "DEEPSEEK_API_KEY is not configured"})
            return

        try:
            body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            payload = json.loads(body)
            payload.setdefault("model", MODEL)
            request = urllib.request.Request(
                "https://api.deepseek.com/chat/completions",
                data=json.dumps(payload).encode("utf-8"),
                headers={
                    "Authorization": authorization,
                    "Content-Type": "application/json",
                },
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=120) as response:
                response_body = response.read()
                self._send_raw(response.status, response_body)
        except urllib.error.HTTPError as error:
            self._send_raw(error.code, error.read())
        except (json.JSONDecodeError, ValueError):
            self._send_json(400, {"error": "invalid_json"})
        except Exception:
            self._send_json(502, {"error": "deepseek_upstream_unavailable"})

    def _send_raw(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, status, payload):
        self._send_raw(status, json.dumps(payload).encode("utf-8"))

    def log_message(self, format_string, *args):
        print(format_string % args, flush=True)


if __name__ == "__main__":
    print(f"DeepSeek relay listening on http://{HOST}:{PORT}", flush=True)
    ThreadingHTTPServer((HOST, PORT), RelayHandler).serve_forever()
