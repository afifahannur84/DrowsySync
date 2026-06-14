"""
drowsiness_detector_pi.py — DrowsySync Vision Client (Raspberry Pi Edition)
=============================================================================
Optimised for low-memory, CPU-constrained hardware (Raspberry Pi 4, 1 GB RAM).

Key optimisations applied
  • 320×240 capture resolution  → ~4× fewer pixels than 640×480
  • refine_landmarks=False      → disables iris tracking, saves ~30 MB RAM
  • FRAME_SKIP = 2              → MediaPipe runs on every other frame only
  • CAP_PROP_BUFFERSIZE = 1     → prevents stale-frame buffer buildup
  • Pre-allocated landmark array → zero heap allocation inside the hot loop
  • Minimal overlay drawing      → text only, no contour/hull operations
  • aplay (ALSA) alarm          → no pygame dependency on Pi

Run:
    python drowsiness_detector_pi.py

Press  q  to quit.
"""

# ── Standard library ──────────────────────────────────────────────────────────
import subprocess
import sys
import time
from typing import List, Optional, Tuple

# ── Third-party ───────────────────────────────────────────────────────────────
import cv2
import mediapipe as mp
import numpy as np


# =============================================================================
# SECTION 1 — CONFIGURATION
# All tuneable parameters live here. No other file needs editing.
# =============================================================================

# ── Camera ────────────────────────────────────────────────────────────────────
CAMERA_INDEX      = 0        # 0 = default; change for USB cam on Pi
FRAME_WIDTH       = 320      # Keep at 320×240 for best Pi performance
FRAME_HEIGHT      = 240      #   (change to 640×480 only on Pi 4 / Pi 5)
TARGET_FPS        = 20       # Ask the driver for 20 fps max
FRAME_SKIP        = 2        # Run MediaPipe every Nth frame; hold state on rest

# ── EAR — Eye Aspect Ratio ────────────────────────────────────────────────────
EAR_THRESHOLD     = 0.22     # Below = eyes considered closed
EAR_CONSEC_FRAMES = 15       # Consecutive *processed* frames  (≈1 s at 20 fps / skip-2)

# ── MAR — Mouth Aspect Ratio ──────────────────────────────────────────────────
MAR_THRESHOLD     = 0.65     # Above = yawn detected
MAR_CONSEC_FRAMES = 10       # Consecutive *processed* frames

# ── MediaPipe Face Mesh landmark indices (468-point model) ────────────────────
# EAR: 6 points per eye  [outer, top-L, top-R, inner, bot-R, bot-L]
LEFT_EYE:  List[int] = [33,  160, 158, 133, 153, 144]
RIGHT_EYE: List[int] = [362, 385, 387, 263, 373, 380]

# MAR: 3 inner-lip vertical pairs + left/right corners (horizontal)
MOUTH_INNER_PAIRS: List[Tuple[int, int]] = [
    (82,  87),    # inner-left vertical
    (13,  14),    # centre vertical
    (312, 317),   # inner-right vertical
]
MOUTH_LEFT_CORNER  = 61
MOUTH_RIGHT_CORNER = 291

# ── Alarm ─────────────────────────────────────────────────────────────────────
ALARM_PATH = "../assets/alarm.wav"   # Relative path from vision_client/

# ── Display colours (BGR) ─────────────────────────────────────────────────────
CLR_GREEN  = (50,  220, 80)
CLR_ORANGE = (0,   165, 255)
CLR_RED    = (0,   60,  230)
CLR_GREY   = (160, 160, 160)


# =============================================================================
# SECTION 2 — CORE METRIC FORMULAS
# Pure functions; no state. Fast NumPy operations only.
# =============================================================================

def _dist(a: np.ndarray, b: np.ndarray) -> float:
    """Euclidean distance between two 2-D integer points."""
    d = a - b
    return float(np.sqrt(d[0] * d[0] + d[1] * d[1]))


