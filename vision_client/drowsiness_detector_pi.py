"""
drowsiness_detector_pi.py — DrowsySync Vision Client (Raspberry Pi 3B Edition)
================================================================================
Optimised for low-memory, CPU-constrained hardware (Raspberry Pi 3 Model B).
Three-Stage Adaptive Escalation System with Network Session/Log Synchronization.

Key features
  • Headless operation  → no display required; runs as a systemd service
  • MJPEG stream server → live annotated camera feed at http://<Pi-IP>:8080
  • WiFi provisioning  → if no WiFi saved, Pi creates 'DrowsySync-Setup' hotspot
                          so the mobile app can push credentials via WifiSetupActivity
  • DEVICE_ID           → read from /proc/cpuinfo (Pi CPU serial); no hardcoding
  • 320×240 resolution  → ~4× fewer pixels than 640×480 (Pi 3B CPU budget)
  • refine_landmarks=False → disables iris tracking, saves ~30 MB RAM
  • FRAME_SKIP = 2      → MediaPipe runs on every other frame only
  • CAP_PROP_BUFFERSIZE = 1 → prevents stale-frame buffer buildup
  • Pre-allocated landmark array → zero heap allocation inside the hot loop

First-time WiFi setup:
    1. Power on the Pi with no WiFi configured
    2. Pi creates 'DrowsySync-Setup' open hotspot
    3. Connect your phone to 'DrowsySync-Setup'
    4. Open DrowsySync app → Settings → WiFi Setup
    5. Pick your real WiFi → enter password → Pi reboots onto that network

Run:
    python drowsiness_detector_pi.py

View live stream (same WiFi network):
    http://<Pi-IP>:8080

View logs (via SSH):
    journalctl -u drowsysync -f
"""

# ── Standard library ──────────────────────────────────────────────────────────
import collections
import socket
import sys
import threading
import time
from typing import Deque, List, Optional, Tuple

# ── Third-party ───────────────────────────────────────────────────────────────
import os
os.environ["QT_QPA_PLATFORM"] = "xcb"
import cv2
import mediapipe as mp
import numpy as np
import requests

try:
    from picamera2 import Picamera2
    _HAS_PICAMERA2 = True
except ImportError:
    _HAS_PICAMERA2 = False


# =============================================================================
# SECTION 1 — CONFIGURATION
# All tuneable parameters live here. No other section needs editing.
# =============================================================================

# ── Camera & Network ──────────────────────────────────────────────────────────
SERVER_BASE_URL = "https://drowsysync.onrender.com"
CAMERA_INDEX = 0
FRAME_WIDTH = 640      # 640×480 standard resolution (crisp display & fills window)
FRAME_HEIGHT = 480
TARGET_FPS = 20        # Target camera capture FPS
FRAME_SKIP = 2         # Run MediaPipe every Nth frame; hold state on rest
STREAM_PORT = 8080     # MJPEG stream port — open http://<Pi-IP>:8080 on laptop

# ── Device Identity ───────────────────────────────────────────────────────────
def _read_pi_serial() -> str:
    """Read the unique CPU serial from /proc/cpuinfo (available on all Pi models)."""
    try:
        with open("/proc/cpuinfo", "r") as f:
            for line in f:
                if line.startswith("Serial"):
                    return line.split(":")[1].strip().upper()
    except Exception:
        pass
    return "UNKNOWN_PI"

def _get_local_ip() -> str:
    """Best-effort attempt to find the Pi's LAN/hotspot IP address."""
    try:
        # Connect to an external address (no data sent) to find the outbound interface IP
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "unknown"

DEVICE_ID: str = _read_pi_serial()
LOCAL_IP: str = _get_local_ip()
print(f"[DEVICE] ID={DEVICE_ID}  Local IP={LOCAL_IP}")


def _get_wifi_ssid() -> str:
    """Return the current connected WiFi SSID (Linux/Pi only)."""
    try:
        import subprocess
        result = subprocess.check_output(
            ["iwgetid", "-r"], stderr=subprocess.DEVNULL, timeout=2
        ).decode().strip()
        return result if result else "unknown"
    except Exception:
        return "unknown"

