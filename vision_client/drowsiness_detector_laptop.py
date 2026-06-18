"""
drowsiness_detector_laptop.py — DrowsySync Vision Client (Windows / Laptop)
=============================================================================
Three-Stage Adaptive Escalation System

Replaces simple EAR/MAR counters with clinical-grade behavioural metrics:
  • PERCLOS  — rolling 60-second percentage of eye-closure frames
  • Microsleep — real-time continuous eye closure > 1.2 s (time-based)
  • Smart Yawn Events — distinct yawns (MAR > threshold ≥ 3 s) counted
                        over a rolling 3-minute window

Escalation Stages
  Stage 0 — Normal            (green)
  Stage 1 — Early Fatigue     (yellow)   2 yawns / 3 min  OR PERCLOS 8–12%
  Stage 2 — Active Drowsiness (orange)   3+ yawns         OR PERCLOS 12–15%
  Stage 3 — Critical Alarm    (red)      Microsleep > 1.2 s OR PERCLOS > 15%
             Alarm latches on; only silenced after 3 s continuous eyes-open.

Run:
    python drowsiness_detector_laptop.py

Press  q  to quit.
"""

# ── Standard library ──────────────────────────────────────────────────────────
import collections
import os
import sys
import threading
import time
from enum import Enum, auto
from typing import Deque, List, Optional, Tuple

# ── Third-party ───────────────────────────────────────────────────────────────
import cv2
import mediapipe as mp
import numpy as np
import requests
import requests

try:
    import pygame

    _PYGAME_OK = True
except ImportError:
    _PYGAME_OK = False
    print(
        "[WARNING] pygame not installed — audio alarm disabled. Run: pip install pygame"
    )


# =============================================================================
# SECTION 1 — CONFIGURATION
# All tuneable parameters live here.  No other section needs editing.
# =============================================================================

# ── Camera & Network ──────────────────────────────────────────────────────────
VEHICLE_ID = "DDH4321"  # <-- Edit this to match your Android app's Car Plate!
CAMERA_INDEX = 0
FRAME_WIDTH = 640
FRAME_HEIGHT = 480

# ── EAR — Eye Aspect Ratio ────────────────────────────────────────────────────
EAR_THRESHOLD = 0.18  # LOWERED: so normal blinks don't accidentally trigger closure.

# ── MAR — Mouth Aspect Ratio ──────────────────────────────────────────────────
MAR_THRESHOLD = 0.50  # LOWERED: so you don't have to open your mouth extremely wide to trigger a yawn.

# ── PERCLOS — Sliding Window ──────────────────────────────────────────────────
PERCLOS_WINDOW_SECS = 60.0  # rolling window duration (seconds)
PERCLOS_BASELINE_FPS = 30  # assumed FPS for initial deque sizing
PERCLOS_MAXLEN = min(  # cap at 1800 to bound RAM usage
    int(PERCLOS_WINDOW_SECS * PERCLOS_BASELINE_FPS), 1800
)  # = 1800 entries  →  60 s @ 30 fps, ~72 s @ 25 fps

# ── PERCLOS Stage Thresholds (%) ──────────────────────────────────────────────
PERCLOS_STAGE1_LO = 4.0  # Stage 1 lower bound (was 8.0)
PERCLOS_STAGE1_HI = 8.0  # Stage 1→2 boundary (was 12.0)
PERCLOS_STAGE2_HI = 12.0  # Stage 2→3 boundary (was 15.0)

# ── Microsleep ────────────────────────────────────────────────────────────────
MICROSLEEP_SECS = 5.0  # continuous eye closure → microsleep event

# ── Yawn Events ───────────────────────────────────────────────────────────────
YAWN_MIN_DURATION_SECS = 1.5  # LOWERED: MAR must exceed threshold for 1.5s (instead of 3.0s) to count as 1 event.
YAWN_WINDOW_SECS = 180.0  # rolling window for counting yawn events (3 min)
YAWN_STAGE1_COUNT = 1  # yawns in window → Stage 1 (was 2)
YAWN_STAGE2_COUNT = 2  # yawns in window → Stage 2 (was 3)

