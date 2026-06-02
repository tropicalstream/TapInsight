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
import io
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

        if clean_path.startswith("/media/"):
            # Serve any file under one of the configured roots: workspace
            # first, then each --media-root in CLI order. The first root
            # that contains the file wins. Filename collisions across
            # roots are won by the earlier-listed root — workspace
            # always wins. Security: reject .. / absolute / drive-letter
            # paths so the URL space stays inside each root.
            filename = unquote(clean_path[len("/media/"):])
            if not filename or ".." in filename or filename.startswith("/"):
                self.send_error(400, "Invalid filename")
                return
            roots = [self.workspace] + list(self.extra_media_roots)
            file_path = None
            matched_root = None
            for root in roots:
                if not root:
                    continue
                candidate = os.path.join(root, filename)
                real_root = os.path.realpath(root)
                real_candidate = os.path.realpath(candidate)
                # Containment check — the resolved path must still be
                # inside the root (defends against symlinks pointing
                # outward, e.g. ~/hermes-media/foo → /etc/shadow).
                if not (real_candidate.startswith(real_root + os.sep) or
                        real_candidate == real_root):
                    continue
                if os.path.isfile(candidate):
                    file_path = candidate
                    matched_root = real_root
                    break
            if not file_path:
                self.send_error(
                    404,
                    f"File not found in any media root: {filename}",
                )
                return
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
                         b"GET /media/<filename> to serve workspace files.\n")

    def log_message(self, format, *args):
        """Quieter logs — skip noisy 200s, keep errors."""
        if args and str(args[1]) not in ("200", "204"):
            super().log_message(format, *args)


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