# ── EAR — Eye Aspect Ratio ────────────────────────────────────────────────────
EAR_THRESHOLD = 0.18  # Below = eyes considered closed

# ── MAR — Mouth Aspect Ratio ──────────────────────────────────────────────────
MAR_THRESHOLD = 0.50  # Above = yawn detected

# ── PERCLOS — Sliding Window ──────────────────────────────────────────────────
PERCLOS_WINDOW_SECS = 60.0  # rolling window duration (seconds)
PERCLOS_BASELINE_FPS = 20   # assumed Pi FPS for initial deque sizing
PERCLOS_MAXLEN = min(
    int(PERCLOS_WINDOW_SECS * PERCLOS_BASELINE_FPS), 1200
)

# ── PERCLOS Stage Thresholds (%) ──────────────────────────────────────────────
PERCLOS_STAGE1_LO = 4.0  # Stage 1 lower bound
PERCLOS_STAGE1_HI = 8.0  # Stage 1→2 boundary
PERCLOS_STAGE2_HI = 12.0  # Stage 2→3 boundary

# ── Microsleep ────────────────────────────────────────────────────────────────
MICROSLEEP_SECS = 5.0  # continuous eye closure → microsleep event

# ── Yawn Events ───────────────────────────────────────────────────────────────
YAWN_MIN_DURATION_SECS = 1.5  # MAR must exceed threshold for 1.5s to count as 1 event
YAWN_WINDOW_SECS = 180.0  # rolling window for counting yawn events (3 min)
YAWN_STAGE1_COUNT = 2  # yawns in window → Stage 1
YAWN_STAGE2_COUNT = 3  # yawns in window → Stage 2

# ── Stage 3 Recovery ─────────────────────────────────────────────────────────
RECOVERY_SECS = 3.0  # eyes must stay open continuously to silence Stage 3 alarm

# ── MediaPipe Face Mesh landmark indices (468-point model) ────────────────────
LEFT_EYE: List[int] = [33, 160, 158, 133, 153, 144]
RIGHT_EYE: List[int] = [362, 385, 387, 263, 373, 380]
MOUTH_PAIRS: List[Tuple[int, int]] = [(82, 87), (13, 14), (312, 317)]
MOUTH_L = 61
MOUTH_R = 291

# ── Display colors (BGR) ─────────────────────────────────────────────────────
CLR_GREEN = (50, 220, 80)     # Stage 0 — Normal
CLR_YELLOW = (0, 210, 240)    # Stage 1 — Early Fatigue
CLR_ORANGE = (0, 140, 255)    # Stage 2 — Active Drowsiness
CLR_RED = (0, 60, 230)        # Stage 3 — Critical Alarm
CLR_GREY = (160, 160, 160)
CLR_WHITE = (230, 230, 230)

STAGE_COLORS = [CLR_GREEN, CLR_YELLOW, CLR_ORANGE, CLR_RED]
STAGE_LABELS = [
    "NORMAL",
    "STAGE 1 — EARLY FATIGUE",
    "STAGE 2 — ACTIVE DROWSINESS",
    "STAGE 3 — CRITICAL ALARM",
]


# =============================================================================
# SECTION 2 — METRIC FORMULAS
# Pure functions; no state. Fast NumPy operations only.
# =============================================================================

def _dist(a: np.ndarray, b: np.ndarray) -> float:
    d = a - b
    return float(np.sqrt(d[0] * d[0] + d[1] * d[1]))


def compute_ear(lm: np.ndarray, indices: List[int]) -> float:
    p = lm[indices]
    A = _dist(p[1], p[5])
    B = _dist(p[2], p[4])
    C = _dist(p[0], p[3])
    return 0.0 if C == 0.0 else (A + B) / (2.0 * C)


def compute_mar(lm: np.ndarray) -> float:
    vert = sum(_dist(lm[t], lm[b]) for t, b in MOUTH_PAIRS)
    horiz = _dist(lm[MOUTH_L], lm[MOUTH_R])
    return 0.0 if horiz == 0.0 else vert / (2.0 * horiz)


