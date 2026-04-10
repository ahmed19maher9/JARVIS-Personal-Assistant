import tkinter as tk

import sys

import pyautogui

import cv2

import json

import os

import threading

from fastapi import FastAPI

from fastapi.responses import StreamingResponse

import uvicorn

import time

import psutil

import numpy as np



# Force dlib/face_recognition to run on CPU. This avoids crashes on some CUDA/cuDNN setups.

os.environ.setdefault("CUDA_VISIBLE_DEVICES", "-1")



face_recognition = None

FACE_REC_AVAILABLE = False

FACE_REC_ENABLED = os.environ.get("JARVIS_ENABLE_FACE_RECOGNITION", "0") == "1"



def _ensure_face_recognition_loaded():

    global face_recognition, FACE_REC_AVAILABLE

    if not FACE_REC_ENABLED:

        return False

    if FACE_REC_AVAILABLE and face_recognition is not None:

        return True

    try:

        import face_recognition as _fr

        face_recognition = _fr

        FACE_REC_AVAILABLE = True

        return True

    except Exception as e:

        print(f" [SYSTEM] face_recognition unavailable: {e}")

        face_recognition = None

        FACE_REC_AVAILABLE = False

        return False



_FACE_CASCADE = None



def _get_face_cascade():

    global _FACE_CASCADE

    if _FACE_CASCADE is not None:

        return _FACE_CASCADE

    try:

        cascade_path = os.path.join(cv2.data.haarcascades, "haarcascade_frontalface_default.xml")

        _FACE_CASCADE = cv2.CascadeClassifier(cascade_path)

    except Exception:

        _FACE_CASCADE = None

    return _FACE_CASCADE



insightface = None

INSIGHT_AVAILABLE = False

INSIGHT_ENABLED = os.environ.get("JARVIS_ENABLE_INSIGHTFACE", "1") == "1"

_insight_app = None



def _ensure_insight_loaded():

    global insightface, INSIGHT_AVAILABLE, _insight_app

    if not INSIGHT_ENABLED:

        return False

    if INSIGHT_AVAILABLE and _insight_app is not None:

        return True

    try:

        import insightface as _if

        from insightface.app import FaceAnalysis

        insightface = _if

        _insight_app = FaceAnalysis(providers=["CPUExecutionProvider"])

        _insight_app.prepare(ctx_id=-1, det_size=(320, 320))

        INSIGHT_AVAILABLE = True

        return True

    except Exception as e:

        print(f" [SYSTEM] insightface unavailable: {e}")

        insightface = None

        _insight_app = None

        INSIGHT_AVAILABLE = False

        return False



import re

import requests

from PIL import Image

try:

    from ultralytics import YOLO

    # Use YOLOv26 Nano - extremely fast and accurate for real-time

    yolo_model = YOLO("yolo26n.pt") 

except ImportError:

    print(" [SYSTEM] Ultralytics not found. Run: pip install ultralytics")

    yolo_model = None

try:

    import pynvml

    pynvml.nvmlInit()

except:

    pynvml = None



# Suppress OpenCV backend warnings

os.environ["OPENCV_VIDEOIO_PRIORITY_MSMF"] = "0"



# --- GLOBAL INSTANCE ---

eye_instance = None

current_detections = []

detections_lock = threading.Lock()

_shared_frame = None

_is_analyzing = False

_frame_lock = threading.Lock()



# --- FACE RECOGNITION DATABASE ---

known_face_encodings = []

known_face_names = []

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

FACE_DIR = os.path.join(SCRIPT_DIR, "resources", "faces")



def load_known_faces():

    known_face_encodings.clear()

    known_face_names.clear()

    if not os.path.exists(FACE_DIR):

        os.makedirs(FACE_DIR, exist_ok=True)

        print(f" [OCULAR] Created face database at {FACE_DIR}", flush=True)

        return



    print(" [OCULAR] Loading known faces...", flush=True)

    use_insight = _ensure_insight_loaded()

    use_fr = (not use_insight) and _ensure_face_recognition_loaded()

    for filename in os.listdir(FACE_DIR):

        if filename.endswith((".jpg", ".png", ".jpeg")):

            path = os.path.join(FACE_DIR, filename)

            try:

                if use_insight:

                    img = cv2.imread(path)

                    if img is None:

                        raise ValueError("Failed to read image")

                    faces = _insight_app.get(img)

                    if faces:

                        faces.sort(key=lambda f: float((f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1])), reverse=True)

                        emb = np.asarray(faces[0].embedding, dtype=np.float32)

                        norm = float(np.linalg.norm(emb))

                        if norm > 0:

                            emb = emb / norm

                        known_face_encodings.append(emb)

                        known_face_names.append(os.path.splitext(filename)[0])

                        print(f"  - Identity Encoded: {os.path.splitext(filename)[0]}", flush=True)

                elif use_fr:

                    image = face_recognition.load_image_file(path)

                    encodings = face_recognition.face_encodings(image)

                    if encodings:

                        known_face_encodings.append(encodings[0])

                        known_face_names.append(os.path.splitext(filename)[0])

                        print(f"  - Identity Encoded: {os.path.splitext(filename)[0]}", flush=True)

            except Exception as e:

                print(f"  - Failed to encode {filename}: {e}", flush=True)



