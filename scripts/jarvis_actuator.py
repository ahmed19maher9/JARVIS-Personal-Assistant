import os
import socket

# Import Jarvis sound manager
from jarvis_utils import report_status
from jarvis_sound_manager import get_sound_manager, SoundEvent

def check_port_lock(port):
    """Ensures no other instance is holding the port before loading heavy modules"""
    for host in ['127.0.0.1', 'localhost']:
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.settimeout(0.5)
                if s.connect_ex((host, port)) == 0:
                    print(f"⚠️ [ACTUATOR] Port {port} is occupied on {host}. Terminating.")
                    os._exit(0)
        except: pass

import time
import threading
import cv2
import numpy as np
import pyautogui, asyncio
import uvicorn
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from typing import Optional, List

# Suppress OpenCV backend hangs on Windows
os.environ["OPENCV_VIDEOIO_PRIORITY_MSMF"] = "0"

# --- BULLETPROOF MEDIAPIPE IMPORTS ---
try:
    import mediapipe as mp
    from mediapipe.tasks import python
    from mediapipe.tasks.python import vision
    print("[JARVIS] MediaPipe Modules Loaded Successfully")
except ImportError as e:
    print(f"[ERROR] MediaPipe not found in current environment: {e}")
    print("Suggest: pip install mediapipe")
    os._exit(1)

app = FastAPI(title="Jarvis Actuator Service (Repulsors)")

# Initialize sound manager
sound_manager = get_sound_manager()

# Global screen resolution for coordinate mapping
SCREEN_W, SCREEN_H = pyautogui.size()

# State flag to track if the camera and model are actually ready
TRACKING_READY = False

# Global Frame Storage for Streaming
_processed_frame = None
_frame_lock = threading.Lock()

# Safety settings for your Dell 7577
# Disabling FAILSAFE allows clicks and movement to function at the screen edges/corners
pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0 

# --- DATA MODELS ---
class MouseCommand(BaseModel):
    x: Optional[int] = None
    y: Optional[int] = None
    button: str = "left"
    clicks: int = 1

class KeyboardCommand(BaseModel):
    text: str
    press_enter: bool = True

class HotKeyCommand(BaseModel):
    keys: List[str]

class MoveCommand(BaseModel):
    x: int
    y: int
    duration: float = 0.1

class ScrollCommand(BaseModel):
    clicks: int
    direction: str = "down" # "up" or "down"

# --- HEALTH CHECK ENDPOINTS ---
@app.get("/health")
async def health_root():
    state = "ready" if TRACKING_READY else "starting"
    return {"status": state, "module": "REPULSORS", "detail": "Actuators online"}

@app.get("/video_feed")
async def video_feed():
    def gen_frames():
        while True:
            with _frame_lock:
                if _processed_frame is None:
                    time.sleep(0.1)
                    continue
                ret, buffer = cv2.imencode('.jpg', _processed_frame)
                frame_bytes = buffer.tobytes()
            yield (b'--frame\r\n'
                   b'Content-Type: image/jpeg\r\n\r\n' + frame_bytes + b'\r\n')
            time.sleep(0.04)
    return StreamingResponse(gen_frames(), media_type="multipart/x-mixed-replace; boundary=frame")

@app.post("/shutdown")
async def shutdown():
    threading.Thread(target=lambda: (time.sleep(1), os._exit(0)), daemon=True).start()
    return {"status": "shutting down"}

# --- WEBSOCKET FOR REAL-TIME CONTROL ---
@app.websocket("/ws/repulsor")
async def websocket_repulsor(websocket: WebSocket):
    await websocket.accept()
    try:
        while True:
            # Expects JSON: {"x": 100, "y": 200, "action": "move"}
            data = await websocket.receive_json()
            if data.get("action") == "move":
                pyautogui.moveTo(data["x"], data["y"])
            elif data.get("action") == "click":
                pyautogui.click()
    except WebSocketDisconnect:
        print("[ACTUATOR] Repulsor WebSocket Disconnected")

