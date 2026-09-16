"""Send a capture card's picture to MavGCS Android over the network.

The tablet cannot take the capture card directly. Its USB port carries the card
at 480 Mbit/s, and the card offers nothing but uncompressed 4K, which wants
373 MB/s -- so it refuses to stream at all. The computer has no such problem:
it takes the card over USB 3 and sends a small compressed picture instead.

So this runs on the computer the card is plugged into, and the tablet opens the
address it prints.

    python videocast.py

MEASURED, on the setup this was written for -- a Walksnail VRX into a Cam Link,
both machines on an ExpressLRS TX backpack access point:

    that link carries about 1.4 Mbit/s, no more, whatever its 72 Mbps
    association rate claims. It is an ESP32 radio, not a router.

    1920x1080 at 20 fps   6.9 Mbit/s    tablet got 4 fps    far too much
     960x540  at 15 fps   1.4 Mbit/s    tablet got 15 fps   fills the link
     640x360  at 12 fps   0.6 Mbit/s    tablet got 12 fps   the default

The default is the conservative one on purpose. That same link is carrying the
MAVLink telemetry, and a picture is not worth crowding the aircraft's own
messages off the air. On a proper wifi network, turn it up freely:

    python videocast.py --width 1920 --height 1080 --fps 20 --quality 75

While it runs it prints two rates: what it is capturing, and what each viewer
is actually receiving. When the second is well below the first, the link is the
limit and the answer is a smaller size, a lower rate or more compression.

    python videocast.py --list              which capture devices are here
    python videocast.py --device 2          if it picked the wrong one
    python videocast.py --port 8080         if something else has that port

Needs only OpenCV, which is already installed alongside MavGCS's tools:

    pip install opencv-python

Stop it with Ctrl+C.
"""
import argparse
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    import cv2
except ImportError:
    sys.exit("This needs OpenCV.  pip install opencv-python")

BOUNDARY = "mavgcsframe"


def local_addresses():
    """Every address this machine might be reachable at.

    Deliberately not just the one the routing table prefers. On a flying setup
    the computer is on the link's own access point, which has no route to the
    internet, so the routed interface is the wrong one -- it would print a home
    network address while the tablet sits on the radio's.

    Asking the operating system for its interface list, because looking the
    hostname up misses addresses: on the machine this was written for it
    returned two and silently omitted the one the tablet was actually on.

    Link-local and virtual-switch addresses are dropped; nothing reaches a
    tablet on those.
    """
    found = []

    def keep(ip):
        if ip.startswith(("127.", "169.254.")):
            return
        if ip not in found:
            found.append(ip)

    try:
        if sys.platform.startswith("win"):
            # PowerShell rather than ipconfig: ipconfig's text is localised and
            # it quietly omitted an adapter that did have an address, which on
            # the machine this was written for was the one the tablet was on.
            out = subprocess.run(
                ["powershell", "-NoProfile", "-Command",
                 "(Get-NetIPAddress -AddressFamily IPv4).IPAddress"],
                capture_output=True, text=True,
            ).stdout
            for line in out.splitlines():
                ip = line.strip()
                if ip.count(".") == 3:
                    keep(ip)
        else:
            out = subprocess.run(["ip", "-4", "-o", "addr"],
                                 capture_output=True, text=True).stdout
            for line in out.splitlines():
                parts = line.split()
                if "inet" in parts:
                    keep(parts[parts.index("inet") + 1].split("/")[0])
    except Exception:
        pass

    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            keep(info[4][0])
    except Exception:
        pass

    return found or ["(could not work out this machine's address)"]


