"""
drowsiness_detector_pi.py — DrowsySync Vision Client (Raspberry Pi Edition)
=============================================================================
Optimised for low-memory, CPU-constrained hardware (Raspberry Pi 4, 1 GB RAM).
Three-Stage Adaptive Escalation System with Network Session/Log Synchronization.

Key optimisations applied
  • 320×240 capture resolution  → ~4× fewer pixels than 640×480
  • refine_landmarks=False      → disables iris tracking, saves ~30 MB RAM
  • FRAME_SKIP = 2              → MediaPipe runs on every other frame only
  • CAP_PROP_BUFFERSIZE = 1     → prevents stale-frame buffer buildup
  • Pre-allocated landmark array → zero heap allocation inside the hot loop
  • Minimal overlay drawing      → text-only HUD, no complex contour operations

Run:
    python drowsiness_detector_pi.py

Press  q  to quit.
"""

# ── Standard library ──────────────────────────────────────────────────────────
import collections
import sys
import threading
import time
from typing import Deque, List, Optional, Tuple

# ── Third-party ───────────────────────────────────────────────────────────────
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
VEHICLE_ID = "DDH4321"  # <-- Edit this to match your Android app's Car Plate!
SERVER_BASE_URL = "https://drowsysync.onrender.com"
CAMERA_INDEX = 0
FRAME_WIDTH = 320      # Keep at 320×240 for best Pi performance
FRAME_HEIGHT = 240
TARGET_FPS = 20       # Target camera capture FPS
FRAME_SKIP = 2        # Run MediaPipe every Nth frame; hold state on rest

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
            "vehicleId": VEHICLE_ID,
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

    # Semi-transparent status banner (top 36 px)
    if state.stage > 0:
        roi = frame[0:36, 0:w]
        overlay = roi.copy()
        cv2.rectangle(overlay, (0, 0), (w, 36), colour, -1)
        cv2.addWeighted(overlay, 0.35, roi, 0.65, 0, roi)

    # Status label (top-left)
    cv2.putText(frame, state.status, (6, 26), _FONT, 0.55, colour, 2, cv2.LINE_AA)

    # Bottom strip metrics (compact text)
    cv2.putText(
        frame,
        f"EAR:{state.ear:.2f} MAR:{state.mar:.2f} PERCLOS:{state.perclos:.1f}% Y:{state.recent_yawn_count} FPS:{fps:.0f}",
        (6, h - 8),
        _FONT,
        0.35,
        CLR_WHITE,
        1,
        cv2.LINE_AA,
    )


def draw_no_face(frame: np.ndarray, fps: float, h: int) -> None:
    cv2.putText(frame, "NO FACE DETECTED", (6, 26), _FONT, 0.55, CLR_GREY, 2, cv2.LINE_AA)
    cv2.putText(
        frame, f"FPS:{fps:.0f}", (6, h - 8),
        _FONT, 0.35, CLR_GREY, 1, cv2.LINE_AA,
    )


# =============================================================================
# SECTION 5 — API / CLOUD SYNC HOOK
# =============================================================================

SESSION_URL = f"{SERVER_BASE_URL}/api/session/{VEHICLE_ID}"


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


def poll_session_status() -> dict:
    try:
        response = requests.get(SESSION_URL, timeout=3.0)
        response.raise_for_status()
        return response.json()
    except Exception as e:
        print(f"\n[WARNING] Session poll failed: {e}")
        return {"sessionActive": False, "resetCounters": False}


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
    print("=" * 60)
    print("  DrowsySync — Raspberry Pi Vision Client")
    print(f"  Resolution : {FRAME_WIDTH}×{FRAME_HEIGHT}  |  Skip : every {FRAME_SKIP} frames")
    print("  Press  q  to quit.")
    print("=" * 60)

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
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, FRAME_WIDTH)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, FRAME_HEIGHT)
        cap.set(cv2.CAP_PROP_FPS, TARGET_FPS)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

        if not cap.isOpened():
            sys.exit("[ERROR] Cannot open camera via OpenCV. Check CAMERA_INDEX.")

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
    last_session_poll = 0.0
    SESSION_POLL_INTERVAL = 1.5

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

            if not ret:
                consecutive_failures += 1
                if consecutive_failures >= 15:
                    print("\n[ERROR] Frame grab failed consecutively 15 times — check camera connection.")
                    break
                time.sleep(0.1)
                continue
            consecutive_failures = 0

            # Force contiguous array with standard strides to prevent MediaPipe C++ memory/padding crashes
            frame = cv2.resize(frame, (320, 240))

            # Poll session status from backend every 1.5s
            now = time.time()
            if now - last_session_poll >= SESSION_POLL_INTERVAL:
                last_session_poll = now
                session = poll_session_status()

                # Handle counter reset
                if session.get("resetCounters"):
                    state.full_reset()

                # Handle monitoring transitions
                new_monitoring = session.get("sessionActive", False)
                if new_monitoring != is_monitoring:
                    is_monitoring = new_monitoring
                    if is_monitoring:
                        print("\n[MONITORING] Session started — detection active.")
                    else:
                        print("\n[STANDBY] Session ended — detection paused.")
                        state.full_reset()

            # Standby mode
            if not is_monitoring:
                standby_frame = frame.copy()
                cv2.putText(standby_frame, "STANDBY", (6, 26), _FONT, 0.75, CLR_GREY, 2, cv2.LINE_AA)
                cv2.putText(
                    standby_frame,
                    "Waiting for app to start monitoring...",
                    (6, 50), _FONT, 0.40, CLR_GREY, 1, cv2.LINE_AA
                )
                cv2.imshow("DrowsySync", standby_frame)
                if cv2.waitKey(1) & 0xFF == ord("q"):
                    break
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
                rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
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

            # Draw HUD
            if face_visible:
                draw_overlay(frame, state, fps_val, w, h)
            else:
                draw_no_face(frame, fps_val, h)

            cv2.imshow("DrowsySync", frame)
            if cv2.waitKey(1) & 0xFF == ord("q"):
                break

    except KeyboardInterrupt:
        print("\n[INFO] Interrupted by user (Ctrl+C).")
    finally:
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