# ── Stage 3 Recovery ─────────────────────────────────────────────────────────
RECOVERY_SECS = 3.0  # eyes must stay open continuously to silence Stage 3 alarm

# ── MediaPipe Face Mesh Landmark Indices ─────────────────────────────────────
LEFT_EYE: List[int] = [33, 160, 158, 133, 153, 144]
RIGHT_EYE: List[int] = [362, 385, 387, 263, 373, 380]
MOUTH_PAIRS: List[Tuple[int, int]] = [(82, 87), (13, 14), (312, 317)]
MOUTH_L = 61
MOUTH_R = 291
MOUTH_OUTLINE: List[int] = [
    61,
    146,
    91,
    181,
    84,
    17,
    314,
    405,
    321,
    375,
    291,
    308,
    324,
    318,
    402,
    317,
    14,
    87,
    178,
    88,
    95,
]

# ── Alarm ─────────────────────────────────────────────────────────────────────
ALARM_PATH = "../assets/alarm.wav"

# ── HUD Colours (BGR) ─────────────────────────────────────────────────────────
CLR_GREEN = (50, 210, 60)  # Stage 0 — Normal
CLR_YELLOW = (0, 210, 240)  # Stage 1 — Early Fatigue
CLR_ORANGE = (0, 140, 255)  # Stage 2 — Active Drowsiness
CLR_RED = (30, 30, 210)  # Stage 3 — Critical Alarm
CLR_GREY = (160, 160, 160)
CLR_WHITE = (230, 230, 230)

STAGE_COLORS: List[tuple] = [CLR_GREEN, CLR_YELLOW, CLR_ORANGE, CLR_RED]
STAGE_LABELS: List[str] = [
    "NORMAL",
    "STAGE 1 — EARLY FATIGUE",
    "STAGE 2 — ACTIVE DROWSINESS",
    "STAGE 3 — CRITICAL ALARM",
]


# =============================================================================
# SECTION 2 — METRIC FORMULAS
# Pure functions; no state; fast NumPy operations only.
# =============================================================================


def _dist(a: np.ndarray, b: np.ndarray) -> float:
    d = a - b
    return float(np.sqrt(d[0] * d[0] + d[1] * d[1]))


def compute_ear(lm: np.ndarray, indices: List[int]) -> float:
    """Eye Aspect Ratio — Soukupová & Čech (2016).
    EAR = (|P2−P6| + |P3−P5|) / (2 × |P1−P4|)
    """
    p = lm[indices]
    A = _dist(p[1], p[5])
    B = _dist(p[2], p[4])
    C = _dist(p[0], p[3])
    return 0.0 if C == 0 else (A + B) / (2.0 * C)


def compute_mar(lm: np.ndarray) -> float:
    """Mouth Aspect Ratio — analogous to EAR.
    MAR = Σ vertical_pair_distances / (2 × mouth_width)
    """
    vert = sum(_dist(lm[t], lm[b]) for t, b in MOUTH_PAIRS)
    horiz = _dist(lm[MOUTH_L], lm[MOUTH_R])
    return 0.0 if horiz == 0 else vert / (2.0 * horiz)


# =============================================================================
# SECTION 3 — ALARM MANAGER  (multi-mode: OFF / PULSE / CONTINUOUS)
# =============================================================================


class AlarmMode(Enum):
    OFF = auto()  # Silence
    PULSE = auto()  # Stage 2: intermittent chime (play → gap → repeat)
    CONTINUOUS = auto()  # Stage 3: aggressive looped siren


