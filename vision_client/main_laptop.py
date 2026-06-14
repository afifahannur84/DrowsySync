import cv2
import dlib
import numpy as np
import threading
import time
import pygame
import os

def eye_aspect_ratio(eye):
    # Compute the euclidean distances between the two sets of vertical eye landmarks
    A = np.linalg.norm(eye[1] - eye[5])
    B = np.linalg.norm(eye[2] - eye[4])

    # Compute the euclidean distance between the horizontal eye landmark
    C = np.linalg.norm(eye[0] - eye[3])

    # Compute the eye aspect ratio
    ear = (A + B) / (2.0 * C)
    return ear

# Paths to assets
PREDICTOR_PATH = "../assets/shape_predictor_68_face_landmarks.dat"
ALARM_PATH = "../assets/alarm.wav"

# Thresholds
EYE_AR_THRESH = 0.25
EYE_AR_CONSEC_FRAMES = 30

# Global variables
alarm_triggered = False
running = True
is_playing = False

def play_alarm_thread():
    """
    Background thread to manage alarm audio playback without blocking the camera feed.
    """
    global running, alarm_triggered, is_playing
    while running:
        if alarm_triggered:
            if not is_playing:
                try:
                    # Play the alarm in an infinite loop
                    pygame.mixer.music.play(-1)
                except Exception as e:
                    print(f"[ERROR] Could not play alarm: {e}")
                is_playing = True
        else:
            if is_playing:
                pygame.mixer.music.stop()
                is_playing = False
        time.sleep(0.1)

def main():
    global alarm_triggered, running

    # Initialize pygame mixer for audio
    pygame.mixer.init()
    if os.path.exists(ALARM_PATH):
        pygame.mixer.music.load(ALARM_PATH)
    else:
        print(f"[WARNING] Alarm file not found at {ALARM_PATH}")

    # Start audio thread
    audio_thread = threading.Thread(target=play_alarm_thread, daemon=True)
    audio_thread.start()

    print("[INFO] Loading facial landmark predictor...")
    if not os.path.exists(PREDICTOR_PATH):
        print(f"[ERROR] Predictor file not found at {PREDICTOR_PATH}")
        return

    detector = dlib.get_frontal_face_detector()
    predictor = dlib.shape_predictor(PREDICTOR_PATH)

    # 68-point landmarks: Left eye: 42-47, Right eye: 36-41
    (lStart, lEnd) = (42, 48) # 42 to 47
    (rStart, rEnd) = (36, 42) # 36 to 41

    print("[INFO] Starting video stream...")
    cap = cv2.VideoCapture(0)
    
    counter = 0

    try:
        while True:
            ret, frame = cap.read()
            if not ret:
                print("[ERROR] Failed to grab frame from camera")
                break

            # Resize to speed up processing
            frame = cv2.resize(frame, (640, 480))
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

            # Detect faces in the grayscale frame
            rects = detector(gray, 0)

            for rect in rects:
                # Determine the facial landmarks for the face region
                shape = predictor(gray, rect)
                
                # Convert landmarks to NumPy array
                coords = np.zeros((68, 2), dtype="int")
                for i in range(0, 68):
                    coords[i] = (shape.part(i).x, shape.part(i).y)

                # Extract the left and right eye coordinates
                leftEye = coords[lStart:lEnd]
                rightEye = coords[rStart:rEnd]

                # Calculate the eye aspect ratio for both eyes
                leftEAR = eye_aspect_ratio(leftEye)
                rightEAR = eye_aspect_ratio(rightEye)

                # Average the eye aspect ratio
                ear = (leftEAR + rightEAR) / 2.0

                # Compute convex hull for the eyes and draw them
                leftEyeHull = cv2.convexHull(leftEye)
                rightEyeHull = cv2.convexHull(rightEye)
                cv2.drawContours(frame, [leftEyeHull], -1, (0, 255, 0), 1)
                cv2.drawContours(frame, [rightEyeHull], -1, (0, 255, 0), 1)

                # Check to see if the eye aspect ratio is below the blink threshold
                if ear < EYE_AR_THRESH:
                    counter += 1

                    # If the eyes were closed for a sufficient number of frames, sound the alarm
                    if counter >= EYE_AR_CONSEC_FRAMES:
                        alarm_triggered = True

                        # Draw warning text on the frame
                        cv2.putText(frame, "DROWSINESS DETECTED!", (10, 30),
                                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)
                else:
                    # Reset the eye frame counter
                    counter = 0
                    alarm_triggered = False

                # Draw the computed eye aspect ratio on the frame
                cv2.putText(frame, f"EAR: {ear:.2f}", (300, 30),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)

            # Show the frame
            cv2.imshow("Frame", frame)
            
            # If the `q` key was pressed, break from the loop
            key = cv2.waitKey(1) & 0xFF
            if key == ord("q"):
                break
                
    except KeyboardInterrupt:
        print("[INFO] Interrupted by user")
    finally:
        print("[INFO] Cleaning up...")
        running = False
        alarm_triggered = False
        cap.release()
        cv2.destroyAllWindows()
        # Wait for audio thread to gracefully exit
        audio_thread.join(timeout=1.0)
        pygame.mixer.quit()

if __name__ == "__main__":
    main()