# =============================================================================
# SECTION 3 — DETECTION STATE MACHINE
# =============================================================================

class DetectionState:
    def __init__(self) -> None:
        # PERCLOS sliding window
        self._eye_history: Deque[int] = collections.deque(maxlen=PERCLOS_MAXLEN)
        self._eye_closed_sum: int = 0

        # Microsleep (time-based)
        self._eye_closed_since: Optional[float] = None
        self.microsleep_active: bool = False

        # Yawn events (time-based)
        self._yawn_started_at: Optional[float] = None
        self._yawn_counted: bool = False
        self._yawn_timestamps: List[float] = []

        # Stage 3 latch & recovery
        self.stage3_latched: bool = False
        self._recovery_started_at: Optional[float] = None

        # Public computed fields
        self.ear: float = 0.0
        self.mar: float = 0.0
        self.perclos: float = 0.0
        self.recent_yawn_count: int = 0
        self.stage: int = 0
        self.status: str = STAGE_LABELS[0]
        self.changed: bool = False
        self.recovery_progress: float = 0.0
        self.recovery_remaining: float = RECOVERY_SECS

        self._prev_stage: int = 0

    def update(self, lm: np.ndarray) -> None:
        now = time.time()

        # 1. Compute raw metrics
        ear_l = compute_ear(lm, LEFT_EYE)
        ear_r = compute_ear(lm, RIGHT_EYE)
        self.ear = (ear_l + ear_r) / 2.0
        self.mar = compute_mar(lm)

        eye_closed = self.ear < EAR_THRESHOLD
        is_actively_yawning = self.mar > MAR_THRESHOLD

        # 2. PERCLOS — O(1) running-sum update
        eye_val = 1 if eye_closed else 0
        if len(self._eye_history) == PERCLOS_MAXLEN:
            self._eye_closed_sum -= self._eye_history[0]
        self._eye_history.append(eye_val)
        self._eye_closed_sum += eye_val
        n = len(self._eye_history)
        self.perclos = (self._eye_closed_sum / n * 100.0) if n > 0 else 0.0

        # 3. Smart Microsleep — FPS-independent & Yawn-Gated
        if eye_closed:
            if self._eye_closed_since is None:
                self._eye_closed_since = now

            closed_duration = now - self._eye_closed_since

            if closed_duration >= 5.0:
                self.microsleep_active = True
            elif is_actively_yawning:
                self.microsleep_active = False
            else:
                self.microsleep_active = closed_duration >= MICROSLEEP_SECS
        else:
            self._eye_closed_since = None
            self.microsleep_active = False

        # 4. Yawn events
        if is_actively_yawning:
            if self._yawn_started_at is None:
                self._yawn_started_at = now
            yawn_duration = now - self._yawn_started_at
            if yawn_duration >= YAWN_MIN_DURATION_SECS and not self._yawn_counted:
                self._yawn_timestamps.append(now)
                self._yawn_counted = True
        else:
            self._yawn_started_at = None
            self._yawn_counted = False

        # Prune yawn timestamps outside rolling window
        cutoff = now - YAWN_WINDOW_SECS
        if self._yawn_timestamps and self._yawn_timestamps[0] < cutoff:
            self._yawn_timestamps = [t for t in self._yawn_timestamps if t >= cutoff]
        self.recent_yawn_count = len(self._yawn_timestamps)

        # 5. Determine raw stage
        if self.microsleep_active or self.perclos > PERCLOS_STAGE2_HI:
            raw_stage = 3
        elif (
            self.recent_yawn_count >= YAWN_STAGE2_COUNT
            or self.perclos >= PERCLOS_STAGE1_HI
        ):
            raw_stage = 2
        elif (
            self.recent_yawn_count >= YAWN_STAGE1_COUNT
            or self.perclos >= PERCLOS_STAGE1_LO
        ):
            raw_stage = 1
        else:
            raw_stage = 0

        # 6. Stage 3 latch
        if raw_stage == 3:
            self.stage3_latched = True
            self._recovery_started_at = None

        if self.stage3_latched:
            self._update_recovery(now, eye_closed)
            effective_stage = 3
        else:
            effective_stage = raw_stage

        # 7. Finalise public state
        self._prev_stage = self.stage
        self.stage = effective_stage
        self.status = STAGE_LABELS[self.stage]
        self.changed = self.stage != self._prev_stage

    def _update_recovery(self, now: float, eye_closed: bool) -> None:
        if not eye_closed:
            if self._recovery_started_at is None:
                self._recovery_started_at = now
            elapsed = now - self._recovery_started_at
            self.recovery_progress = min(elapsed / RECOVERY_SECS, 1.0)
            self.recovery_remaining = max(RECOVERY_SECS - elapsed, 0.0)
            if elapsed >= RECOVERY_SECS:
                self.stage3_latched = False
                self._recovery_started_at = None
                self.recovery_progress = 0.0
                self.recovery_remaining = RECOVERY_SECS
        else:
            self._recovery_started_at = None
            self.recovery_progress = 0.0
            self.recovery_remaining = RECOVERY_SECS

    def reset(self) -> None:
        self._eye_closed_since = None
        self.microsleep_active = False
        self._yawn_started_at = None
        self._yawn_counted = False
        self._recovery_started_at = None

        prev = self.stage
        self.stage = 3 if self.stage3_latched else 0
        self.status = STAGE_LABELS[self.stage]
        self.changed = self.stage != prev
        self._prev_stage = self.stage

    def full_reset(self) -> None:
        self._eye_history.clear()
        self._eye_closed_sum = 0
        self._eye_closed_since = None
        self.microsleep_active = False
        self._yawn_started_at = None
        self._yawn_counted = False
        self._yawn_timestamps.clear()
        self.stage3_latched = False
        self._recovery_started_at = None
        self.perclos = 0.0
        self.recent_yawn_count = 0
        self.ear = 0.0
        self.mar = 0.0
        self.stage = 0
        self.status = STAGE_LABELS[0]
        self.changed = False
        self.recovery_progress = 0.0
        self.recovery_remaining = RECOVERY_SECS
        self._prev_stage = 0
        print("\n[INFO] Full session reset — all counters cleared to 0.")

    def to_dict(self) -> dict:
        return {
            "deviceId": DEVICE_ID,
            "localIp": LOCAL_IP,
            "stage": self.stage,
            "status": self.status,
            "perclos": round(self.perclos, 2),
            "ear": round(self.ear, 4),
            "mar": round(self.mar, 4),
            "recent_yawn_count": self.recent_yawn_count,
            "microsleep_active": self.microsleep_active,
            "stage3_latched": self.stage3_latched,
            "timestamp": int(time.time() * 1000),
        }