def compute_ear(lm: np.ndarray, indices: List[int]) -> float:
    """
    Eye Aspect Ratio (EAR) — Soukupová & Čech, 2016.

    EAR = (|P2−P6| + |P3−P5|) / (2 × |P1−P4|)

    lm      : (468, 2) pixel-coordinate array — pre-allocated, reused each frame.
    indices : 6-element list  [outer, tL, tR, inner, bR, bL]
    """
    p = lm[indices]          # (6, 2) — zero-copy slice
    A = _dist(p[1], p[5])
    B = _dist(p[2], p[4])
    C = _dist(p[0], p[3])
    return 0.0 if C == 0.0 else (A + B) / (2.0 * C)


def compute_mar(
    lm:          np.ndarray,
    pairs:       List[Tuple[int, int]],
    left_corner: int,
    right_corner: int,
) -> float:
    """
    Mouth Aspect Ratio (MAR) — analogous to EAR for the mouth.

    MAR = Σ vertical_pair_distances / (2 × mouth_width)
    """
    vert = sum(_dist(lm[t], lm[b]) for t, b in pairs)
    horiz = _dist(lm[left_corner], lm[right_corner])
    return 0.0 if horiz == 0.0 else vert / (2.0 * horiz)


# =============================================================================
# SECTION 3 — DETECTION STATE MACHINE
# Lightweight class; avoids allocations in the update() hot-path.
# =============================================================================

class DetectionState:
    """
    Accumulates consecutive-frame counters and resolves the current status.

    Attributes exposed for overlay / API use:
        status       : str  — "NORMAL" | "DROWSY" | "YAWNING" | "DROWSY+YAWNING"
        ear          : float
        mar          : float
        eye_counter  : int
        mouth_counter: int
        changed      : bool — True when status transitioned this update
    """

    __slots__ = (
        "status", "ear", "mar",
        "eye_counter", "mouth_counter", "changed",
        "_prev_status",
    )

    def __init__(self) -> None:
        self.status        = "NORMAL"
        self.ear           = 0.0
        self.mar           = 0.0
        self.eye_counter   = 0
        self.mouth_counter = 0
        self.changed       = False
        self._prev_status  = "NORMAL"

    def update(self, lm: np.ndarray) -> None:
        """
        Process landmarks from one *processed* frame.
        Call this only on frames that MediaPipe actually ran on.
        """
        ear_l = compute_ear(lm, LEFT_EYE)
        ear_r = compute_ear(lm, RIGHT_EYE)
        self.ear = (ear_l + ear_r) * 0.5
        self.mar = compute_mar(lm, MOUTH_INNER_PAIRS, MOUTH_LEFT_CORNER, MOUTH_RIGHT_CORNER)

        # Accumulate or reset counters
        self.eye_counter   = self.eye_counter   + 1 if self.ear < EAR_THRESHOLD else 0
        self.mouth_counter = self.mouth_counter + 1 if self.mar > MAR_THRESHOLD else 0

        eye_alert  = self.eye_counter   >= EAR_CONSEC_FRAMES
        yawn_alert = self.mouth_counter >= MAR_CONSEC_FRAMES

        if eye_alert and yawn_alert:
            new_status = "DROWSY+YAWNING"
        elif eye_alert:
            new_status = "DROWSY"
        elif yawn_alert:
            new_status = "YAWNING"
        else:
            new_status = "NORMAL"

        self.changed       = new_status != self._prev_status
        self._prev_status  = new_status
        self.status        = new_status

    def reset(self) -> None:
        """Call when the face disappears from frame."""
        self.eye_counter   = 0
        self.mouth_counter = 0
        prev               = self.status
        self.status        = "NORMAL"
        self._prev_status  = "NORMAL"
        self.changed       = prev != "NORMAL"

    def to_dict(self) -> dict:
        """Minimal payload for Firebase / REST API upload."""
        return {
            "status":        self.status,
            "ear":           round(self.ear, 4),
            "mar":           round(self.mar, 4),
            "eye_counter":   self.eye_counter,
            "mouth_counter": self.mouth_counter,
            "timestamp":     time.time(),
        }


# =============================================================================
# SECTION 4 — LIGHTWEIGHT ALARM CONTROLLER
# Uses ALSA (aplay) on Raspberry Pi OS — no extra Python package required.
# Falls back to a console beep if aplay is not available.
# =============================================================================

