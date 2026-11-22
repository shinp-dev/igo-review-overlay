#!/usr/bin/env python3
"""Small LAN-only HTTP adapter for KataGo's JSON-line analysis engine.
Android sends only 81 stone colors, not screenshots or account information.
"""
import argparse
import hmac
import json
import logging
import subprocess
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COLUMNS = "ABCDEFGHJ"
LOG = logging.getLogger("igo-review")


def to_query(data, query_id):
    board = data.get("board")
    if (not isinstance(board, list) or len(board) != 9
            or any(not isinstance(row, str) or len(row) != 9
                   or any(ch not in "BW." for ch in row) for row in board)):
        raise ValueError("board must contain nine rows of nine B/W/. characters")
    to_play = data.get("toPlay")
    if to_play not in ("B", "W"):
        raise ValueError("toPlay must be B or W")
    visits = data.get("maxVisits", 200)
    candidates = data.get("candidates", 3)
    if type(visits) is not int or not 1 <= visits <= 1000:
        raise ValueError("maxVisits must be between 1 and 1000")
    if type(candidates) is not int or not 1 <= candidates <= 5:
        raise ValueError("candidates must be between 1 and 5")
    initial = []
    for row, text in enumerate(board):
        for col, color in enumerate(text):
            if color != ".":
                initial.append([color, COLUMNS[col] + str(9 - row)])
    return {
        "id": query_id,
        "boardXSize": 9,
        "boardYSize": 9,
        "rules": "chinese",
        "komi": 7.0,
        "initialStones": initial,
        "initialPlayer": to_play,
        "moves": [],
        "maxVisits": visits,
        "analysisPVLen": 0,
    }, candidates


def normalize(result, limit, to_play, perspective):
    if "error" in result:
        raise RuntimeError(str(result["error"]))
    root = result.get("rootInfo")
    if not isinstance(root, dict):
        raise RuntimeError("KataGo returned no rootInfo")
    invert = perspective != "SIDETOMOVE" and perspective != (
        "BLACK" if to_play == "B" else "WHITE"
    )

    def projected(item):
        winrate = float(item.get("winrate", 0.5))
        score = float(item.get("scoreLead", 0))
        return (1.0 - winrate if invert else winrate,
                -score if invert else score)

    info = result.get("moveInfos", [])
    if not isinstance(info, list):
        raise RuntimeError("KataGo returned invalid moveInfos")
    sorted_moves = sorted(info, key=lambda move: int(move.get("order", 9999)))
    moves = []
    for item in sorted_moves:
        move = item.get("move", "")
        if move == "pass":
            continue
        if not isinstance(move, str) or not (
            len(move) >= 2 and move[0] in COLUMNS and move[1:].isdigit()
            and 1 <= int(move[1:]) <= 9
        ):
            continue
        winrate, score = projected(item)
        moves.append({
            "move": move,
            "winrate": winrate,
            "scoreLead": score,
            "visits": int(item.get("visits", 0)),
        })
        if len(moves) == limit:
            break
    winrate, score = projected(root)
    return {"rootWinrate": winrate, "rootScoreLead": score, "candidates": moves}


class Engine:
    def __init__(self, katago, config, model, perspective):
        self.perspective = perspective
        self.lock = threading.Lock()
        cmd = [katago, "analysis", "-config", config, "-model", model]
        self.process = subprocess.Popen(
            cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            text=True, encoding="utf-8", bufsize=1,
        )
        LOG.info("Started KataGo PID=%s", self.process.pid)

    def analyze(self, data):
        request_id = uuid.uuid4().hex
        query, limit = to_query(data, request_id)
        with self.lock:
            if self.process.poll() is not None:
                raise RuntimeError("KataGo process has stopped")
            assert self.process.stdin is not None
            assert self.process.stdout is not None
            self.process.stdin.write(json.dumps(query, separators=(",", ":")) + "\n")
            self.process.stdin.flush()
            while True:
                line = self.process.stdout.readline()
                if not line:
                    raise RuntimeError("KataGo stdout closed")
                message = json.loads(line)
                if message.get("id") != request_id:
                    continue
                if message.get("isDuringSearch", False):
                    continue
                return normalize(message, limit, data["toPlay"], self.perspective)

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()


def handler(engine, token):
    class RequestHandler(BaseHTTPRequestHandler):
        def respond(self, status, body):
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_GET(self):
            if self.path == "/health":
                self.respond(200, {"ok": engine.process.poll() is None})
            else:
                self.respond(404, {"error": "not found"})

        def do_POST(self):
            if self.path != "/analyze":
                self.respond(404, {"error": "not found"})
                return
            if token and not hmac.compare_digest(
                self.headers.get("X-Review-Token", ""), token
            ):
                self.respond(401, {"error": "invalid token"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 1 <= length <= 4096:
                    raise ValueError("invalid request size")
                data = json.loads(self.rfile.read(length))
                if not isinstance(data, dict):
                    raise ValueError("request must be an object")
                result = engine.analyze(data)
                self.respond(200, result)
            except (ValueError, json.JSONDecodeError) as error:
                self.respond(400, {"error": str(error)})
            except Exception as error:
                LOG.exception("analysis request failed")
                self.respond(502, {"error": str(error)})

        def log_message(self, fmt, *args):
            LOG.info("%s - %s", self.client_address[0], fmt % args)

    return RequestHandler


def main():
    parser = argparse.ArgumentParser(description="KataGo 9x9 review HTTP bridge")
    parser.add_argument("--katago", required=True, help="Path to KataGo executable")
    parser.add_argument("--config", required=True, help="KataGo analysis config")
    parser.add_argument("--model", required=True, help="KataGo neural network model")
    parser.add_argument("--host", default="127.0.0.1", help="Set 0.0.0.0 for LAN access")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token", default="", help="Required for non-loopback bind")
    parser.add_argument("--perspective", choices=("BLACK", "WHITE", "SIDETOMOVE"),
                        default="BLACK", help="Must match reportAnalysisWinratesAs in config")
    args = parser.parse_args()
    if args.host not in ("127.0.0.1", "::1", "localhost") and not args.token:
        parser.error("--token is required for non-loopback hosting")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    engine = Engine(args.katago, args.config, args.model, args.perspective)
    server = ThreadingHTTPServer((args.host, args.port), handler(engine, args.token))
    LOG.info("Listening on %s:%d (use a trusted LAN only)", args.host, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        engine.close()


if __name__ == "__main__":
    main()