# --- ACTUATION ENDPOINTS ---
@app.post("/actuate/click")
async def click_mouse(cmd: MouseCommand):
    try:
        # Play click sound
        sound_manager.play_sound_async(SoundEvent.CLICK)
        
        if cmd.x is not None and cmd.y is not None:
            pyautogui.click(x=cmd.x, y=cmd.y, clicks=cmd.clicks, button=cmd.button)
        else:
            pyautogui.click(clicks=cmd.clicks, button=cmd.button)
        return {"status": "success"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/actuate/move")
async def move_mouse(cmd: MoveCommand):
    try:
        pyautogui.moveTo(cmd.x, cmd.y, duration=cmd.duration)
        return {"status": "success"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/actuate/scroll")
async def scroll_mouse(cmd: ScrollCommand):
    try:
        amount = cmd.clicks if cmd.direction == "up" else -cmd.clicks
        pyautogui.scroll(amount)
        return {"status": "success"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/actuate/drag_start")
async def drag_start():
    pyautogui.mouseDown()
    return {"status": "dragging"}

@app.post("/actuate/drag_stop")
async def drag_stop():
    pyautogui.mouseUp()
    return {"status": "dropped"}

# --- BACKGROUND TRACKING ENGINE ---
def start_hand_tracking():
    print("--- [ACTUATOR] Ocular Tracking Engine Started ---")
    
    # 1. Setup the Task Options for Hand Landmarker
    base_options = python.BaseOptions(model_asset_path='hand_landmarker.task')
    hand_options = vision.HandLandmarkerOptions(
        base_options=base_options,
        num_hands=1,
        min_hand_detection_confidence=0.5,
        running_mode=vision.RunningMode.VIDEO # Optimized for camera streams
    )
    
    # 1. Setup Face Options
    face_base_options = python.BaseOptions(model_asset_path='face_landmarker.task')
    face_options = vision.FaceLandmarkerOptions(
        base_options=face_base_options,
        output_face_blendshapes=True,
        running_mode=vision.RunningMode.VIDEO
    )

    # 2. Initialize the Hand Detector
    hand_detector = vision.HandLandmarker.create_from_options(hand_options)
    
    # 2. Initialize the Face Detector
    face_landmarker = vision.FaceLandmarker.create_from_options(face_options)
    
    # Use DirectShow backend for faster hardware link on Windows
    cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
    if not cap.isOpened():
        print(" [ACTUATOR ERROR] Camera index 0 unavailable. Actuator movement disabled.")
        return

    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1) # Prevent frame lag
    # This part is slow because it negotiates with the hardware driver
    print("[ACTUATOR] Negotiating 480p hardware link for low latency...")
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
    
    # Smoothing variables to prevent mouse jitter
    prev_x, prev_y = SCREEN_W // 2, SCREEN_H // 2 # Initialize to screen center
    smooth_factor = 0.4  # Increased for higher hand sensitivity
    is_dragging = False
    is_scrolling = False
    click_cooldown = 0

    # Gaze calibration offsets (simplified)
    gaze_smoothing = 0.25 # Increased for higher eye responsiveness
    eye_x, eye_y = SCREEN_W // 2, SCREEN_H // 2 # Current gaze position
    gaze_multiplier = 4.0 # Increased to allow eye movements to cover more screen area
    
    # Hybrid Control State
    hand_priority = 0.0 # 0.0 = Gaze only, 1.0 = Hand only
    last_hand_raw = None
    
    # Transition & Mode State
    was_hand_active = False
    hand_offset_x, hand_offset_y = 0.0, 0.0
    eye_offset_x, eye_offset_y = 0.0, 0.0
    manual_override_until = 0

    # Variables for hand gesture detection
    itip = None
    ttip = None
    mtip = None
    rtip = None
    frame_timestamp_ms = 0
    
    while cap.isOpened():
        success, frame = cap.read()
        if not success:
            time.sleep(0.1)
            continue

        # Detect manual mouse movement (User Priority Override)
        mx, my = pyautogui.position()
        if abs(mx - prev_x) > 15 or abs(my - prev_y) > 15:
            manual_override_until = time.time() + 2.0 

        global TRACKING_READY
        if not TRACKING_READY:
            print("--- [ACTUATOR] Ocular Feed Active ---")
            TRACKING_READY = True
            
        frame = cv2.flip(frame, 1)
        h, w, _ = frame.shape
        rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        
        # Initialize current frame coordinates to previous position
        curr_x, curr_y = prev_x, prev_y

        # Process Hands first to determine priority
        # Convert to MediaPipe Image format for new API
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)
        frame_timestamp_ms += 1
        hand_results = hand_detector.detect_for_video(mp_image, frame_timestamp_ms)
        face_results = None

        target_x_from_gaze = None
        target_y_from_gaze = None

        # 1. EYE TRACKING LOGIC (Gaze Targeting) - Only processed if no hand is detected
        if not hand_results.hand_landmarks:
            face_results = face_landmarker.detect_for_video(mp_image, frame_timestamp_ms)

        if face_results and face_results.face_landmarks:
            # Get the first face detected
            face_landmarks = face_results.face_landmarks[0]
            
            # Access landmark directly by index (no .landmark attribute)
            left_iris = face_landmarks[468]
            right_iris = face_landmarks[473]
            
            # Calculate center point of both irises
            center_x_norm = (left_iris.x + right_iris.x) / 2
            center_y_norm = (left_iris.y + right_iris.y) / 2

            # Amplify movement from center (0.5) to allow full screen coverage
            offset_x = (center_x_norm - 0.5) * gaze_multiplier
            offset_y = (center_y_norm - 0.5) * gaze_multiplier
            
            target_x_from_gaze = np.clip((0.5 + offset_x) * SCREEN_W, 0, SCREEN_W)
            target_y_from_gaze = np.clip((0.5 + offset_y) * SCREEN_H, 0, SCREEN_H)
            
            eye_x = eye_x + (target_x_from_gaze - eye_x) * gaze_smoothing
            eye_y = eye_y + (target_y_from_gaze - eye_y) * gaze_smoothing
            
            # WINK DETECTION (Only active if no hand is detected)
            if not hand_results.hand_landmarks and click_cooldown == 0:
                # Left eye landmarks (159 top, 145 bottom), Right eye (386 top, 374 bottom)
                left_open = abs(face_landmarks[159].y - face_landmarks[145].y)
                right_open = abs(face_landmarks[386].y - face_landmarks[374].y)
                
                # Threshold for a wink: closed eye < 0.016, other eye remains open > 0.022
                if left_open < 0.016 and right_open > 0.022:
                    try: 
                        sound_manager.play_sound_async(SoundEvent.CLICK)
                        pyautogui.click()
                    except pyautogui.FailSafeException: pass
                    click_cooldown = 25
                    cv2.putText(frame, "LEFT WINK: CLICK", (w//2 - 50, h - 50), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)
                elif right_open < 0.016 and left_open > 0.022:
                    try: 
                        sound_manager.play_sound_async(SoundEvent.CLICK)
                        pyautogui.rightClick()
                    except pyautogui.FailSafeException: pass
                    click_cooldown = 25
                    cv2.putText(frame, "RIGHT WINK: R-CLICK", (w//2 - 50, h - 50), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)

            cv2.circle(frame, (int(center_x_norm*w), int(center_y_norm*h)), 5, (0, 255, 255), -1)
            cv2.putText(frame, "GAZE ACTIVE", (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)

        # 2. HAND TRACKING & MODE SWITCHING
        if hand_results.hand_landmarks:
            # Transition: Switch from Eye to Hand
            if not was_hand_active:
                itip_raw = hand_results.hand_landmarks[0][8]
                # Reset tracking to start from screen center (calibration point)
                prev_x, prev_y = SCREEN_W // 2, SCREEN_H // 2
                hand_offset_x = prev_x - (itip_raw.x * SCREEN_W)
                hand_offset_y = prev_y - (itip_raw.y * SCREEN_H)
                was_hand_active = True

            for hand_landmarks in hand_results.hand_landmarks:
                itip = hand_landmarks[8]
                mtip = hand_landmarks[12]
                rtip = hand_landmarks[16]
                ttip = hand_landmarks[4]
                
                # Use hand position plus the transition offset
                target_x_from_hand = (itip.x * SCREEN_W) + hand_offset_x
                target_y_from_hand = (itip.y * SCREEN_H) + hand_offset_y

                curr_x = prev_x + (target_x_from_hand - prev_x) * smooth_factor
                curr_y = prev_y + (target_y_from_hand - prev_y) * smooth_factor
                cv2.putText(frame, "HAND PRIORITY", (10, 60), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)

                # 1. LEFT CLICK (Index + Thumb quick pinch)
                dist_index = np.sqrt((itip.x - ttip.x)**2 + (itip.y - ttip.y)**2)
                if dist_index < 0.04 and click_cooldown == 0:
                    if not is_dragging:
                        try: 
                            sound_manager.play_sound_async(SoundEvent.CLICK)
                            pyautogui.click()
                        except pyautogui.FailSafeException: pass
                        click_cooldown = 20
                        cv2.circle(frame, (int(itip.x*w), int(itip.y*h)), 20, (0, 255, 255), -1)
                elif dist_index < 0.04:
                    if not is_dragging:
                        try: pyautogui.mouseDown()
                        except pyautogui.FailSafeException: pass
                        is_dragging = True
                    cv2.circle(frame, (int(itip.x*w), int(itip.y*h)), 20, (0, 255, 0), -1)
                else:
                    if is_dragging:
                        try: pyautogui.mouseUp()
                        except pyautogui.FailSafeException: pass
                        is_dragging = False

                # 2. RIGHT CLICK (Middle + Thumb)
                dist_middle = np.sqrt((mtip.x - ttip.x)**2 + (mtip.y - ttip.y)**2)
                if dist_middle < 0.04 and click_cooldown == 0:
                    try: 
                        sound_manager.play_sound_async(SoundEvent.CLICK)
                        pyautogui.rightClick()
                    except pyautogui.FailSafeException: pass
                    click_cooldown = 20
                    cv2.circle(frame, (int(mtip.x*w), int(mtip.y*h)), 30, (0, 0, 255), -1)
                # 3. SCROLLING (Ring + Thumb)
                dist_ring = np.sqrt((rtip.x - ttip.x)**2 + (rtip.y - ttip.y)**2)
                if dist_ring < 0.04:
                    is_scrolling = True
                    # For simplicity, scroll down a fixed amount when gesture is active
                    try: pyautogui.scroll(-10) 
                    except pyautogui.FailSafeException: pass
                    cv2.circle(frame, (int(rtip.x*w), int(rtip.y*h)), 20, (255, 0, 255), -1)
                else:
                    is_scrolling = False

                # HUD Rendering
                cx, cy = int(itip.x * w), int(itip.y * h)
                hud_color = (0, 255, 255)
                if is_dragging: hud_color = (0, 255, 0)
                if is_scrolling: hud_color = (255, 0, 255)
                
                cv2.circle(frame, (cx, cy), 15, hud_color, 2)
                cv2.putText(frame, f"HAND ACTIVE", (cx + 20, cy), 
                            cv2.FONT_HERSHEY_SIMPLEX, 0.5, hud_color, 1)
                # Draw hand connections manually for new API
                # Manual HAND_CONNECTIONS for MediaPipe Tasks
                HAND_CONNECTIONS = [
                    (0, 1), (1, 2), (2, 3), (3, 4),    # Thumb
                    (0, 5), (5, 6), (6, 7), (7, 8),    # Index
                    (0, 9), (9, 10), (10, 11), (11, 12), # Middle
                    (0, 13), (13, 14), (14, 15), (15, 16), # Ring
                    (0, 17), (17, 18), (18, 19), (19, 20), # Pinky
                    (5, 9), (9, 13), (13, 17)          # Palm
                ]
                for connection in HAND_CONNECTIONS:
                    start_idx, end_idx = connection
                    start_point = (int(hand_landmarks[start_idx].x * w), int(hand_landmarks[start_idx].y * h))
                    end_point = (int(hand_landmarks[end_idx].x * w), int(hand_landmarks[end_idx].y * h))
                    cv2.line(frame, start_point, end_point, (255, 255, 255), 2)
        else:
            # Transition: Switch from Hand to Eye
            if was_hand_active:
                if target_x_from_gaze is not None:
                    # Reset tracking to start from screen center (calibration point)
                    prev_x, prev_y = SCREEN_W // 2, SCREEN_H // 2
                    eye_offset_x = prev_x - eye_x
                    eye_offset_y = prev_y - eye_y
                was_hand_active = False
            
            # Eye movement control
            if target_x_from_gaze is not None:
                # Movement starts from last hand position
                target_x = eye_x + eye_offset_x
                target_y = eye_y + eye_offset_y
                curr_x = prev_x + (target_x - prev_x) * smooth_factor
                curr_y = prev_y + (target_y - prev_y) * smooth_factor

        # Execute physical cursor move only if user isn't using hardware mouse
        if time.time() > manual_override_until:
            if (face_results and face_results.face_landmarks) or hand_results.hand_landmarks:
                try:
                    pyautogui.moveTo(int(curr_x), int(curr_y))
                except pyautogui.FailSafeException: pass
            prev_x, prev_y = curr_x, curr_y
        else:
            # Sync AI state with physical mouse position to prevent snapping back
            prev_x, prev_y = mx, my
            eye_x, eye_y = mx, my
            # Display override status on HUD
            cv2.putText(frame, "MANUAL OVERRIDE", (w//2 - 80, 30), 
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)

        # Global cooldown decrement to ensure winks work repeatedly without hands
        if click_cooldown > 0: 
            click_cooldown -= 1

        # Update the shared frame for the Java UI stream
        with _frame_lock:
            global _processed_frame
            _processed_frame = frame.copy()

if __name__ == "__main__":
    check_port_lock(8084)
    
    print("""
    =========================================
     JARVIS ACTUATOR SERVICE (REPULSORS)
    Listening on: http://127.0.0.1:8084
    =========================================
    """)
    # Start Vision in background
    threading.Thread(target=start_hand_tracking, daemon=True).start()
    report_status("REPULSORS", "READY", "Ocular Tracking Engine Online")
    
    # Start FastAPI server
    uvicorn.run(app, host="127.0.0.1", port=8084, log_level="info")