class AlarmManager:
    """
    Thread-safe alarm controller.
    Call set_mode() from the main loop — returns immediately.

    PULSE    : plays alarm for PULSE_ON_SECS, pauses PULSE_OFF_SECS, repeats.
    CONTINUOUS: loops alarm indefinitely via pygame.mixer.music.play(-1).
    """

    PULSE_ON_SECS = 0.8  # chime play duration
    PULSE_OFF_SECS = 1.5  # gap between chimes

    def __init__(self, wav_path: str) -> None:
        self._path = wav_path
        self._mode = AlarmMode.OFF
        self._lock = threading.Lock()
        self._running = True
        self._ready = False

        if _PYGAME_OK:
            try:
                pygame.mixer.pre_init(frequency=44100, size=-16, channels=1, buffer=512)
                pygame.mixer.init()
                if os.path.exists(wav_path):
                    pygame.mixer.music.load(wav_path)
                    self._ready = True
                else:
                    print(f"[WARNING] Alarm file not found: {wav_path}")
            except Exception as e:
                print(f"[WARNING] pygame init failed: {e}")

        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def set_mode(self, mode: AlarmMode) -> None:
        with self._lock:
            self._mode = mode

    def _run(self) -> None:
        pulse_state = "off"  # "on" | "off"
        pulse_timer = 0.0

        while self._running:
            with self._lock:
                mode = self._mode

            if not self._ready:
                time.sleep(0.05)
                continue

            now = time.time()

            if mode == AlarmMode.OFF:
                if pygame.mixer.music.get_busy():
                    pygame.mixer.music.stop()
                pulse_state = "off"
                pulse_timer = now

            elif mode == AlarmMode.PULSE:
                if pulse_state == "off" and now - pulse_timer >= self.PULSE_OFF_SECS:
                    pygame.mixer.music.play()
                    pulse_state = "on"
                    pulse_timer = now
                elif pulse_state == "on" and now - pulse_timer >= self.PULSE_ON_SECS:
                    pygame.mixer.music.stop()
                    pulse_state = "off"
                    pulse_timer = now

            elif mode == AlarmMode.CONTINUOUS:
                if not pygame.mixer.music.get_busy():
                    pygame.mixer.music.play(-1)  # loop forever
                pulse_state = "off"  # reset pulse state
                pulse_timer = now

            time.sleep(0.05)

    def shutdown(self) -> None:
        self._running = False
        if self._ready and pygame.mixer.music.get_busy():
            pygame.mixer.music.stop()
        self._thread.join(timeout=1.0)
        if _PYGAME_OK:
            pygame.mixer.quit()


# =============================================================================
# SECTION 4 — THREE-STAGE ADAPTIVE DETECTION STATE
# =============================================================================


