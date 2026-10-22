#!/usr/bin/env python3
"""Live TV vitals in a browser. Samples the connected ADB device every half second.

Usage, from the repo root, with the TV already connected:

    python dashboard/server.py

Uses TV_HOST when it is set (host or host:5555). Otherwise the one device
`adb devices` lists. Nothing is installed on the TV and nothing is written there.

The page is only served on this computer (127.0.0.1).
"""

import argparse
import json
import os
import queue
import re
import subprocess
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
INTERVAL_S = 0.5
# One sample is a few short /proc reads plus the focused window. On this TV that
# returns in about 0.4s, which is what makes a 0.5s cadence possible. dumpsys meminfo
# takes several seconds, so RAM here comes from /proc/meminfo instead.
SNAPSHOT = r"""echo @@STAT
head -n 1 /proc/stat
echo @@MEM
cat /proc/meminfo
echo @@LOAD
cat /proc/loadavg
echo @@UP
cat /proc/uptime
echo @@DF
df /data
echo @@PS
ps -A -o RSS,NAME | sort -nr | head -n 16
echo @@FOCUS
dumpsys window | grep mCurrentFocus
echo @@END
"""
PROBE = (
    "getprop ro.product.model; getprop ro.product.device; "
    "getprop ro.build.version.release; getprop ro.build.display.id"
)
FOCUS_RE = re.compile(r"mCurrentFocus=Window\{[^\s]+\s+\S+\s+(\S+)\}")


class Hub:
    def __init__(self):
        self.lock = threading.Lock()
        self.latest = None
        self.clients = []

    def publish(self, snap):
        payload = "data: " + json.dumps(snap) + "\n\n"
        with self.lock:
            self.latest = snap
            clients = list(self.clients)
        for client in clients:
            try:
                client.put_nowait(payload)
            except queue.Full:
                try:
                    client.get_nowait()
                except queue.Empty:
                    pass
                try:
                    client.put_nowait(payload)
                except queue.Full:
                    pass

    def subscribe(self):
        client = queue.Queue(maxsize=4)
        with self.lock:
            self.clients.append(client)
            latest = self.latest
        if latest is not None:
            client.put("data: " + json.dumps(latest) + "\n\n")
        return client

    def unsubscribe(self, client):
        with self.lock:
            if client in self.clients:
                self.clients.remove(client)


HUB = Hub()


def adb_serial():
    host = os.environ.get("TV_HOST", "").strip()
    if host:
        return host if ":" in host else host + ":5555"
    out = subprocess.run(
        ["adb", "devices"], capture_output=True, text=True, timeout=10, check=False
    )
    serials = []
    for line in out.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    if len(serials) == 1:
        return serials[0]
    if not serials:
        sys.exit("No ADB device is connected. Connect the TV, or set TV_HOST.")
    sys.exit("More than one ADB device is connected. Set TV_HOST to the TV.")


def adb_out(serial, command, timeout):
    done = subprocess.run(
        ["adb", "-s", serial, "exec-out", command],
        capture_output=True,
        timeout=timeout,
        check=False,
    )
    if done.returncode != 0:
        err = done.stderr.decode("utf-8", "replace").strip()
        raise RuntimeError(err or f"adb exited {done.returncode}")
    return done.stdout.decode("utf-8", "replace")


def sections(text):
    found = {}
    name = None
    buf = []
    for line in text.splitlines():
        if line.startswith("@@"):
            if name:
                found[name] = "\n".join(buf).strip()
            name = line[2:].strip()
            buf = []
        else:
            buf.append(line)
    if name:
        found[name] = "\n".join(buf).strip()
    return found


def mem_kb(text):
    values = {}
    for line in text.splitlines():
        key, _, rest = line.partition(":")
        num = rest.strip().split()
        if num and num[0].isdigit():
            values[key] = int(num[0])
    return values


def cpu_percent(line, prev):
    parts = [int(x) for x in line.split()[1:] if x.isdigit()]
    if len(parts) < 4:
        return None, prev
    idle = parts[3] + (parts[4] if len(parts) > 4 else 0)
    total = sum(parts)
    if not prev or total <= prev[0]:
        return None, (total, idle)
    delta = total - prev[0]
    busy = delta - (idle - prev[1])
    return round(100.0 * busy / delta, 1), (total, idle)


