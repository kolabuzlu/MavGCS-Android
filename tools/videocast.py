"""Send a capture card's picture to MavGCS Android over the network.

The tablet cannot take the capture card directly. Its USB port carries the
card at 480 Mbit/s, and the card offers nothing but uncompressed 4K, which
wants 373 MB/s -- so it refuses to stream at all. The computer has no such
problem: it takes the card over USB 3, and sending a compressed picture over
wifi costs about ten megabits.

So this runs on the computer the card is plugged into, and the tablet opens the
address it prints.

    python videocast.py

That is the whole of it for the usual case. It finds the card, sends 1080p at
20 frames a second, and prints the address to type into the tablet's Live video
window.

    python videocast.py --list              which capture devices are here
    python videocast.py --device 2          if it picked the wrong one
    python videocast.py --width 1280 --fps 15 --quality 65
                                            for a slower link
    python videocast.py --port 8080         if something else has that port

Needs only OpenCV, which is already installed alongside MavGCS's tools:

    pip install opencv-python

Stop it with Ctrl+C.
"""
import argparse
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    import cv2
except ImportError:
    sys.exit("This needs OpenCV.  pip install opencv-python")

BOUNDARY = "mavgcsframe"


def local_address():
    """The address this machine is reachable at from the tablet.

    Found by asking the routing table which interface would be used to reach
    the outside world, which is the one on the same network as the tablet. No
    packet is actually sent.
    """
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    except Exception:
        return socket.gethostbyname(socket.gethostname())
    finally:
        probe.close()


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
        print("  viewer connected from %s" % self.client_address[0], flush=True)
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
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            print("  viewer from %s disconnected" % self.client_address[0], flush=True)
        except Exception as exc:
            print("  viewer error: %s" % exc, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", type=int, default=None,
                        help="capture device index; found automatically if omitted")
    parser.add_argument("--width", type=int, default=1920)
    parser.add_argument("--height", type=int, default=1080)
    parser.add_argument("--fps", type=int, default=20)
    parser.add_argument("--quality", type=int, default=75, help="JPEG quality, 1-100")
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
    host = local_address()
    print()
    print("Capture device %d, %dx%d native" % (index, camera.native[0], camera.native[1]))
    print("Sending %dx%d at %d fps, JPEG quality %d"
          % (args.width, args.height, args.fps, args.quality))
    print()
    print("  On the tablet: Live video, Stream, and this address")
    print()
    print("      http://%s:%d" % (host, args.port))
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
    """A line a second, so it is obvious whether frames are moving."""
    last = camera.count
    while camera.running:
        time.sleep(2.0)
        now = camera.count
        size = len(camera.latest() or b"")
        print("  %5.1f fps   %4.0f KB a frame   %4.1f Mbit/s"
              % ((now - last) / 2.0, size / 1024.0,
                 (now - last) / 2.0 * size * 8 / 1e6), flush=True)
        last = now


if __name__ == "__main__":
    main()