class DetectionState:
    """
    Implements the Three-Stage Adaptive Escalation System.

    All timing is wall-clock based (time.time()), making thresholds
    FPS-independent and accurate on both laptops and Raspberry Pi.

    PERCLOS is computed with an O(1) running-sum counter — no per-frame
    iteration over the deque — keeping CPU usage negligible.

    Public attributes (read by HUD and API hook)
    ─────────────────────────────────────────────
    ear, mar            : float  — current metric values
    perclos             : float  — current PERCLOS percentage
    recent_yawn_count   : int    — distinct yawns in last YAWN_WINDOW_SECS
    microsleep_active   : bool   — True while eyes closed > MICROSLEEP_SECS
    stage               : int    — 0..3
    status              : str    — human-readable stage label
    changed             : bool   — True when stage transitioned this update
    stage3_latched      : bool   — True while Stage 3 alarm is held on
    recovery_progress   : float  — 0.0→1.0 progress toward Stage 3 silence
    recovery_remaining  : float  — seconds left until Stage 3 silences
    """

    def __init__(self) -> None:
        # ── PERCLOS sliding window ────────────────────────────────────────────
        # Stores binary integers (1=eyes closed, 0=open). Using a running sum
        # avoids O(n) sum() call every frame — critical for Raspberry Pi.
        self._eye_history: Deque[int] = collections.deque(maxlen=PERCLOS_MAXLEN)
        self._eye_closed_sum: int = 0  # running count of 1s in deque

        # ── Microsleep (time-based) ───────────────────────────────────────────
        self._eye_closed_since: Optional[float] = None
        self.microsleep_active: bool = False

        # ── Yawn events (time-based) ──────────────────────────────────────────
        self._yawn_started_at: Optional[float] = None
        self._yawn_counted: bool = False  # guards against re-counting
        self._yawn_timestamps: List[float] = []  # wall-clock times of each event

        # ── Stage 3 latch & recovery ──────────────────────────────────────────
        self.stage3_latched: bool = False
        self._recovery_started_at: Optional[float] = None

        # ── Public computed fields ────────────────────────────────────────────
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

    # ── Public Update ─────────────────────────────────────────────────────────

    def update(self, lm: np.ndarray) -> None:
        """Process one frame's landmarks with Yawn-Gated Eye Tracking."""
        now = time.time()

        # —— 1. Compute raw metrics
        ear_l = compute_ear(lm, LEFT_EYE)
        ear_r = compute_ear(lm, RIGHT_EYE)
        self.ear = (ear_l + ear_r) / 2.0
        self.mar = compute_mar(lm)

        eye_closed = self.ear < EAR_THRESHOLD
        is_actively_yawning = self.mar > MAR_THRESHOLD

        # —— 2. PERCLOS — O(1) running-sum update
        eye_val = 1 if eye_closed else 0
        if len(self._eye_history) == PERCLOS_MAXLEN:
            self._eye_closed_sum -= self._eye_history[0]
        self._eye_history.append(eye_val)
        self._eye_closed_sum += eye_val
        n = len(self._eye_history)
        self.perclos = (self._eye_closed_sum / n * 100.0) if n > 0 else 0.0

        # —— 3. Smart Microsleep — FPS-independent & Yawn-Gated
        if eye_closed:
            if self._eye_closed_since is None:
                self._eye_closed_since = now

            closed_duration = now - self._eye_closed_since

            # Absolute hard limit: if eyes closed for >= 5 seconds, trigger critical alarm immediately
            # even if the driver is yawning.
            if closed_duration >= 5.0:
                self.microsleep_active = True
            # Otherwise, suppress the 1.2s microsleep alarm if they are actively yawning
            elif is_actively_yawning:
                self.microsleep_active = False
            else:
                self.microsleep_active = closed_duration >= MICROSLEEP_SECS
        else:
            self._eye_closed_since = None
            self.microsleep_active = False

        # —— 4. Yawn events — a distinct event = MAR > threshold for ≥ 3 s
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

        # Prune yawn timestamps outside the rolling window
        cutoff = now - YAWN_WINDOW_SECS
        if self._yawn_timestamps and self._yawn_timestamps[0] < cutoff:
            self._yawn_timestamps = [t for t in self._yawn_timestamps if t >= cutoff]
        self.recent_yawn_count = len(self._yawn_timestamps)

        # —— 5. Determine raw stage
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

        # —— 6. Stage 3 latch
        if raw_stage == 3:
            self.stage3_latched = True
            self._recovery_started_at = None

        if self.stage3_latched:
            self._update_recovery(now, eye_closed)
            effective_stage = 3
        else:
            effective_stage = raw_stage

        # —— 7. Finalise public state
        self._prev_stage = self.stage
        self.stage = effective_stage
        self.status = STAGE_LABELS[self.stage]
        self.changed = self.stage != self._prev_stage

    def _update_recovery(self, now: float, eye_closed: bool) -> None:
        """Track the 3-second eyes-open recovery window for Stage 3 unlatch."""
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
            # Eyes closed again — reset recovery window
            self._recovery_started_at = None
            self.recovery_progress = 0.0
            self.recovery_remaining = RECOVERY_SECS

    def reset(self) -> None:
        """
        Call when the face disappears from frame.
        Resets real-time tracking but preserves behavioural history
        (eye_history, yawn_timestamps, stage3_latched) so a brief
        face loss does not wipe accumulated fatigue data.
        """
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

    def to_dict(self) -> dict:
        """JSON-serialisable payload for Firebase / REST API."""
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
# SECTION 5 — HUD RENDERER
# =============================================================================

_FONT = cv2.FONT_HERSHEY_SIMPLEX
_FONT_BOLD = cv2.FONT_HERSHEY_DUPLEX


