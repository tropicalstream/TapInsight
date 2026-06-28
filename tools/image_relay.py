#!/usr/bin/env python3
"""
TapClaw Media Relay — Receives camera frames from AR glasses and serves
media files from the OpenClaw workspace.

The OpenClaw gateway strips binary attachments from WebSocket RPC calls.
This lightweight HTTP relay bypasses that limitation:
  1. Glasses POST a JPEG to http://<host-ip>:18790/frame
  2. Relay saves it to ~/.openclaw/workspace/camera_frame.jpg
  3. Glasses send text-only agent call: "analyze camera_frame.jpg in workspace"
  4. OpenClaw agent reads the file and responds with vision analysis

Media serving (audio, video, images):
  - GET /media/<filename>  — serves any file from the workspace directory
    OR from any extra root passed with --media-root. Roots are searched
    in order, workspace first, then each --media-root in CLI order.
  - OpenClaw / Hermes save a file to one of those roots, then tell the
    glasses to open  http://<relay>:<port>/media/<filename>  via
    open_taplink. TapBrowser auto-detects audio/video extensions and
    opens the in-app media player.
  - Use --media-root to expose Hermes's local output dir (e.g.
    ~/hermes-media) alongside the OpenClaw workspace without copying
    files or running a second relay.

Usage:
    python3 image_relay.py                       # default port 18790
    python3 image_relay.py --port 18791          # custom port
    python3 image_relay.py --workspace /path     # custom OpenClaw workspace
    python3 image_relay.py --media-root ~/hermes-media
    python3 image_relay.py --media-root ~/hermes-media --media-root /tmp/extra
    py -3 image_relay.py                         # Windows, default port

Runs on the same host computer as OpenClaw. Bind to 0.0.0.0 so the glasses can
reach it over the local network.
"""

import argparse
import html
import io
import json
import mimetypes
import os
import sys
import time
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import unquote

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

DEFAULT_PORT = 18790
DEFAULT_WORKSPACE = os.path.expanduser("~/.openclaw/workspace")
FRAME_FILENAME = "camera_frame.jpg"
ROTATION_DEGREES = 90  # Rotate clockwise to make landscape frames upright