def list_devices(limit=6):
    """Every capture device the system will open, with a note on each."""
    print("Looking for capture devices...")
    for index in range(limit):
        cap = cv2.VideoCapture(index, cv2.CAP_DSHOW)
        if not cap.isOpened():
            cap.release()
            continue
        # A moment to settle: a capture card hands out black frames while it
        # locks on to the signal, and judging it too early calls it empty.
        time.sleep(1.0)
        ok, frame = cap.read()
        size = "%dx%d" % (cap.get(3), cap.get(4))
        note = "no frame"
        if ok:
            import numpy as np
            detail = float(np.std(frame))
            note = "picture" if detail > 3 else "blank or black"
            note += "  (detail %.1f)" % detail
        print("  --device %d   %s   %s" % (index, size, note))
        cap.release()
    print("\nPick the one that says picture, at the size you expect.")


class Camera:
    """One capture device, read by a thread so every client sees the latest.

    A shared reader rather than one per viewer: two clients each opening the
    card would fight over it, and the second would simply fail.
    """

    def __init__(self, index, width, height, fps, quality):
        self.quality = quality
        self.fps = fps
        self.jpeg = None
        self.count = 0
        self.lock = threading.Lock()
        self.running = True
        self.cap = cv2.VideoCapture(index, cv2.CAP_DSHOW)
        if not self.cap.isOpened():
            raise SystemExit("Could not open capture device %d. Try --list." % index)
        # Ask the card for the size wanted. A capture card often ignores this
        # and hands over whatever its input is; the frame is resized below
        # either way, so this is an optimisation rather than a requirement.
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        self.cap.set(cv2.CAP_PROP_FPS, fps)
        time.sleep(1.0)
        self.native = (int(self.cap.get(3)), int(self.cap.get(4)))
        self.want = (width, height)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        interval = 1.0 / max(self.fps, 1)
        params = [cv2.IMWRITE_JPEG_QUALITY, self.quality]
        nxt = time.time()
        while self.running:
            ok, frame = self.cap.read()
            if not ok:
                time.sleep(0.05)
                continue
            if (frame.shape[1], frame.shape[0]) != self.want:
                frame = cv2.resize(frame, self.want, interpolation=cv2.INTER_AREA)
            ok, buf = cv2.imencode(".jpg", frame, params)
            if ok:
                with self.lock:
                    self.jpeg = buf.tobytes()
                    self.count += 1
            # Paced rather than free-running: the card will hand over frames
            # faster than the link should carry them, and the newest is the
            # only one worth sending.
            nxt += interval
            delay = nxt - time.time()
            if delay > 0:
                time.sleep(delay)
            else:
                nxt = time.time()

    def latest(self):
        with self.lock:
            return self.jpeg

    def close(self):
        self.running = False
        time.sleep(0.2)
        self.cap.release()