def _bar(
    frame: np.ndarray,
    x: int,
    y: int,
    w: int,
    h: int,
    ratio: float,
    fill_clr: tuple,
    label: str,
) -> None:
    """Draws a labelled filled progress bar."""
    filled = int(max(0.0, min(ratio, 1.0)) * w)
    cv2.rectangle(frame, (x, y), (x + w, y + h), (40, 40, 40), -1)
    if filled > 0:
        cv2.rectangle(frame, (x, y), (x + filled, y + h), fill_clr, -1)
    cv2.rectangle(frame, (x, y), (x + w, y + h), (100, 100, 100), 1)
    cv2.putText(frame, label, (x, y - 4), _FONT, 0.37, CLR_GREY, 1, cv2.LINE_AA)


def draw_eye_contours(frame: np.ndarray, lm: np.ndarray) -> None:
    for indices in (LEFT_EYE, RIGHT_EYE):
        pts = lm[indices].reshape(-1, 1, 2).astype(np.int32)
        hull = cv2.convexHull(pts)
        cv2.drawContours(frame, [hull], -1, CLR_GREEN, 1, cv2.LINE_AA)


def draw_mouth_contour(frame: np.ndarray, lm: np.ndarray) -> None:
    pts = lm[MOUTH_OUTLINE].reshape(-1, 1, 2).astype(np.int32)
    hull = cv2.convexHull(pts)
    cv2.drawContours(frame, [hull], -1, CLR_ORANGE, 1, cv2.LINE_AA)


def draw_hud(
    frame: np.ndarray,
    state: DetectionState,
    fps: float,
    w: int,
    h: int,
) -> None:
    """
    Renders the full Three-Stage HUD onto *frame* (in-place).

    Layout:
      Top strip  — stage label (left)  |  PERCLOS & yawn count (right)
      Below strip — MICROSLEEP indicator (if active)
      Bottom     — EAR / MAR / FPS metric strip
      Bar area   — PERCLOS bar + stage threshold ticks
                   Recovery bar (only while Stage 3 latched)
    """
    colour = STAGE_COLORS[state.stage]

    # ── Semi-transparent coloured banner (top 55 px, only when alerting)
    if state.stage > 0:
        roi = frame[0:55, 0:w]
        overlay = roi.copy()
        cv2.rectangle(overlay, (0, 0), (w, 55), colour, -1)
        cv2.addWeighted(overlay, 0.25, roi, 0.75, 0, roi)

    # ── Stage label  (top-left)
    cv2.putText(frame, state.status, (10, 40), _FONT_BOLD, 0.80, colour, 2, cv2.LINE_AA)

    # ── PERCLOS (top-right, line 1)
    cv2.putText(
        frame,
        f"PERCLOS: {state.perclos:.1f}%",
        (w - 195, 24),
        _FONT,
        0.55,
        CLR_WHITE,
        1,
        cv2.LINE_AA,
    )

    # ── Recent Yawn Count (top-right, line 2)
    yawn_clr = CLR_YELLOW if state.recent_yawn_count >= YAWN_STAGE1_COUNT else CLR_WHITE
    cv2.putText(
        frame,
        f"Recent Yawns: {state.recent_yawn_count}",
        (w - 195, 48),
        _FONT,
        0.55,
        yawn_clr,
        1,
        cv2.LINE_AA,
    )

    # ── MICROSLEEP indicator  (appears below the banner when active)
    if state.microsleep_active:
        cv2.putText(
            frame,
            "  ⚠  MICROSLEEP  ⚠",
            (10, 80),
            _FONT_BOLD,
            0.72,
            CLR_RED,
            2,
            cv2.LINE_AA,
        )

    # ── Bottom metric strip (EAR / MAR / FPS)
    cv2.putText(
        frame,
        f"EAR: {state.ear:.3f}    MAR: {state.mar:.3f}    FPS: {fps:.1f}",
        (10, h - 10),
        _FONT,
        0.50,
        CLR_WHITE,
        1,
        cv2.LINE_AA,
    )

    # ── PERCLOS progress bar (full width: 0–20% range)
    # Threshold tick marks at 8%, 12%, 15% (relative to 20% max)
    bar_w = 220
    bar_x = 10
    perclos_ratio = min(state.perclos / 20.0, 1.0)
    _bar(
        frame,
        bar_x,
        h - 55,
        bar_w,
        13,
        perclos_ratio,
        colour,
        f"PERCLOS  {state.perclos:.1f}%",
    )

    for pct_thresh, tick_clr in [
        (PERCLOS_STAGE1_LO, CLR_YELLOW),
        (PERCLOS_STAGE1_HI, CLR_ORANGE),
        (PERCLOS_STAGE2_HI, CLR_RED),
    ]:
        tick_x = bar_x + int(pct_thresh / 20.0 * bar_w)
        cv2.line(frame, (tick_x, h - 55), (tick_x, h - 42), tick_clr, 2)

    # ── Stage 3 Recovery bar (only while latched)
    if state.stage3_latched:
        _bar(
            frame,
            bar_x,
            h - 82,
            bar_w,
            13,
            state.recovery_progress,
            CLR_GREEN,
            f"Keep eyes open — {state.recovery_remaining:.1f}s to silence",
        )