class RelayHandler(BaseHTTPRequestHandler):
    workspace = DEFAULT_WORKSPACE
    # List of additional read-only roots searched by /media/<filename>
    # AFTER the workspace. Populated from --media-root flags in main().
    extra_media_roots: list[str] = []

    def do_POST(self):
        if self.path not in ("/frame", "/upload"):
            self.send_error(404, "Use POST /frame")
            return

        content_length = int(self.headers.get("Content-Length", 0))
        if content_length == 0:
            self.send_error(400, "Empty body")
            return

        if content_length > 10 * 1024 * 1024:  # 10MB limit
            self.send_error(413, "Image too large (max 10MB)")
            return

        body = self.rfile.read(content_length)

        # Validate it looks like a JPEG
        if not body[:2] == b"\xff\xd8":
            self.send_error(400, "Not a valid JPEG (missing FFD8 header)")
            return

        # Rotate the image 90° clockwise so landscape frames appear upright
        if HAS_PIL:
            try:
                img = Image.open(io.BytesIO(body))
                img = img.rotate(-ROTATION_DEGREES, expand=True)
                buf = io.BytesIO()
                img.save(buf, format="JPEG", quality=85)
                image_bytes = buf.getvalue()
            except Exception as e:
                print(f"[WARN] PIL rotation failed, saving raw: {e}")
                image_bytes = body
        else:
            image_bytes = body

        save_path = os.path.join(self.workspace, FRAME_FILENAME)
        try:
            with open(save_path, "wb") as f:
                f.write(image_bytes)
            size_kb = len(image_bytes) / 1024
            print(f"[{time.strftime('%H:%M:%S')}] Saved {size_kb:.0f}KB frame -> {save_path}")

            # Also save a permanent copy in dated archive folder
            archive_dir = os.path.join(
                self.workspace,
                "saved_photos",
                datetime.now().strftime('%Y-%m-%d')
            )
            os.makedirs(archive_dir, exist_ok=True)
            timestamp = datetime.now().strftime("%H%M%S")
            archive_path = os.path.join(archive_dir, f"frame_{timestamp}.jpg")
            with open(archive_path, "wb") as f:
                f.write(image_bytes)
            print(f"[{time.strftime('%H:%M:%S')}] Archived -> {archive_path}")

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(
                f'{{"ok":true,"path":"{save_path}","archive":"{archive_path}","size":{len(image_bytes)}}}'.encode()
            )
        except Exception as e:
            print(f"[ERROR] Failed to save frame: {e}")
            self.send_error(500, f"Write failed: {e}")

    def do_OPTIONS(self):
        """CORS preflight for cross-origin requests from the companion app."""
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_HEAD(self):
        """HTTP metadata probes for WebView/agent clients.

        Some callers check reachability with HEAD before opening media or the
        log page. BaseHTTPRequestHandler returns 501 by default, which makes a
        healthy relay look broken. Mirror the important GET headers without a
        response body.
        """
        clean_path = self.path.split("?")[0]

        if clean_path == "/status":
            payload = b'{"ok":true}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            return

        if clean_path in ("/", "/media", "/media/", "/media-index.json", "/hermes/log/glasses", "/hermes/log"):
            content_type = "application/json; charset=utf-8" if clean_path == "/media-index.json" else "text/html; charset=utf-8"
            if clean_path == "/":
                content_type = "text/plain"
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            return

        if clean_path == "/latest":
            frame_path = os.path.join(self.workspace, FRAME_FILENAME)
            if not os.path.exists(frame_path):
                self.send_error(404, "No frame available")
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(os.path.getsize(frame_path)))
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            return

        if clean_path.startswith("/media/"):
            filename = unquote(clean_path[len("/media/"):])
            found = self._find_media_file(filename)
            if found is None:
                self.send_error(404, f"File not found in any media root: {filename}")
                return
            file_path, _matched_root = found
            mime_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
            self.send_response(200)
            self.send_header("Content-Type", mime_type)
            self.send_header("Content-Length", str(os.path.getsize(file_path)))
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            return

        self.send_error(404, "Not found")

    def do_GET(self):
        """Health check, status, image serving, and media file serving."""
        # Strip query string for path matching
        clean_path = self.path.split("?")[0]

        if clean_path == "/latest":
            # Serve the latest camera frame as JPEG for the companion app gallery
            frame_path = os.path.join(self.workspace, FRAME_FILENAME)
            if not os.path.exists(frame_path):
                self.send_error(404, "No frame available")
                return
            try:
                with open(frame_path, "rb") as f:
                    data = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(data)
            except Exception as e:
                self.send_error(500, f"Read failed: {e}")
            return

        if clean_path == "/status":
            frame_path = os.path.join(self.workspace, FRAME_FILENAME)
            exists = os.path.exists(frame_path)
            size = os.path.getsize(frame_path) if exists else 0
            mtime = os.path.getmtime(frame_path) if exists else 0
            age = int(time.time() - mtime) if exists else -1

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(
                f'{{"ok":true,"hasFrame":{str(exists).lower()},"sizeBytes":{size},"ageSeconds":{age}}}'.encode()
            )
            return

        if clean_path in ("/hermes/log/glasses", "/hermes/log"):
            self._serve_hermes_glasses_log()
            return

        if clean_path in ("/media", "/media/"):
            self._serve_media_index(html_page=True)
            return

        if clean_path == "/media-index.json":
            self._serve_media_index(html_page=False)
            return

        if clean_path.startswith("/media/"):
            # Serve any file under one of the configured roots: workspace
            # first, then each --media-root in CLI order. The first root
            # that contains the file wins. Filename collisions across
            # roots are won by the earlier-listed root — workspace
            # always wins. Security: reject .. / absolute / drive-letter
            # paths so the URL space stays inside each root.
            filename = unquote(clean_path[len("/media/"):])
            found = self._find_media_file(filename)
            if found is None:
                self.send_error(
                    404,
                    f"File not found in any media root: {filename}",
                )
                return
            file_path, matched_root = found
            try:
                mime_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
                file_size = os.path.getsize(file_path)
                with open(file_path, "rb") as f:
                    data = f.read()
                self.send_response(200)
                self.send_header("Content-Type", mime_type)
                self.send_header("Content-Length", str(file_size))
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(data)
                root_label = (
                    "workspace" if matched_root == os.path.realpath(self.workspace)
                    else matched_root
                )
                print(
                    f"[{time.strftime('%H:%M:%S')}] Served "
                    f"{file_size/1024:.0f}KB {mime_type} "
                    f"-> /media/{filename}  (root={root_label})"
                )
            except Exception as e:
                self.send_error(500, f"Read failed: {e}")
            return

        # Default: help text
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"TapClaw Media Relay is running.\n"
                         b"POST /frame with a JPEG body.\n"
                         b"GET /status for health check.\n"
                         b"GET /hermes/log/glasses for the live Hermes glasses log.\n"
                         b"GET /media or /media-index.json to list exposed media files.\n"
                         b"GET /media/<filename> to serve workspace files.\n")

    def log_message(self, format, *args):
        """Quieter logs — skip noisy 200s, keep errors."""
        if args and str(args[1]) not in ("200", "204"):
            super().log_message(format, *args)

    def _serve_hermes_glasses_log(self):
        """Small read-only live log page for the glasses.

        The Hermes dashboard binds to 127.0.0.1 by default, so the glasses
        cannot open it directly. The relay is already public/tunneled, so this
        exposes a narrow, non-interactive tail of the active `session=glasses`
        log without exposing the full dashboard or any write surface.
        """
        log_path = os.path.expanduser("~/.hermes/logs/agent.log")
        session_path = os.path.expanduser("~/.hermes/sessions/session_glasses.json")
        lines: list[str] = []
        try:
            with open(log_path, "r", encoding="utf-8", errors="replace") as f:
                raw_lines = f.readlines()[-1800:]
            interesting = [
                line.rstrip("\n")
                for line in raw_lines
                if ("[glasses]" in line or "session=glasses" in line)
                and '"GET /v1/models' not in line
            ]
            lines = interesting[-80:]
        except Exception as e:
            lines = [f"Could not read {log_path}: {e}"]

        session_meta = ""
        try:
            if os.path.exists(session_path):
                stat = os.stat(session_path)
                session_meta = (
                    f"session_glasses.json: {stat.st_size} bytes, "
                    f"modified {datetime.fromtimestamp(stat.st_mtime).isoformat(timespec='seconds')}"
                )
        except Exception as e:
            session_meta = f"session_glasses.json metadata unavailable: {e}"

        escaped_lines = [html.escape(line) for line in lines]
        body = "\n".join(
            f'<div class="line">{line}</div>' for line in escaped_lines
        ) or '<div class="empty">No glasses log lines found yet.</div>'
        page = f"""<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta http-equiv="refresh" content="3">
  <title>Hermes Glasses Log</title>
  <style>
    html, body {{
      margin: 0;
      background: #050608;
      color: #f2f6ff;
      font: 13px/1.45 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    }}
    header {{
      position: sticky;
      top: 0;
      padding: 10px 12px 8px;
      background: rgba(5, 6, 8, 0.94);
      border-bottom: 1px solid rgba(255,255,255,0.16);
      z-index: 1;
    }}
    h1 {{
      margin: 0;
      font-size: 14px;
      color: #9ef3ff;
      letter-spacing: 0;
    }}
    .meta {{
      margin-top: 3px;
      color: #aab4c4;
      font-size: 11px;
      white-space: normal;
    }}
    .log {{
      padding: 8px 12px 22px;
    }}
    .line {{
      padding: 3px 0;
      border-bottom: 1px solid rgba(255,255,255,0.055);
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }}
    .line:last-child {{
      border-bottom: 0;
    }}
    .empty {{
      color: #aab4c4;
      padding: 16px 0;
    }}
  </style>
</head>
<body>
  <header>
    <h1>Hermes · glasses tail</h1>
    <div class="meta">Latest 80 glasses lines · refreshes every 3s · {html.escape(session_meta)}</div>
  </header>
  <main class="log">{body}</main>
</body>
</html>"""
        data = page.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)

    def _media_roots(self):
        roots = [self.workspace] + list(self.extra_media_roots)
        seen = set()
        result = []
        for root in roots:
            if not root:
                continue
            real = os.path.realpath(os.path.expanduser(root))
            if real in seen or not os.path.isdir(real):
                continue
            seen.add(real)
            result.append(real)
        return result

    def _find_media_file(self, filename):
        if not filename or ".." in filename or filename.startswith("/"):
            return None
        for root in self._media_roots():
            candidate = os.path.join(root, filename)
            real_root = os.path.realpath(root)
            real_candidate = os.path.realpath(candidate)
            # Containment check — the resolved path must still be inside the
            # root, including when symlinks or hardlinks are involved.
            if not (
                real_candidate.startswith(real_root + os.sep)
                or real_candidate == real_root
            ):
                continue
            if os.path.isfile(candidate):
                return candidate, real_root
        return None

    def _collect_media_files(self):
        files = []
        for root in self._media_roots():
            root_label = (
                "workspace" if root == os.path.realpath(self.workspace)
                else root
            )
            try:
                names = os.listdir(root)
            except Exception:
                continue
            for name in names:
                if name.startswith("."):
                    continue
                path = os.path.join(root, name)
                if not os.path.isfile(path):
                    continue
                mime_type = mimetypes.guess_type(name)[0] or "application/octet-stream"
                if not (
                    mime_type.startswith("image/")
                    or mime_type.startswith("audio/")
                    or mime_type.startswith("video/")
                    or mime_type.startswith("text/")
                ):
                    continue
                try:
                    stat = os.stat(path)
                except Exception:
                    continue
                files.append({
                    "filename": name,
                    "url": f"/media/{name}",
                    "absolute_url": f"https://relay.tapinsight.uk/media/{name}",
                    "mime": mime_type,
                    "size_bytes": stat.st_size,
                    "modified": datetime.fromtimestamp(stat.st_mtime).isoformat(timespec="seconds"),
                    "root": root_label,
                })
        files.sort(key=lambda item: item["modified"], reverse=True)
        return files

    def _serve_media_index(self, html_page: bool):
        files = self._collect_media_files()
        if not html_page:
            payload = {
                "ok": True,
                "relay": "https://relay.tapinsight.uk",
                "roots": self._media_roots(),
                "count": len(files),
                "files": files,
            }
            data = json.dumps(payload, indent=2).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(data)
            return

        rows = "\n".join(
            "<tr>"
            f"<td><a href=\"/media/{html.escape(item['filename'])}\">{html.escape(item['filename'])}</a></td>"
            f"<td>{html.escape(item['mime'])}</td>"
            f"<td>{item['size_bytes'] // 1024} KB</td>"
            f"<td>{html.escape(item['modified'])}</td>"
            f"<td>{html.escape(item['root'])}</td>"
            "</tr>"
            for item in files
        ) or '<tr><td colspan="5">No exposed media files found.</td></tr>'
        page = f"""<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>TapInsight Media Relay</title>
  <style>
    html,body{{margin:0;background:#050608;color:#f2f6ff;font:13px/1.45 system-ui,-apple-system,Segoe UI,sans-serif}}
    body{{padding:14px}}
    h1{{font-size:16px;margin:0 0 8px;color:#9ef3ff}}
    .meta{{color:#aab4c4;font-size:12px;margin-bottom:12px}}
    table{{width:100%;border-collapse:collapse;background:rgba(255,255,255,.035)}}
    th,td{{border-bottom:1px solid rgba(255,255,255,.10);padding:6px 8px;text-align:left;vertical-align:top}}
    th{{color:#7ee8ff;font-size:11px;text-transform:uppercase}}
    a{{color:#fff;text-decoration:none;font-weight:700}}
  </style>
</head>
<body>
  <h1>TapInsight Media Relay</h1>
  <div class="meta">Use <code>https://relay.tapinsight.uk/media/&lt;filename&gt;</code> for glasses playback. JSON: <code>/media-index.json</code>.</div>
  <table>
    <thead><tr><th>File</th><th>Type</th><th>Size</th><th>Modified</th><th>Root</th></tr></thead>
    <tbody>{rows}</tbody>
  </table>
</body>
</html>"""
        data = page.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)


