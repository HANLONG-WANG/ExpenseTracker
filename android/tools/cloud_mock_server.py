#!/usr/bin/env python3
"""Local cloud handoff mock. Admin routes are unauthenticated: bind only to trusted local interfaces."""
import argparse
import hashlib
import json
import secrets
import tempfile
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

LIMIT = 100_000_000
TYPES = {kind: {"name": kind} for kind in ("receipt", "statement", "general")}
TRANSITIONS = {
    "uploading": {"queued", "archived"}, "queued": {"analyzing", "needs_info", "archived"},
    "analyzing": {"needs_info", "queued", "archived"}, "needs_info": {"queued", "archived"},
    "review": {"analyzing", "needs_info", "archived"}, "ready": {"analyzing", "needs_info", "archived"},
    "archived": {"queued"},
}


def uid(): return str(uuid.uuid4())
def timestamp(): return datetime.now(timezone.utc).isoformat()
def digest(data): return hashlib.sha256(data).hexdigest()


class Problem(Exception):
    def __init__(self, status, message): self.status, self.message = status, message


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_): pass

    def reply(self, value, status=200):
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self, limit=2_000_000):
        length = self.headers.get("Content-Length")
        if self.headers.get("Transfer-Encoding") or not length or not length.isdigit():
            raise Problem(413, "Fixed Content-Length required")
        size = int(length)
        if not 0 < size < limit: raise Problem(413, "Invalid body size")
        data = self.rfile.read(size)
        if len(data) != size: raise Problem(400, "Truncated body")
        return data

    def json_body(self):
        try:
            value = json.loads(self.body())
            if not isinstance(value, dict): raise ValueError()
            return value
        except (ValueError, UnicodeDecodeError): raise Problem(400, "Invalid JSON object")

    def event(self, task, message):
        task["updatedAt"] = timestamp()
        task["events"].append({"id": uid(), "message": message, "createdAt": task["updatedAt"]})

    def ready(self, task):
        return next((r for r in task["results"] if task["status"] == "ready" and r["state"] == "ready"
                     and not r["stale"] and r["inputRevision"] == task["inputRevision"]), None)

    def save_file(self, task, data, role, name, mime):
        file_id = uid()
        (self.server.directory / file_id).write_bytes(data)
        meta = {"id": file_id, "role": role, "name": name, "mime": mime, "size": len(data), "sha256": digest(data)}
        task["files"].append(meta)
        return meta

    def download(self, task, file_id):
        result = self.ready(task)
        if not result or result["fileId"] != file_id: raise Problem(404, "No current ready result")
        meta = next(f for f in task["files"] if f["id"] == file_id)
        self.send_response(200)
        for key, value in {"Content-Type": "application/octet-stream", "Content-Length": str(meta["size"]),
                           "X-Content-SHA256": meta["sha256"], "Cache-Control": "private, no-store"}.items():
            self.send_header(key, value)
        self.end_headers()
        with (self.server.directory / file_id).open("rb") as source:
            while data := source.read(65536): self.wfile.write(data)

    def route(self):
        parsed = urlsplit(self.path)
        parts = parsed.path.strip("/").split("/")
        query = parse_qs(parsed.query)
        admin = parts[0] == "admin"
        if not admin:
            if (self.headers.get("Authorization") != "Bearer " + self.server.config["deviceToken"] or
                self.headers.get("OAI-Sites-Authorization") != "Bearer " + self.server.config["platformAccessToken"]):
                raise Problem(401, "Invalid credentials")
            if self.command == "POST" and self.headers.get("Origin"):
                if self.headers["Origin"] != "http://" + self.headers.get("Host", ""): raise Problem(403, "Origin rejected")
            if parts[:2] != ["api", "v1"]: raise Problem(404, "Not found")
            parts = parts[2:]
        else: parts = parts[1:]
        if parts == ["types"] and self.command == "GET": return self.reply(TYPES)
        if parts == ["tasks"]:
            if self.command == "GET": return self.reply({"tasks": list(self.server.tasks.values())[-500:]})
            if self.command == "POST" and not admin:
                value = self.json_body()
                key = self.headers.get("Idempotency-Key", "")
                if not key.strip() or len(key) > 150: raise Problem(400, "Invalid Idempotency-Key")
                title, instructions = value.get("title"), value.get("instructions", "")
                if (value.get("type") not in TYPES or not isinstance(title, str) or not title.strip() or len(title) > 150 or
                    not isinstance(instructions, str) or len(instructions) > 20000 or not isinstance(value.get("context", {}), dict)):
                    raise Problem(400, "Invalid create parameters")
                payload = {"type": value["type"], "title": title.strip(), "instructions": instructions, "context": value.get("context", {})}
                exact = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
                if len(exact) >= 200_000: raise Problem(413, "Context too large")
                if key in self.server.keys:
                    prior, task_id = self.server.keys[key]
                    if exact != prior: raise Problem(409, "Idempotency parameters changed")
                    return self.reply(self.server.tasks[task_id], 201)
                task = {**payload, "id": uid(), "status": "uploading", "inputRevision": 0,
                        "files": [], "results": [], "events": [], "createdAt": timestamp()}
                self.event(task, "Task created")
                self.server.tasks[task["id"]] = task
                self.server.keys[key] = exact, task["id"]
                return self.reply(task, 201)
        if len(parts) == 2 and parts[0] == "files" and self.command == "GET" and not admin:
            for task in self.server.tasks.values():
                if any(f["id"] == parts[1] for f in task["files"]): return self.download(task, parts[1])
            raise Problem(404, "File not found")
        if len(parts) < 2 or parts[0] != "tasks" or parts[1] not in self.server.tasks: raise Problem(404, "Task not found")
        task = self.server.tasks[parts[1]]
        action = parts[2] if len(parts) == 3 else None
        if len(parts) == 2 and self.command == "GET": return self.reply(task)
        if action == "result" and self.command == "GET" and not admin:
            result = self.ready(task)
            if not result: raise Problem(404, "No ready result")
            return self.download(task, result["fileId"])
        if self.command != "POST": raise Problem(404, "Not found")
        if admin:
            if action == "status":
                status = self.json_body().get("status")
                if status not in TRANSITIONS[task["status"]]: raise Problem(409, "Invalid transition")
                task["status"] = status
                self.event(task, "Status: " + status)
                return self.reply(task)
            if action == "results":
                data = self.body(LIMIT)
                meta = self.save_file(task, data, "result", "result.txt", "application/octet-stream")
                for result in task["results"]: result["state"] = "draft"
                result = {"id": uid(), "version": len(task["results"]) + 1, "inputRevision": task["inputRevision"],
                          "fileId": meta["id"], "state": "ready", "stale": False, "summary": query.get("summary", [""])[0],
                          "validation": {"errors": [], "warnings": query.get("warning", [])}}
                task["results"].insert(0, result)
                task["status"] = "ready"
                self.event(task, "Result ready")
                return self.reply(task, 201)
        elif action == "files":
            if task["status"] not in ("uploading", "needs_info"): raise Problem(409, "Not accepting inputs")
            name = query.get("name", [""])[0]
            if not 1 <= len(name) <= 180 or any(c in "/\\" or ord(c) < 32 or 127 <= ord(c) <= 159 for c in name):
                raise Problem(400, "Invalid file name")
            data = self.body(LIMIT)
            sha = digest(data)
            if self.headers.get("X-Content-SHA256") != sha: raise Problem(400, "SHA mismatch")
            for meta in task["files"]:
                if meta["role"] == "input" and meta["name"] == name and meta["sha256"] == sha:
                    return self.reply({"fileId": meta["id"], "duplicate": True}, 201)
            if sum(f["role"] == "input" for f in task["files"]) >= 200: raise Problem(400, "Too many inputs")
            meta = self.save_file(task, data, "input", name, self.headers.get("Content-Type", "application/octet-stream"))
            task["inputRevision"] += 1
            for result in task["results"]: result["stale"] = result["inputRevision"] != task["inputRevision"]
            self.event(task, "Input uploaded")
            return self.reply({"fileId": meta["id"], "size": meta["size"], "sha256": sha}, 201)
        elif action == "submit":
            if task["status"] not in ("uploading", "needs_info"): raise Problem(409, "Already submitted")
            if not any(f["role"] == "input" for f in task["files"]): raise Problem(400, "No inputs")
            task["status"] = "queued"
            self.event(task, "Submitted")
            return self.reply(task)
        elif action == "ack":
            value = self.json_body()
            result = self.ready(task)
            if value.get("status") not in ("imported", "failed"): raise Problem(400, "Invalid ack")
            if not result or value.get("resultId") != result["id"]: raise Problem(409, "Result changed")
            self.event(task, "Ack: " + value["status"])
            return self.reply({"ok": True})
        raise Problem(403 if not admin else 404, "Endpoint unavailable to device")

    def do_GET(self):
        try: self.route()
        except Problem as exc: self.reply({"error": exc.message}, exc.status)
        except (BrokenPipeError, ConnectionResetError): pass
    do_POST = do_GET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="Use 0.0.0.0 only on a trusted development network")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--config", type=Path, help="Connection JSON output (default: temporary directory/config.json)")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="yohaku-cloud-") as directory:
        server = HTTPServer((args.host, args.port), Handler)
        server.directory = Path(directory)
        server.tasks, server.keys = {}, {}
        server.config = {"id": uid(), "baseUrl": f"http://10.0.2.2:{server.server_port}", "deviceToken": secrets.token_urlsafe(24),
                         "platformAccessToken": secrets.token_urlsafe(24), "platformHeader": "OAI-Sites-Authorization", "apiVersion": 1}
        config_file = args.config or server.directory / "config.json"
        config_file.write_text(json.dumps(server.config, ensure_ascii=False, indent=2) + "\n")
        config_file.chmod(0o600)
        print(config_file.read_text(), flush=True)
        print(f"Config: {config_file}\nAdmin: http://{args.host}:{server.server_port}/admin/tasks", flush=True)
        try: server.serve_forever()
        except KeyboardInterrupt: pass
        finally: server.server_close()


if __name__ == "__main__": main()