def draw_no_face(frame: np.ndarray, fps: float, h: int) -> None:
    cv2.putText(
        frame, "No face detected", (10, 40), _FONT_BOLD, 0.90, CLR_GREY, 2, cv2.LINE_AA
    )
    cv2.putText(
        frame, f"FPS: {fps:.1f}", (10, h - 10), _FONT, 0.50, CLR_GREY, 1, cv2.LINE_AA
    )


# =============================================================================
# SECTION 6 — API / CLOUD EVENT HOOK
# Fires only on stage transitions (not every frame) to avoid network spam.
# =============================================================================


def _send_log_async(payload: dict) -> None:
    """Background task to send the event payload to the Node.js server."""
    try:
        # CORRECTION: Changed /api/events to /api/logs to match server.js
        url = "https://drowsysync.onrender.com/api/logs"
        response = requests.post(url, json=payload, timeout=3.0)
        response.raise_for_status()
    except requests.exceptions.RequestException as e:
        # Graceful failure: print a warning without crashing or blocking the script
        print(f"\n[WARNING] Failed to sync event to cloud backend: {e}")

def on_status_change(state: DetectionState) -> None:
    """
    Called whenever the detected stage changes.
    Dispatches the network request in a daemon thread to prevent frame drops.
    """
    payload = state.to_dict()
    tag = ["[--]", "[!] ", "[!!]", "[!!!!!]"][state.stage]
    print(
        f"\n{tag} [STAGE {state.stage}] {state.status}\n"
        f"         PERCLOS={payload['perclos']}%  "
        f"Yawns={payload['recent_yawn_count']}  "
        f"EAR={payload['ear']}  "
        f"Microsleep={payload['microsleep_active']}"
    )

    # Spawn a non-blocking background thread for the network request
    threading.Thread(target=_send_log_async, args=(payload,), daemon=True).start()


# =============================================================================
# SECTION 7 — MAIN LOOP
# =============================================================================