VIEWERS = {}
VIEWERS_LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    camera = None

    def log_message(self, *args):
        pass  # The status line below is more use than a line per request.

    def do_GET(self):
        if self.path in ("/", "/stream", "/video"):
            self.stream()
        else:
            self.send_error(404)

    def stream(self):
        self.send_response(200)
        self.send_header("Age", "0")
        self.send_header("Cache-Control", "no-cache, private")
        self.send_header("Pragma", "no-cache")
        self.send_header(
            "Content-Type", "multipart/x-mixed-replace; boundary=%s" % BOUNDARY
        )
        self.end_headers()
        who = self.client_address[0]
        print("  viewer connected from %s" % who, flush=True)
        with VIEWERS_LOCK:
            VIEWERS[who] = [0, 0]          # frames delivered, bytes delivered
        sent = None
        try:
            while True:
                frame = Handler.camera.latest()
                if frame is None or frame is sent:
                    time.sleep(0.005)
                    continue
                sent = frame
                self.wfile.write(b"--" + BOUNDARY.encode() + b"\r\n")
                self.wfile.write(b"Content-Type: image/jpeg\r\n")
                self.wfile.write(
                    b"Content-Length: " + str(len(frame)).encode() + b"\r\n\r\n"
                )
                self.wfile.write(frame)
                self.wfile.write(b"\r\n")
                # Counted after the write returns, which on a slow link is
                # after the link has actually taken it: TCP pushes back, so
                # this measures what the radio delivered rather than what was
                # offered to it.
                with VIEWERS_LOCK:
                    seen = VIEWERS.get(who)
                    if seen:
                        seen[0] += 1
                        seen[1] += len(frame) + 80
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            print("  viewer from %s disconnected" % who, flush=True)
        except Exception as exc:
            print("  viewer error: %s" % exc, flush=True)
        finally:
            with VIEWERS_LOCK:
                VIEWERS.pop(who, None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", type=int, default=None,
                        help="capture device index; found automatically if omitted")
    # Defaults sized for the radio's own access point rather than for a home
    # network. See the note at the top: that link measured 1.4 Mbit/s, and it
    # is also carrying the telemetry, so the picture should not take all of it.
    parser.add_argument("--width", type=int, default=640)
    parser.add_argument("--height", type=int, default=360)
    parser.add_argument("--fps", type=int, default=12)
    parser.add_argument("--quality", type=int, default=55, help="JPEG quality, 1-100")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--list", action="store_true", help="show capture devices and exit")
    args = parser.parse_args()

    if args.list:
        list_devices()
        return

    index = args.device
    if index is None:
        index = find_device()
        if index is None:
            sys.exit("No capture device with a picture on it. Try --list.")

    camera = Camera(index, args.width, args.height, args.fps, args.quality)
    Handler.camera = camera
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    hosts = local_addresses()
    print()
    print("Capture device %d, %dx%d native" % (index, camera.native[0], camera.native[1]))
    print("Sending %dx%d at %d fps, JPEG quality %d"
          % (args.width, args.height, args.fps, args.quality))
    print()
    print("  On the tablet: Live video, Stream, and this address")
    print()
    for host in hosts:
        print("      http://%s:%d" % (host, args.port))
    if len(hosts) > 1:
        print()
        print("  More than one address here. Use the one on the same network as")
        print("  the tablet -- on a flying setup that is the link's own access")
        print("  point, not a home network.")
    print()
    print("Ctrl+C to stop.")
    print()
    watcher = threading.Thread(target=report, args=(camera,), daemon=True)
    watcher.start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopping")
    finally:
        camera.close()
        server.shutdown()


def find_device(limit=6):
    """The first device that is actually showing something.

    A capture card with nothing plugged into it still opens and still hands
    over frames; they are simply black. Flat frames are skipped so that the
    card carrying a picture is the one chosen.
    """
    import numpy as np
    for index in range(limit):
        cap = cv2.VideoCapture(index, cv2.CAP_DSHOW)
        if not cap.isOpened():
            cap.release()
            continue
        time.sleep(1.0)
        ok, frame = cap.read()
        cap.release()
        if ok and float(np.std(frame)) > 3:
            return index
    return None


def report(camera):
    """What is being captured, and what each viewer is actually receiving.

    The two are different numbers and the difference is the whole story. The
    capture rate says what the card and this machine can do; the viewer rate
    says what the radio link between here and the tablet will carry. When the
    second is well below the first, the picture on the tablet is behind and the
    answer is a smaller size, a lower rate or more compression.
    """
    last = camera.count
    marks = {}
    while camera.running:
        time.sleep(2.0)
        now = camera.count
        size = len(camera.latest() or b"")
        print("  capturing %5.1f fps  %4.0f KB a frame  %4.1f Mbit/s"
              % ((now - last) / 2.0, size / 1024.0,
                 (now - last) / 2.0 * size * 8 / 1e6), flush=True)
        last = now
        with VIEWERS_LOCK:
            snapshot = {k: list(v) for k, v in VIEWERS.items()}
        for who, (frames, byts) in snapshot.items():
            was = marks.get(who, (0, 0))
            fps = (frames - was[0]) / 2.0
            mbit = (byts - was[1]) * 8 / 2.0 / 1e6
            marks[who] = (frames, byts)
            note = ""
            if fps < camera.fps * 0.6:
                note = "   <- the link is the limit, not the camera"
            print("    to %-15s %5.1f fps  %4.1f Mbit/s%s" % (who, fps, mbit, note),
                  flush=True)


if __name__ == "__main__":
    main()