_proc = psutil.Process(os.getpid())

try:

    # Set process to High Priority

    if os.name == 'nt':

        _proc.nice(psutil.HIGH_PRIORITY_CLASS)

except Exception as e: print(f" [SYSTEM] Could not set high priority: {e}")

_proc.cpu_percent()



def get_process_metrics():

    gpu_val = 0.0

    if pynvml:

        try:

            handle = pynvml.nvmlDeviceGetHandleByIndex(0)

            gpu_val = float(pynvml.nvmlDeviceGetUtilizationRates(handle).gpu)

        except: pass

    return {

        "cpu": _proc.cpu_percent(),

        "ram_mb": _proc.memory_info().rss / (1024 * 1024),

        "gpu": gpu_val

    }



def list_cameras():

    """Probes system for available camera indices"""

    available_cams = []

    print(" [SYSTEM] Probing for camera hardware...", flush=True)

    # Check first 5 indices

    for i in range(5):

        print(f"  - Testing index {i}...", end=" ", flush=True)

        # Use DirectShow backend for faster hardware enumeration on Windows

        cap = cv2.VideoCapture(i, cv2.CAP_DSHOW)

        if cap.isOpened():

            ret, _ = cap.read()

            if ret:

                available_cams.append(i)

                print("FOUND ", flush=True)

            cap.release()

        else: print("SKIP ", flush=True)

    return available_cams



# --- HEALTH CHECK SERVER ---

# This allows the Java UI to see that the Eye module script is active

app_health = FastAPI()

@app_health.get("/health")

async def health():

    return {"status": "ready", "module": "eye", "metrics": get_process_metrics()}



@app_health.post("/capture")

async def trigger_capture():

    # Trigger the selection UI in a separate thread so the API stays responsive

    threading.Thread(target=lambda: JarvisEye().root.mainloop()).start()

    return {"status": "selection started"}



@app_health.post("/shutdown")

async def shutdown():

    if eye_instance:

        eye_instance.root.after(0, eye_instance.root.destroy)

    threading.Thread(target=lambda: (time.sleep(1), os._exit(0)), daemon=True).start()

    return {"status": "shutting down"}



@app_health.get("/faces")

async def list_faces():

    return {

        "available": bool(INSIGHT_AVAILABLE or FACE_REC_AVAILABLE),

        "backend": "insightface" if INSIGHT_AVAILABLE else ("face_recognition" if FACE_REC_AVAILABLE else "opencv"),

        "insightface_enabled": bool(INSIGHT_ENABLED),

        "insightface_available": bool(INSIGHT_AVAILABLE),

        "face_recognition_enabled": bool(FACE_REC_ENABLED),

        "face_recognition_available": bool(FACE_REC_AVAILABLE),

        "face_dir": FACE_DIR,

        "known": list(known_face_names),

        "count": len(known_face_names),

    }



@app_health.post("/faces/reload")

async def reload_faces():

    load_known_faces()

    return {"status": "reloaded", "count": len(known_face_names)}



@app_health.get("/video_feed")

async def video_feed():

    def gen_frames():

        while True:

            with _frame_lock:

                if _shared_frame is None:

                    time.sleep(0.1)

                    continue

                ret, buffer = cv2.imencode('.jpg', _shared_frame)

                frame_bytes = buffer.tobytes()

            yield (b'--frame\r\n'

                   b'Content-Type: image/jpeg\r\n\r\n' + frame_bytes + b'\r\n')

            time.sleep(0.04)

    return StreamingResponse(gen_frames(), media_type="multipart/x-mixed-replace; boundary=frame")



def run_health_server():

    # Using port 8882 for Eye Service

    uvicorn.run(app_health, host="127.0.0.1", port=8882, log_level="error")



def send_target_to_java(label):

    """Notify Java backend of the detected target"""

    try:

        requests.post("http://127.0.0.1:8082/jarvis/target", 

                     json={"target": label}, timeout=0.5)

    except: pass