def parse_snapshot(text, prev_cpu):
    part = sections(text)
    mem = mem_kb(part.get("MEM", ""))
    total = mem.get("MemTotal", 0)
    available = mem.get("MemAvailable", 0)
    swap_total = mem.get("SwapTotal", 0)
    swap_free = mem.get("SwapFree", 0)
    cpu, prev_cpu = cpu_percent(part.get("STAT", ""), prev_cpu)

    load_parts = part.get("LOAD", "").split()
    load = [float(x) for x in load_parts[:3]] if len(load_parts) >= 3 else []
    running, threads = None, None
    if len(load_parts) >= 4 and "/" in load_parts[3]:
        left, right = load_parts[3].split("/", 1)
        if left.isdigit():
            running = int(left)
        if right.isdigit():
            threads = int(right)

    uptime = None
    up_bits = part.get("UP", "").split()
    if up_bits:
        uptime = float(up_bits[0])

    storage = None
    for line in part.get("DF", "").splitlines():
        cols = line.split()
        if len(cols) >= 6 and cols[-1] == "/data":
            storage = {
                "total_kb": int(cols[-5]),
                "used_kb": int(cols[-4]),
                "free_kb": int(cols[-3]),
                "use_percent": int(cols[-2].rstrip("%")),
            }
            break

    processes = []
    for line in part.get("PS", "").splitlines():
        rss, _, name = line.strip().partition(" ")
        name = name.strip()
        if not rss.isdigit() or not name or name.startswith("["):
            continue
        processes.append({"rss_kb": int(rss), "name": name})

    focus = None
    match = FOCUS_RE.search(part.get("FOCUS", ""))
    if match:
        component = match.group(1).rstrip("}")
        package, _, activity = component.partition("/")
        focus = {"package": package, "activity": activity or component}

    return {
        "ok": True,
        "cpu_percent": cpu,
        "load": load,
        "running": running,
        "threads": threads,
        "uptime_s": uptime,
        "mem": {
            "total_kb": total,
            "available_kb": available,
            "used_kb": max(0, total - available),
            "free_kb": mem.get("MemFree", 0),
            "cache_kb": mem.get("Buffers", 0) + mem.get("Cached", 0),
            "swap_total_kb": swap_total,
            "swap_used_kb": max(0, swap_total - swap_free),
        },
        "storage": storage,
        "focus": focus,
        "processes": processes[:12],
    }, prev_cpu


def probe(serial):
    try:
        lines = [ln.strip() for ln in adb_out(serial, PROBE, 8).splitlines()]
    except (OSError, RuntimeError, subprocess.TimeoutExpired):
        return {}
    while len(lines) < 4:
        lines.append("")
    return {
        "model": lines[0],
        "device": lines[1],
        "android": lines[2],
        "firmware": lines[3],
    }


def sample_loop(serial, interval):
    device = probe(serial)
    prev_cpu = None
    prev_at = None
    announced = False
    next_tick = time.perf_counter()
    while True:
        started = time.perf_counter()
        try:
            text = adb_out(serial, SNAPSHOT, timeout=2.5)
            snap, prev_cpu = parse_snapshot(text, prev_cpu)
            snap["device"] = device
        except (OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
            snap = {"ok": False, "error": str(exc), "device": device}
        now = time.perf_counter()
        snap["sample_ms"] = round((now - started) * 1000)
        snap["interval_ms"] = None if prev_at is None else round((started - prev_at) * 1000)
        snap["ts"] = time.time()
        prev_at = started
        HUB.publish(snap)
        if not announced and snap.get("ok"):
            announced = True
            cpu = snap.get("cpu_percent")
            cpu_text = "waiting for a second sample" if cpu is None else f"CPU {cpu}%"
            print(f"live  {cpu_text}  sample {snap['sample_ms']} ms", flush=True)
        elif not snap.get("ok"):
            print(f"sample failed: {snap.get('error')}", file=sys.stderr, flush=True)
        next_tick += interval
        delay = next_tick - time.perf_counter()
        if delay > 0:
            time.sleep(delay)
        else:
            next_tick = time.perf_counter()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        return

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/", "/index.html"):
            self._send_file(ROOT / "index.html", "text/html; charset=utf-8")
        elif path == "/api/stats":
            self._send_json(HUB.latest or {"ok": False, "error": "no sample yet"})
        elif path == "/events":
            self._events()
        else:
            self.send_error(404)

    def _send_file(self, path, content_type):
        body = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _events(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        client = HUB.subscribe()
        try:
            while True:
                self.wfile.write(client.get().encode("utf-8"))
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            HUB.unsubscribe(client)


def main():
    parser = argparse.ArgumentParser(description="Live TV vitals dashboard")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--interval", type=float, default=INTERVAL_S)
    parser.add_argument("--no-open", action="store_true", help="do not open a browser")
    args = parser.parse_args()
    if args.interval < INTERVAL_S:
        sys.exit(f"--interval must be at least {INTERVAL_S} seconds")

    serial = adb_serial()
    threading.Thread(target=sample_loop, args=(serial, args.interval), daemon=True).start()

    try:
        httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    except OSError as exc:
        sys.exit(f"Could not listen on {args.host}:{args.port}: {exc}")

    url = f"http://{args.host}:{args.port}"
    print(f"dashboard  {url}", flush=True)
    print(f"sampling every {args.interval}s over ADB. Ctrl+C stops it.", flush=True)
    if not args.no_open:
        threading.Timer(0.4, lambda: webbrowser.open(url)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped", flush=True)
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
