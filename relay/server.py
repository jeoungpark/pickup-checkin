#!/usr/bin/env python3
"""
픽업 체크인 중계 서버 (데모용, 표준 라이브러리만 사용)

  차량 앱  ── POST /api/plan, /api/events ──▶  서버  ◀── GET /api/state (1초 폴링) ── 탑승자 웹 /r/<rider_id>
  차량 앱  ◀── GET /api/inbox (2초 폴링) ──────  서버  ◀── POST /api/late ─────────── 탑승자 웹 "늦어요" 버튼

실행:  python3 relay/server.py            (기본 0.0.0.0:8787)
보기:  http://localhost:8787/            (탑승자 링크 목록)
에뮬레이터 앱은 adb reverse tcp:8787 tcp:8787 후 http://127.0.0.1:8787 로 접속한다.
"""
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
WEB = Path(__file__).parent / "web"

lock = threading.Lock()
state = {"plan": None, "events": [], "inbox": []}
seq = {"event": 0, "inbox": 0}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # 조용히
        pass

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)

    def _json(self):
        n = int(self.headers.get("Content-Length", 0) or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if u.path == "/" or u.path.startswith("/r/"):
            return self._send(200, (WEB / ("index.html" if u.path == "/" else "rider.html")).read_bytes(), "text/html; charset=utf-8")
        if u.path == "/api/state":
            rider = q.get("rider", [""])[0]
            after = int(q.get("after", ["0"])[0])
            with lock:
                ev = [e for e in state["events"] if e["seq"] > after and (rider == "" or e["to"] in (rider, "*"))]
                return self._send(200, {"plan": state["plan"], "events": ev})
        if u.path == "/api/inbox":
            after = int(q.get("after", ["0"])[0])
            with lock:
                return self._send(200, [m for m in state["inbox"] if m["seq"] > after])
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        u = urlparse(self.path)
        body = self._json()
        with lock:
            if u.path == "/api/plan":
                state["plan"] = body
                state["events"].clear()
                state["inbox"].clear()
                print(f"[plan] {body.get('title')}")
                return self._send(200, {"ok": True})
            if u.path == "/api/events":
                seq["event"] += 1
                e = {"seq": seq["event"], "to": body.get("to", "*"), "kind": body.get("kind", ""),
                     "text": body.get("text", ""), "ts": body.get("ts") or int(time.time() * 1000)}
                state["events"].append(e)
                print(f"[event→{e['to']}] {e['text']}")
                return self._send(200, {"ok": True, "seq": e["seq"]})
            if u.path == "/api/reply":
                seq["inbox"] += 1
                m = {"seq": seq["inbox"], "type": "reply", "rider": body.get("rider"), "code": body.get("code", "ok")}
                state["inbox"].append(m)
                print(f"[reply] {m['rider']} {m['code']}")
                return self._send(200, {"ok": True})
            if u.path == "/api/late":
                seq["inbox"] += 1
                m = {"seq": seq["inbox"], "type": "late", "rider": body.get("rider"), "minutes": int(body.get("minutes", 5))}
                state["inbox"].append(m)
                print(f"[late] {m['rider']} {m['minutes']}분")
                return self._send(200, {"ok": True})
        return self._send(404, {"error": "not found"})


if __name__ == "__main__":
    print(f"픽업 체크인 중계 서버: http://localhost:{PORT}/  (에뮬레이터: adb reverse tcp:{PORT} tcp:{PORT})")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