# =============================================================================
# SECTION 4 — OVERLAY RENDERER (Pi-Optimised Text HUD)
# =============================================================================

_FONT = cv2.FONT_HERSHEY_SIMPLEX


def draw_overlay(
    frame: np.ndarray,
    state: DetectionState,
    fps: float,
    w: int,
    h: int,
) -> None:
    colour = STAGE_COLORS[state.stage]

    # Semi-transparent status banner at top (height 44 px)
    banner = frame[0:44, 0:w]
    banner_bg = np.full_like(banner, (20, 20, 20))
    if state.stage > 0:
        banner_bg[:] = colour
        cv2.addWeighted(banner_bg, 0.40, banner, 0.60, 0, banner)
    else:
        cv2.addWeighted(banner_bg, 0.30, banner, 0.70, 0, banner)

    # Status label (top-left) - crisp, large font
    cv2.putText(frame, state.status, (12, 32), _FONT, 0.85, colour, 2, cv2.LINE_AA)

    # Bottom strip background (semi-transparent black for high contrast readability)
    bottom_bar = frame[h - 40:h, 0:w]
    black_bar = np.zeros_like(bottom_bar)
    cv2.addWeighted(black_bar, 0.65, bottom_bar, 0.35, 0, bottom_bar)

    # Bottom strip metrics (clean, sharp, readable)
    metrics_text = (
        f"EAR: {state.ear:.2f}  |  MAR: {state.mar:.2f}  |  "
        f"PERCLOS: {state.perclos:.1f}%  |  YAWNS: {state.recent_yawn_count}  |  "
        f"FPS: {fps:.0f}"
    )
    cv2.putText(
        frame,
        metrics_text,
        (12, h - 14),
        _FONT,
        0.52,
        CLR_WHITE,
        1,
        cv2.LINE_AA,
    )