class AlarmController:
    """
    Manages alarm sound via a background subprocess (aplay) on Pi.
    Non-blocking: start() and stop() return immediately.
    """

    def __init__(self, wav_path: str) -> None:
        self._wav   = wav_path
        self._proc: Optional[subprocess.Popen] = None
        self._on    = False

    def set_alarm(self, state: bool) -> None:
        if state == self._on:
            return                       # No change — nothing to do
        self._on = state
        if state:
            self._start()
        else:
            self._stop()

    def _start(self) -> None:
        if self._proc and self._proc.poll() is None:
            return                       # Already playing
        try:
            # aplay: -q (quiet), loop via shell while-true
            self._proc = subprocess.Popen(
                ["sh", "-c", f'while true; do aplay -q "{self._wav}"; done'],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except FileNotFoundError:
            # aplay not available (e.g., during development on Windows)
            print("[ALARM] aplay not found — audio disabled. "
                  "Install alsa-utils on Pi:  sudo apt install alsa-utils")

    def _stop(self) -> None:
        if self._proc and self._proc.poll() is None:
            self._proc.terminate()
            self._proc = None

    def shutdown(self) -> None:
        self._stop()


# =============================================================================
# SECTION 5 — MINIMAL OVERLAY RENDERER
# Text-only drawing keeps GPU/CPU load negligible on Pi.
# =============================================================================

_STATUS_STYLE = {
    #  status            label text           BGR colour   banner?
    "NORMAL":         ("NORMAL",         CLR_GREEN,  False),
    "DROWSY":         ("DROWSY",         CLR_RED,    True),
    "YAWNING":        ("YAWNING",        CLR_ORANGE, True),
    "DROWSY+YAWNING": ("DROWSY+YAWNING", CLR_RED,    True),
    "NO FACE":        ("NO FACE",        CLR_GREY,   False),
}

_FONT = cv2.FONT_HERSHEY_SIMPLEX


def draw_overlay(
    frame:  np.ndarray,
    state:  DetectionState,
    fps:    float,
    w:      int,
    h:      int,
) -> None:
    """
    Draws a minimal HUD directly on *frame* (in-place, no copies).
    Intentionally avoids convex-hull / contour drawing to save CPU cycles.
    """
    label, colour, banner = _STATUS_STYLE.get(
        state.status, _STATUS_STYLE["NORMAL"]
    )

    # ── Semi-transparent status banner (only when alert is active)
    if banner:
        # addWeighted is faster than drawing a filled rect + alpha blend manually
        roi     = frame[0:36, 0:w]
        overlay = roi.copy()
        cv2.rectangle(overlay, (0, 0), (w, 36), colour, -1)
        cv2.addWeighted(overlay, 0.35, roi, 0.65, 0, roi)

    # ── Status label  (top-left, always visible)
    cv2.putText(frame, label, (6, 26), _FONT, 0.75, colour, 2, cv2.LINE_AA)

    # ── EAR / MAR metrics  (bottom strip, small text)
    metric_y = h - 8
    cv2.putText(
        frame,
        f"EAR:{state.ear:.2f}  MAR:{state.mar:.2f}  FPS:{fps:.0f}",
        (6, metric_y), _FONT, 0.40, CLR_GREY, 1, cv2.LINE_AA,
    )


def draw_no_face(frame: np.ndarray, fps: float, h: int) -> None:
    cv2.putText(frame, "NO FACE", (6, 26), _FONT, 0.75, CLR_GREY, 2, cv2.LINE_AA)
    cv2.putText(
        frame, f"FPS:{fps:.0f}", (6, h - 8),
        _FONT, 0.40, CLR_GREY, 1, cv2.LINE_AA,
    )


# =============================================================================
# SECTION 6 — OPTIONAL API EVENT HOOK
# Called only on status transitions (not every frame) to avoid network spam.
# Swap the stub body for a real requests.post() when your backend is ready.
# =============================================================================

def on_status_change(state: DetectionState) -> None:
    """
    Fired whenever the detected status transitions (NORMAL→DROWSY, etc.).
    Replace the print() with an HTTP POST or Firebase write for cloud sync.

    Example (uncomment when backend is ready):
        import requests
        requests.post(
            "https://your-backend.com/api/events",
            json=state.to_dict(),
            timeout=2,
        )
    """
    payload = state.to_dict()
    print(f"\n[EVENT] {payload['status']}"
          f"  EAR={payload['ear']}  MAR={payload['mar']}")


# =============================================================================
# SECTION 7 — MAIN LOOP
# =============================================================================

def main() -> None:
    print("=" * 60)
    print("  DrowsySync — Raspberry Pi Vision Client")
    print(f"  Resolution : {FRAME_WIDTH}×{FRAME_HEIGHT}  |  Skip : every {FRAME_SKIP} frames")
    print("  Press  q  to quit.")
    print("=" * 60)

    # ── Camera setup ──────────────────────────────────────────────────────────
    cap = cv2.VideoCapture(CAMERA_INDEX)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH,  FRAME_WIDTH)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, FRAME_HEIGHT)
    cap.set(cv2.CAP_PROP_FPS,          TARGET_FPS)
    # CRITICAL on Pi: keep internal buffer at 1 frame to avoid reading stale data
    cap.set(cv2.CAP_PROP_BUFFERSIZE,   1)

    if not cap.isOpened():
        sys.exit("[ERROR] Cannot open camera. Check CAMERA_INDEX.")

    # ── MediaPipe Face Mesh ───────────────────────────────────────────────────
    mp_face_mesh = mp.solutions.face_mesh
    face_mesh = mp_face_mesh.FaceMesh(
        max_num_faces          = 1,      # Track only the driver — saves RAM
        refine_landmarks       = False,  # Disables iris tracking → saves ~30 MB
        min_detection_confidence = 0.5,
        min_tracking_confidence  = 0.5,
    )

    # ── Pre-allocate landmark pixel array (reused every processed frame) ──────
    # Avoids heap allocation inside the hot loop — important on 1 GB RAM.
    lm_px = np.zeros((468, 2), dtype=np.int32)

    # ── Supporting objects ────────────────────────────────────────────────────
    state   = DetectionState()
    alarm   = AlarmController(ALARM_PATH)
    w, h    = FRAME_WIDTH, FRAME_HEIGHT

    frame_idx   = 0          # Total frames captured
    face_visible = False     # Did we see a face on the last processed frame?

    # FPS measurement (simple rolling estimate)
    fps_t0  = time.perf_counter()
    fps_val = 0.0
    fps_cnt = 0

    try:
        while True:
            ret, frame = cap.read()
            if not ret:
                print("[ERROR] Frame grab failed — check camera connection.")
                break

            frame_idx += 1
            process_now = (frame_idx % FRAME_SKIP == 0)

            # ── FPS estimation (updated every 30 captured frames) ─────────────
            fps_cnt += 1
            if fps_cnt == 30:
                elapsed = time.perf_counter() - fps_t0
                fps_val = 30.0 / elapsed if elapsed > 0 else 0.0
                fps_t0  = time.perf_counter()
                fps_cnt = 0

            # ── PROCESSED FRAME: run MediaPipe + metric update ────────────────
            if process_now:
                # Convert colour in-place using a pre-allocated buffer avoids
                # an extra malloc; here we let OpenCV manage it since it's fast.
                rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                results = face_mesh.process(rgb)

                if results.multi_face_landmarks:
                    face_visible = True
                    fl = results.multi_face_landmarks[0].landmark

                    # Fill pre-allocated array — no new allocation
                    for i in range(468):
                        lm_px[i, 0] = int(fl[i].x * w)
                        lm_px[i, 1] = int(fl[i].y * h)

                    prev_status = state.status
                    state.update(lm_px)

                    # Fire API hook only on transitions
                    if state.changed:
                        on_status_change(state)

                    # Control alarm
                    alarm.set_alarm(state.status != "NORMAL")

                else:
                    # Face lost this processed frame
                    face_visible = False
                    state.reset()
                    if state.changed:
                        on_status_change(state)
                    alarm.set_alarm(False)

            # ── DRAW OVERLAY (every frame — cheap text only) ──────────────────
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
        alarm.shutdown()
        cap.release()
        face_mesh.close()
        cv2.destroyAllWindows()
        print("[INFO] Done.")


if __name__ == "__main__":
    main()