def main():
    parser = argparse.ArgumentParser(description="TapClaw Image Relay")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"Port (default {DEFAULT_PORT})")
    parser.add_argument("--workspace", default=DEFAULT_WORKSPACE, help=f"OpenClaw workspace (default {DEFAULT_WORKSPACE})")
    parser.add_argument(
        "--media-root",
        action="append",
        default=[],
        metavar="DIR",
        help=(
            "Additional directory to expose under /media/<filename>. "
            "Can be passed more than once. Workspace is always searched "
            "first; --media-root entries are searched in CLI order. "
            "Typical use: --media-root ~/hermes-media to expose "
            "Hermes-generated files (Minimax image/t2a/music/video) at "
            "the same URL pattern the glasses already understand, "
            "without copying files or running a separate uploader."
        ),
    )
    args = parser.parse_args()

    # Ensure workspace exists
    if not os.path.isdir(args.workspace):
        print(f"[WARN] Workspace '{args.workspace}' not found — creating it")
        os.makedirs(args.workspace, exist_ok=True)

    # Resolve and validate each extra media root. A missing dir is a
    # warning, not a fatal error — the user may start the relay before
    # they've generated anything.
    resolved_roots = []
    for raw in args.media_root:
        expanded = os.path.expanduser(raw)
        if not os.path.isdir(expanded):
            print(f"[WARN] --media-root '{expanded}' does not exist yet — leaving it empty")
            os.makedirs(expanded, exist_ok=True)
        resolved_roots.append(expanded)

    RelayHandler.workspace = args.workspace
    RelayHandler.extra_media_roots = resolved_roots

    if not HAS_PIL:
        print("[WARN] Pillow not installed — frames will NOT be rotated.")
        print("       Install with: pip3 install Pillow")
    else:
        print(f"Image rotation: {ROTATION_DEGREES}° clockwise")

    # SO_REUSEADDR lets us re-bind the port immediately after a previous
    # instance died (or was killed) instead of waiting for the kernel's
    # TIME_WAIT timeout — that's where the "Address already in use"
    # errors on a quick restart come from. We do this BEFORE
    # server_bind by setting allow_reuse_address on the class.
    HTTPServer.allow_reuse_address = True
    try:
        server = HTTPServer(("0.0.0.0", args.port), RelayHandler)
    except OSError as e:
        # If the port is genuinely held by another process (not just
        # TIME_WAIT), SO_REUSEADDR alone won't help — surface a useful
        # error with the recovery command instead of a stack trace.
        if e.errno in (48, 98):  # 48 = macOS, 98 = Linux EADDRINUSE
            sys.stderr.write(
                f"[ERROR] Port {args.port} is already in use by another process.\n"
                f"        Find it with:    lsof -ti :{args.port}\n"
                f"        Kill it with:    lsof -ti :{args.port} | xargs kill -9\n"
                f"        Or run on a different port: python3 image_relay.py --port {args.port + 1}\n"
            )
            sys.exit(2)
        raise
    print(f"TapClaw Image Relay listening on 0.0.0.0:{args.port}")
    print(f"Workspace: {args.workspace}")
    print(f"Frames will be saved to: {os.path.join(args.workspace, FRAME_FILENAME)}")
    if resolved_roots:
        print("Extra media roots (searched after workspace):")
        for r in resolved_roots:
            print(f"  - {r}")
    print(f"POST http://<host-ip>:{args.port}/frame with JPEG body")
    print(f"GET  http://<host-ip>:{args.port}/media/<filename>  to serve any file under the roots above")
    print()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.shutdown()


if __name__ == "__main__":
    main()