def draw_no_face(frame: np.ndarray, fps: float, w: int, h: int) -> None:
    # Semi-transparent top banner
    banner = frame[0:44, 0:w]
    banner_bg = np.full_like(banner, (20, 20, 20))
    cv2.addWeighted(banner_bg, 0.30, banner, 0.70, 0, banner)
    cv2.putText(frame, "NO FACE DETECTED", (12, 32), _FONT, 0.85, CLR_GREY, 2, cv2.LINE_AA)

    # Bottom strip
    bottom_bar = frame[h - 40:h, 0:w]
    black_bar = np.zeros_like(bottom_bar)
    cv2.addWeighted(black_bar, 0.65, bottom_bar, 0.35, 0, bottom_bar)

    cv2.putText(
        frame,
        f"FPS: {fps:.0f}  (Searching for driver...)",
        (12, h - 14),
        _FONT,
        0.52,
        CLR_GREY,
        1,
        cv2.LINE_AA,
    )


# =============================================================================
# SECTION 5 — API / CLOUD SYNC HOOK
# =============================================================================

SESSION_URL = f"{SERVER_BASE_URL}/api/devices/{DEVICE_ID}/session"

# MJPEG Stream server has been removed in favor of local cv2.imshow


def _send_log_async(payload: dict, state: DetectionState) -> None:
    try:
        url = f"{SERVER_BASE_URL}/api/logs"
        response = requests.post(url, json=payload, timeout=3.0)
        response.raise_for_status()

        res_data = response.json()
        if res_data.get("dismissAlarm"):
            state.full_reset()
            print("\n[INFO] Alarm dismissed remotely from mobile app. All counters reset.")
    except Exception as e:
        print(f"\n[WARNING] Failed to sync event to cloud backend: {e}")


# Heartbeat interval — Pi reports WiFi + IP to cloud every 5 seconds
_HEARTBEAT_INTERVAL = 5.0
_last_heartbeat_time = 0.0


def _send_heartbeat() -> None:
    """POST heartbeat to cloud so the app can see Pi status (WiFi, IP, online)."""
    try:
        url = f"{SERVER_BASE_URL}/api/devices/{DEVICE_ID}/heartbeat"
        payload = {
            "currentWifi": _get_wifi_ssid(),
            "localIp": LOCAL_IP,
        }
        requests.post(url, json=payload, timeout=3.0)
    except Exception as e:
        pass  # heartbeat is best-effort; never crash main loop


# Shared session state polled in background thread to eliminate video stutter
class SharedSessionState:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.is_paired: bool = False
        self.user_name: Optional[str] = None
        self.session_active: bool = False
        self.reset_counters: bool = False
        self.running: bool = True


_shared_session = SharedSessionState()


def _session_polling_worker() -> None:
    """Background daemon thread: continuously polls session state without blocking OpenCV loop."""
    prev_active = None
    prev_paired = None
    while _shared_session.running:
        try:
            response = requests.get(SESSION_URL, timeout=3.0)
            if response.status_code == 200:
                data = response.json()
                is_paired = data.get("isPaired", False)
                user_name = data.get("userName")
                session_active = data.get("sessionActive", False)

                if prev_paired is None or is_paired != prev_paired or session_active != prev_active:
                    prev_paired = is_paired
                    prev_active = session_active
                    status_text = "MONITORING ACTIVE" if session_active else "STANDBY"
                    paired_text = f"Paired ({user_name})" if is_paired else "NOT PAIRED"
                    print(f"\n[NETWORK] Cloud Sync: {paired_text} | Mode: {status_text}")

                with _shared_session.lock:
                    _shared_session.is_paired = is_paired
                    _shared_session.user_name = user_name
                    _shared_session.session_active = session_active
                    if data.get("resetCounters"):
                        _shared_session.reset_counters = True
        except Exception:
            pass  # network hiccups handled silently in background
        time.sleep(1.5)