def main() -> None:
    print("=" * 62)
    print("  DrowsySync — Laptop Vision Client")
    print("  Three-Stage Adaptive Escalation System")
    print(f"  Resolution : {FRAME_WIDTH}×{FRAME_HEIGHT}  |  Every frame processed")
    print(
        f"  PERCLOS window : {PERCLOS_MAXLEN} frames  "
        f"(~{PERCLOS_WINDOW_SECS:.0f}s @ {PERCLOS_BASELINE_FPS} fps)"
    )
    print(
        f"  Microsleep threshold : {MICROSLEEP_SECS}s  |  "
        f"Yawn event min : {YAWN_MIN_DURATION_SECS}s"
    )
    print("  Press  q  to quit.")
    print("=" * 62)

    # ── Camera ────────────────────────────────────────────────────────────────
    cap = cv2.VideoCapture(CAMERA_INDEX)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, FRAME_WIDTH)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, FRAME_HEIGHT)
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

    if not cap.isOpened():
        sys.exit("[ERROR] Cannot open camera. Check CAMERA_INDEX in config.")

    # ── MediaPipe Face Mesh ───────────────────────────────────────────────────
    mp_face_mesh = mp.solutions.face_mesh
    face_mesh = mp_face_mesh.FaceMesh(
        max_num_faces=1,
        refine_landmarks=False,  # no iris tracking → saves RAM on Pi
        min_detection_confidence=0.5,
        min_tracking_confidence=0.5,
    )

    # ── Pre-allocated landmark pixel array (zero heap-alloc in hot loop) ──────
    lm_px = np.zeros((468, 2), dtype=np.int32)

    # ── Supporting objects ────────────────────────────────────────────────────
    alarm = AlarmManager(ALARM_PATH)
    state = DetectionState()
    w, h = FRAME_WIDTH, FRAME_HEIGHT

    # FPS meter (rolling average over 30 frames)
    fps_val = 0.0
    fps_t0 = time.perf_counter()
    fps_cnt = 0

    print("[INFO] Camera open. Starting detection...\n")

    try:
        while True:
            ret, frame = cap.read()
            if not ret:
                print("[ERROR] Frame grab failed — is the webcam connected?")
                break

            # ── FPS measurement ───────────────────────────────────────────────
            fps_cnt += 1
            if fps_cnt == 30:
                elapsed = time.perf_counter() - fps_t0
                fps_val = 30.0 / elapsed if elapsed > 0 else 0.0
                fps_t0 = time.perf_counter()
                fps_cnt = 0

            # ── MediaPipe inference ───────────────────────────────────────────
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            results = face_mesh.process(rgb)

            if results.multi_face_landmarks:
                fl = results.multi_face_landmarks[0].landmark

                # Fill pre-allocated landmark array — no heap allocation
                for i in range(468):
                    lm_px[i, 0] = int(fl[i].x * w)
                    lm_px[i, 1] = int(fl[i].y * h)

                state.update(lm_px)

                # Fire API hook only on stage transitions
                if state.changed:
                    on_status_change(state)

                # ── Set alarm mode based on effective stage ────────────────────
                if state.stage3_latched or state.stage == 3:
                    # Stage 3: aggressive continuous siren
                    alarm.set_mode(AlarmMode.CONTINUOUS)
                else:
                    # Stage 0, 1, or 2: silence
                    alarm.set_mode(AlarmMode.OFF)

                # ── Draw ──────────────────────────────────────────────────────
                draw_eye_contours(frame, lm_px)
                draw_mouth_contour(frame, lm_px)
                draw_hud(frame, state, fps_val, w, h)

                # Console status (overwrites in place)
                print(
                    f"\r[S{state.stage}] {state.status:<28} "
                    f"PERCLOS={state.perclos:5.1f}%  "
                    f"Yawns={state.recent_yawn_count}  "
                    f"EAR={state.ear:.3f}  "
                    f"MAR={state.mar:.3f}",
                    end="",
                    flush=True,
                )

            else:
                # ── No face in frame ──────────────────────────────────────────
                state.reset()
                if state.changed:
                    on_status_change(state)

                # Keep Stage 3 alarm alive even without face (safety)
                alarm.set_mode(
                    AlarmMode.CONTINUOUS if state.stage3_latched else AlarmMode.OFF
                )

                draw_no_face(frame, fps_val, h)
                print(
                    f"\r[--] No face detected"
                    f"{'  [STAGE 3 LATCHED]' if state.stage3_latched else ''}"
                    f"                              ",
                    end="",
                    flush=True,
                )

            cv2.imshow("DrowsySync — Laptop", frame)
            if cv2.waitKey(1) & 0xFF == ord("q"):
                break

    except KeyboardInterrupt:
        print("\n[INFO] Interrupted by user.")
    finally:
        print("\n[INFO] Shutting down...")
        alarm.set_mode(AlarmMode.OFF)
        alarm.shutdown()
        cap.release()
        face_mesh.close()
        cv2.destroyAllWindows()
        print("[INFO] Done.")


if __name__ == "__main__":
    main()
