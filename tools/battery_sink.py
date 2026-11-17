"""Receives the probe app's upload stream and logs the battery level it reports.

The glasses send 32 KB/s of silence (a stand-in for streaming audio to a speech
service) plus one "BAT,level,temp,plugged" line every 10 seconds. Run this on the PC,
then start the probe's battery page with `--es sink <pc-ip>:8765` and unplug USB.

Usage: python tools/battery_sink.py [--minutes 60] [--port 8765]
"""
import argparse
import csv
import datetime
import pathlib
import socket
import time

parser = argparse.ArgumentParser()
parser.add_argument("--minutes", type=int, default=60, help="unplugged minutes to record before exiting")
parser.add_argument("--port", type=int, default=8765)
parser.add_argument("--wait-hours", type=float, default=3.0, help="give up if the run has not finished by then")
args = parser.parse_args()

out_dir = pathlib.Path(__file__).parent / "out"
out_dir.mkdir(exist_ok=True)
out_path = out_dir / "battery_sink.csv"

server = socket.socket()
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("0.0.0.0", args.port))
server.listen(1)
server.settimeout(5)

deadline = time.time() + args.wait_hours * 3600
unplugged_start = None
start_level = None
last_level = None
total_bytes = 0
finished = False

with out_path.open("w", newline="") as handle:
    writer = csv.writer(handle)
    writer.writerow(["time", "unplugged_min", "level", "temp_c", "plugged", "received_kb"])
    print(f"listening on :{args.port}, logging to {out_path}", flush=True)
    while not finished and time.time() < deadline:
        try:
            conn, peer = server.accept()
        except socket.timeout:
            continue
        print(f"connected: {peer[0]}", flush=True)
        conn.settimeout(15)
        pending = b""
        try:
            while not finished and time.time() < deadline:
                data = conn.recv(65536)
                if not data:
                    break
                total_bytes += len(data)
                # Status lines are ASCII between runs of zero bytes.
                pending += data.replace(b"\x00", b"")
                while b"\n" in pending:
                    line, pending = pending.split(b"\n", 1)
                    parts = line.decode("ascii", "ignore").strip().split(",")
                    if len(parts) != 4 or parts[0] != "BAT":
                        continue
                    level, temp, plugged = int(parts[1]), float(parts[2]), parts[3] == "true"
                    now = time.time()
                    if plugged:
                        unplugged_start = None
                        start_level = None
                    elif unplugged_start is None:
                        unplugged_start = now
                        start_level = level
                        print(f"USB unplugged at {level}%, run started", flush=True)
                    minutes = 0.0 if unplugged_start is None else (now - unplugged_start) / 60
                    last_level = level
                    writer.writerow([
                        datetime.datetime.now().strftime("%H:%M:%S"), f"{minutes:.1f}",
                        level, temp, plugged, total_bytes // 1024,
                    ])
                    handle.flush()
                    if unplugged_start is not None and (minutes >= args.minutes or level <= 5):
                        finished = True
        except (socket.timeout, ConnectionError) as error:
            print(f"connection lost: {error}", flush=True)
        finally:
            conn.close()

if finished and start_level is not None:
    minutes = (time.time() - unplugged_start) / 60
    print(f"RESULT: {start_level}% -> {last_level}% in {minutes:.0f} min "
          f"({start_level - last_level} points), received {total_bytes // 1024} KB")
else:
    print(f"NO RESULT: run did not complete (last level {last_level}, received {total_bytes // 1024} KB)")