def analysis_worker():

    """The Analysis Layer: Optimized for YOLO Real-time Tracking"""

    global current_detections, _shared_frame, _is_analyzing

    print(" [OCULAR] Analysis Layer Warmup: Loading YOLO Engine...", flush=True)

    if not yolo_model:

        print(" [OCULAR] YOLO model not loaded. Detection disabled.", flush=True)

        return



    print(" [OCULAR] Analysis Layer Online: Using YOLOv26 Engine...", flush=True)

    face_counter = 0

    last_face_detections = []

    

    while True:

        frame_to_analyze = None

        with _frame_lock:

            if _shared_frame is not None:

                frame_to_analyze = _shared_frame.copy()

        

        if frame_to_analyze is not None:

            _is_analyzing = True

            try:

                # Run YOLO inference with stream=True to keep VRAM clear

                results = yolo_model.predict(frame_to_analyze, conf=0.4, verbose=False, stream=True)

                

                new_detections = []

                for result in results:

                    # Get frame dimensions

                    img_h, img_w = frame_to_analyze.shape[:2]

                    

                    for box in result.boxes:

                        label = result.names[int(box.cls[0])].lower()

                        

                        # Skip generic 'person' label if we are in identity mode

                        if "person" in label and (FACE_REC_AVAILABLE and len(known_face_encodings) > 0):

                            continue

                            

                        # Get coordinates in [x1, y1, x2, y2] pixel format

                        coords = box.xyxy[0].tolist()

                        # Normalize back to 0-1000 for the existing Visual Layer logic

                        norm_box = [

                            (coords[1] / img_h) * 1000, # ymin

                            (coords[0] / img_w) * 1000, # xmin

                            (coords[3] / img_h) * 1000, # ymax

                            (coords[2] / img_w) * 1000  # xmax

                        ]

                        new_detections.append({"label": label, "box_2d": norm_box})

                

                # --- FACE RECOGNITION (Run every 5 frames for performance) ---

                if face_counter % 5 == 0:

                    current_faces = []

                    try:

                        # Resize for faster processing

                        small_frame = cv2.resize(frame_to_analyze, (0, 0), fx=0.25, fy=0.25)

                        if _ensure_insight_loaded():

                            faces = _insight_app.get(small_frame)

                            for f in faces:

                                x1, y1, x2, y2 = [int(v) for v in f.bbox]

                                name = "FACE"

                                if known_face_encodings:

                                    emb = np.asarray(f.embedding, dtype=np.float32)

                                    norm = float(np.linalg.norm(emb))

                                    if norm > 0:

                                        emb = emb / norm

                                    sims = np.dot(np.vstack(known_face_encodings), emb)

                                    best_idx = int(np.argmax(sims))

                                    best_sim = float(sims[best_idx])

                                    name = "UNKNOWN"

                                    if best_sim >= 0.45:

                                        name = known_face_names[best_idx]

                                norm_face = [(y1*4/img_h)*1000, (x1*4/img_w)*1000, (y2*4/img_h)*1000, (x2*4/img_w)*1000]

                                current_faces.append({"label": f"ID: {name}", "box_2d": norm_face})

                        elif _ensure_face_recognition_loaded():

                            rgb_small_frame = cv2.cvtColor(small_frame, cv2.COLOR_BGR2RGB)

                            face_locations = face_recognition.face_locations(rgb_small_frame)

                            face_encodings = face_recognition.face_encodings(rgb_small_frame, face_locations)



                            for (top, right, bottom, left), face_encoding in zip(face_locations, face_encodings):

                                name = "FACE"

                                if known_face_encodings:

                                    matches = face_recognition.compare_faces(known_face_encodings, face_encoding, tolerance=0.55)

                                    name = "UNKNOWN"

                                    face_distances = face_recognition.face_distance(known_face_encodings, face_encoding)

                                    if len(face_distances) > 0:

                                        best_match_index = np.argmin(face_distances)

                                        if matches[best_match_index]:

                                            name = known_face_names[best_match_index]

                                norm_face = [(top*4/img_h)*1000, (left*4/img_w)*1000, (bottom*4/img_h)*1000, (right*4/img_w)*1000]

                                current_faces.append({"label": f"ID: {name}", "box_2d": norm_face})

                        else:

                            cascade = _get_face_cascade()

                            if cascade is not None:

                                gray = cv2.cvtColor(small_frame, cv2.COLOR_BGR2GRAY)

                                faces = cascade.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=5, minSize=(30, 30))

                                for (x, y, w0, h0) in faces:

                                    top, left = int(y), int(x)

                                    bottom, right = int(y + h0), int(x + w0)

                                    norm_face = [(top*4/img_h)*1000, (left*4/img_w)*1000, (bottom*4/img_h)*1000, (right*4/img_w)*1000]

                                    current_faces.append({"label": "FACE", "box_2d": norm_face})

                        last_face_detections = current_faces

                    except Exception as fe:

                        print(f" [OCULAR] Face Rec Internal Error: {fe}", flush=True)



                # Prepend face IDs so they appear first in the list and status bar

                new_detections = last_face_detections + new_detections

                face_counter += 1

                

                with detections_lock:

                    current_detections = new_detections

                    if current_detections:

                        send_target_to_java(current_detections[0]['label'])



            except Exception as e:

                print(f"⚠️ [OCULAR] YOLO Error: {e}", flush=True)

            finally:

                _is_analyzing = False

        

        # Tight loop for near real-time performance

        time.sleep(0.01) 