def on_status_change(state: DetectionState) -> None:
    payload = state.to_dict()
    tag = ["[--]", "[!] ", "[!!]", "[!!!!!]"][state.stage]
    print(
        f"\n{tag} [STAGE {state.stage}] {state.status}\n"
        f"         PERCLOS={payload['perclos']}%  "
        f"Yawns={payload['recent_yawn_count']}  "
        f"EAR={payload['ear']}  "
        f"Microsleep={payload['microsleep_active']}"
    )

    # Spawn background thread for network sync
    threading.Thread(target=_send_log_async, args=(payload, state), daemon=True).start()


# =============================================================================
# SECTION 6 — MAIN LOOP
# =============================================================================

def main() -> None:
    global _HAS_PICAMERA2
    print("=" * 64)
    print("  DrowsySync — Raspberry Pi 3B Vision Client (Local Display)")
    print(f"  Device ID  : {DEVICE_ID}")
    print(f"  Local IP   : {LOCAL_IP}")
    print(f"  Resolution : {FRAME_WIDTH}×{FRAME_HEIGHT}  |  Skip : every {FRAME_SKIP} frames")
    print("=" * 64)

    # OpenCV Window Setup (explicit window sizing to prevent toolbar clipping/black borders)
    cv2.namedWindow("DrowsySync - Pi Camera", cv2.WINDOW_AUTOSIZE)
    cv2.resizeWindow("DrowsySync - Pi Camera", FRAME_WIDTH, FRAME_HEIGHT)

    # Camera setup
    if _HAS_PICAMERA2:
        print("[INFO] Initialising Picamera2 native interface...")
        try:
            picam = Picamera2()
            config = picam.create_preview_configuration(main={"size": (FRAME_WIDTH, FRAME_HEIGHT), "format": "RGB888"})
            picam.configure(config)
            picam.start()
            print("[INFO] Picamera2 started successfully.")
        except Exception as e:
            print(f"[ERROR] Failed to start Picamera2: {e}. Falling back to OpenCV.")
            _HAS_PICAMERA2 = False

    if not _HAS_PICAMERA2:
        print("[INFO] Initialising OpenCV VideoCapture interface...")
        cap = cv2.VideoCapture(CAMERA_INDEX)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, FRAME_WIDTH)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, FRAME_HEIGHT)
        cap.set(cv2.CAP_PROP_FPS, TARGET_FPS)

        if not cap.isOpened():
            sys.exit("[ERROR] Cannot open camera via OpenCV. Check CAMERA_INDEX.")

        # Allow camera sensor (e.g. ov5647 CSI / V4L2) 3-4 seconds to negotiate and stabilize stream
        print("[INFO] Waiting for camera sensor stream to stabilize...")
        warmup_ok = False
        for _ in range(30):
            ret, _ = cap.read()
            if ret:
                warmup_ok = True
                break
            time.sleep(0.15)
        if warmup_ok:
            print("[INFO] Camera stream established successfully.")
        else:
            print("[WARNING] Initial camera warmup timed out; continuing to main loop retries.")

    # MediaPipe Face Mesh
    mp_face_mesh = mp.solutions.face_mesh
    face_mesh = mp_face_mesh.FaceMesh(
        max_num_faces=1,
        refine_landmarks=False,  # Saves RAM on Pi
        min_detection_confidence=0.5,
        min_tracking_confidence=0.5,
    )

    # Pre-allocated landmark array (zero allocation in loop)
    lm_px = np.zeros((468, 2), dtype=np.int32)

    # State objects
    state = DetectionState()
    w, h = FRAME_WIDTH, FRAME_HEIGHT

    frame_idx = 0
    face_visible = False

    # FPS counter
    fps_t0 = time.perf_counter()
    fps_val = 0.0
    fps_cnt = 0

    # Session monitoring state
    is_monitoring = False
    last_heartbeat = 0.0  # tracks last heartbeat send time

    # Start non-blocking session polling in background thread and announce device immediately
    threading.Thread(target=_send_heartbeat, daemon=True).start()
    threading.Thread(target=_session_polling_worker, daemon=True).start()

    print("[STANDBY] Waiting for mobile app to start monitoring...\n")

    consecutive_failures = 0
    try:
        while True:
            if _HAS_PICAMERA2:
                try:
                    frame = picam.capture_array()
                    ret = frame is not None
                    if ret:
                        frame = cv2.cvtColor(frame, cv2.COLOR_RGB2BGR)
                except Exception as capture_err:
                    print(f"\n[WARNING] Picamera2 capture failed: {capture_err}")
                    ret = False
            else:
                ret, frame = cap.read()

            if not ret or frame is None:
                consecutive_failures += 1
                if consecutive_failures >= 50:
                    print("\n[ERROR] Frame grab failed consecutively 50 times — check camera connection.")
                    break
                time.sleep(0.1)
                continue
            consecutive_failures = 0

            # Ensure contiguous array matching 640x480 resolution
            if frame.shape[1] != FRAME_WIDTH or frame.shape[0] != FRAME_HEIGHT:
                frame = cv2.resize(frame, (FRAME_WIDTH, FRAME_HEIGHT))
            frame = np.ascontiguousarray(frame)

            now = time.time()

            # Send heartbeat every 5s in background
            if now - last_heartbeat >= _HEARTBEAT_INTERVAL:
                last_heartbeat = now
                threading.Thread(target=_send_heartbeat, daemon=True).start()

            # Non-blocking session check (0ms latency, zero frame lag)
            with _shared_session.lock:
                is_paired = _shared_session.is_paired
                user_name = _shared_session.user_name
                new_monitoring = _shared_session.session_active
                do_reset = _shared_session.reset_counters
                _shared_session.reset_counters = False

            if do_reset:
                state.full_reset()

            # State 1: Device is NOT paired to any mobile app account
            if not is_paired:
                setup_frame = np.full((h, w, 3), (25, 25, 25), dtype=np.uint8)
                cv2.putText(setup_frame, "DROWSYSYNC - NOT PAIRED", (24, 55), _FONT, 0.90, (50, 200, 255), 2, cv2.LINE_AA)
                cv2.putText(setup_frame, "Please pair this device in the mobile app:", (24, 100), _FONT, 0.58, CLR_WHITE, 1, cv2.LINE_AA)

                # Card box displaying Device ID
                cv2.rectangle(setup_frame, (24, 125), (w - 24, 290), (45, 40, 35), -1)
                cv2.rectangle(setup_frame, (24, 125), (w - 24, 290), (80, 75, 70), 1)
                cv2.putText(setup_frame, "DEVICE ID (CPU SERIAL):", (44, 165), _FONT, 0.52, CLR_GREY, 1, cv2.LINE_AA)
                cv2.putText(setup_frame, DEVICE_ID, (44, 215), _FONT, 1.0, (50, 220, 80), 2, cv2.LINE_AA)
                cv2.putText(setup_frame, f"IP: {LOCAL_IP}   |   WiFi: {_get_wifi_ssid()}", (44, 265), _FONT, 0.48, CLR_WHITE, 1, cv2.LINE_AA)

                cv2.putText(setup_frame, "1. Open DrowsySync mobile app -> Settings -> Pair Device", (24, 340), _FONT, 0.50, CLR_WHITE, 1, cv2.LINE_AA)
                cv2.putText(setup_frame, "2. Enter the Device ID shown above and tap 'Pair Device'", (24, 375), _FONT, 0.50, CLR_WHITE, 1, cv2.LINE_AA)
                cv2.putText(setup_frame, "Waiting for phone pairing signal...", (24, h - 25), _FONT, 0.52, (0, 180, 255), 1, cv2.LINE_AA)

                cv2.imshow("DrowsySync - Pi Camera", setup_frame)
                if cv2.waitKey(1) & 0xFF == ord('q'):
                    print("\n[INFO] 'q' pressed. Exiting...")
                    break
                time.sleep(0.08)
                continue

            # Handle monitoring transitions
            if new_monitoring != is_monitoring:
                is_monitoring = new_monitoring
                if is_monitoring:
                    print("\n[MONITORING] Session started — detection active.")
                else:
                    print("\n[STANDBY] Session ended — detection paused.")
                    state.full_reset()

            # State 2: Device is PAIRED, but user has NOT clicked "Start Monitoring"
            if not is_monitoring:
                standby_frame = frame.copy()
                overlay = np.zeros_like(standby_frame)
                cv2.addWeighted(standby_frame, 0.35, overlay, 0.65, 0, standby_frame)
                cv2.putText(standby_frame, "STANDBY", (24, 55), _FONT, 1.2, CLR_WHITE, 3, cv2.LINE_AA)
                paired_label = f"Paired with: {user_name}" if user_name else "Device Paired"
                cv2.putText(standby_frame, paired_label, (24, 100), _FONT, 0.65, (50, 220, 80), 2, cv2.LINE_AA)
                cv2.putText(
                    standby_frame,
                    "Press 'Start Monitoring' in the mobile app to begin trip...",
                    (24, 145), _FONT, 0.55, CLR_WHITE, 1, cv2.LINE_AA
                )
                cv2.putText(
                    standby_frame,
                    f"Device ID: {DEVICE_ID}  |  IP: {LOCAL_IP}",
                    (24, h - 24), _FONT, 0.50, CLR_GREY, 1, cv2.LINE_AA
                )
                cv2.imshow("DrowsySync - Pi Camera", standby_frame)
                if cv2.waitKey(1) & 0xFF == ord('q'):
                    print("\n[INFO] 'q' pressed during standby. Exiting...")
                    break
                time.sleep(0.05)
                continue

            frame_idx += 1
            process_now = (frame_idx % FRAME_SKIP == 0)

            # FPS measurement
            fps_cnt += 1
            if fps_cnt == 30:
                elapsed = time.perf_counter() - fps_t0
                fps_val = 30.0 / elapsed if elapsed > 0 else 0.0
                fps_t0 = time.perf_counter()
                fps_cnt = 0

            if process_now:
                # Downscale strictly for MediaPipe processing to keep Pi 3B CPU fast & cool
                small_frame = cv2.resize(frame, (320, 240))
                rgb = cv2.cvtColor(small_frame, cv2.COLOR_BGR2RGB)
                rgb.flags.writeable = False
                results = face_mesh.process(rgb)

                if results.multi_face_landmarks:
                    face_visible = True
                    fl = results.multi_face_landmarks[0].landmark

                    for i in range(468):
                        lm_px[i, 0] = int(fl[i].x * w)
                        lm_px[i, 1] = int(fl[i].y * h)

                    state.update(lm_px)

                    # Trigger network sync
                    current_time = time.time()
                    if state.changed or (current_time - getattr(state, '_last_pushed_time', 0) >= 2.0):
                        on_status_change(state)
                        state._last_pushed_time = current_time
                else:
                    face_visible = False
                    state.reset()
                    if state.changed:
                        on_status_change(state)

            # Draw HUD overlay on full 640x480 frame
            if face_visible:
                draw_overlay(frame, state, fps_val, w, h)
            else:
                draw_no_face(frame, fps_val, w, h)

            cv2.imshow("DrowsySync - Pi Camera", frame)
            if cv2.waitKey(1) & 0xFF == ord('q'):
                print("\n[INFO] 'q' pressed. Exiting...")
                break
    except KeyboardInterrupt:
        print("\n[INFO] Interrupted by user (Ctrl+C).")
    finally:
        _shared_session.running = False
        print("[INFO] Releasing resources...")
        if _HAS_PICAMERA2:
            try:
                picam.stop()
                picam.close()
            except Exception:
                pass
        else:
            cap.release()
        face_mesh.close()
        cv2.destroyAllWindows()
        print("[INFO] Done.")


if __name__ == "__main__":
    main()