def start_camera_feed(cam_index):

    """Opens a window showing the live camera feed"""

    print(f"🎬 [OCULAR] Thread Active: Attempting to link camera hardware at index {cam_index}...", flush=True)

    # Use DirectShow backend and set low buffer size to minimize lag

    cap = cv2.VideoCapture(cam_index, cv2.CAP_DSHOW)

    if not cap.isOpened():

        print(f"❌ [ERROR] Failed to open camera at index {cam_index}", flush=True)

        return



    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)  # Force buffer to 1 frame to prevent "old" video lag

    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)  # Standardize resolution for speed

    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)



    print(f"⚙️ [OCULAR] Camera stream opened. Synchronizing hardware properties...", flush=True)

    w = cap.get(cv2.CAP_PROP_FRAME_WIDTH)

    h = cap.get(cv2.CAP_PROP_FRAME_HEIGHT)

    fps = cap.get(cv2.CAP_PROP_FPS)

    print(f"📸 [OCULAR] Hardware Linked: {int(w)}x{int(h)} @ {fps} FPS", flush=True)

    

    # Start the Analysis Layer in the background

    threading.Thread(target=analysis_worker, daemon=True).start()

    

    print(f"🎬 [OCULAR] Starting Feed (Asynchronous Augmentation active)...", flush=True)

    

    first_frame = True

    while True:

        ret, frame = cap.read()

        if not ret:

            print("❌ [OCULAR] Failed to grab frame from hardware.", flush=True)

            break

            

        if first_frame:

            print(f"✨ [OCULAR] Stream synchronized. Rendering window...", flush=True)

            first_frame = False



        h, w, _ = frame.shape

        

        # --- VISUAL LAYER: High Speed Rendering ---

        with detections_lock:

            for obj in current_detections:

                try:

                    # Convert normalized coordinates to pixel coordinates

                    box = obj.get('box_2d')

                    if box and len(box) == 4:

                        ymin, xmin, ymax, xmax = [float(v) for v in box]

                        start_point = (int(xmin * w / 1000), int(ymin * h / 1000))

                        end_point = (int(xmax * w / 1000), int(ymax * h / 1000))

                        

                        # Cyberpunk Style: Cyan box

                        cv2.rectangle(frame, start_point, end_point, (255, 251, 0), 2)

                        

                        # Label Background

                        label = str(obj.get('label', 'UNK')).upper()

                        cv2.rectangle(frame, (start_point[0], start_point[1] - 25), 

                                     (start_point[0] + (len(label) * 12), start_point[1]), (255, 251, 0), -1)

                        

                        # Label Text

                        cv2.putText(frame, label, (start_point[0] + 5, start_point[1] - 7),

                                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 0), 2)

                except:

                    continue



        # Add scanning HUD effect

        scan_y = int((time.time() * 100) % h)

        cv2.line(frame, (0, scan_y), (w, scan_y), (255, 251, 0), 1)

        

        # Show status

        if _is_analyzing:

            cv2.putText(frame, "BRAIN ACTIVE...", (10, 30), 

                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 251, 0), 2)



        # Update shared buffer for the streaming endpoint

        with _frame_lock:

            global _shared_frame

            _shared_frame = frame.copy()

        

    cap.release()

    cv2.destroyAllWindows()



if __name__ == "__main__":

    print("\n--- OCULAR INITIALIZATION ---")

    

    # Load faces before starting feed

    load_known_faces()

    

    cameras = list_cameras()

    if cameras:

        # Prioritize camera index 1 as requested to avoid conflict with Actuator

        cam_id = 1 if 1 in cameras else cameras[0]

        if len(cameras) > 1:

            print(f"✅ [SYSTEM] Found multiple cameras: {cameras}")

            print(f"Enter camera index to use for Ocular Feed (Default {cam_id} in 3s): ", end='', flush=True)

            

            choice = [str(cam_id)]

            def get_input():

                try:

                    if sys.stdin:

                        val = sys.stdin.readline().strip()

                        if val: choice[0] = val

                except: pass

            input_thread = threading.Thread(target=get_input, daemon=True)

            input_thread.start()

            input_thread.join(3) # Wait only 3 seconds for automation compatibility

            cam_id = int(choice[0])

            

        print(f"🚀 [OCULAR] Spawning background feed for camera index {cam_id}...")

        threading.Thread(target=start_camera_feed, args=(cam_id,), daemon=True).start()

    else:

        print("⚠️ No cameras detected.")

    

    print("🚀 Jarvis Eye Service Online on port 8882")

    run_health_server()