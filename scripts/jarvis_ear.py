import os
os.environ["KMP_DUPLICATE_LIB_OK"] = "TRUE"
import sys
import site
import json
import threading
import time
import numpy as np
import pyaudio
import requests
import pygame
import psutil
from jarvis_utils import report_status, is_mouth_speaking
try:
    import pynvml
    pynvml.nvmlInit()
except:
    pynvml = None

# Import Jarvis sound manager
from jarvis_sound_manager import get_sound_manager, SoundEvent

_proc = psutil.Process(os.getpid())
try:
    # Set process to High Priority
    if sys.platform == 'win32':
        _proc.nice(psutil.HIGH_PRIORITY_CLASS)
except Exception as e: print(f"⚠️ [SYSTEM] Could not set high priority: {e}")
_proc.cpu_percent() # Seed the initial counter

def get_process_metrics():
    gpu_val = 0.0
    vram_val = 0.0
    if pynvml:
        try:
            handle = pynvml.nvmlDeviceGetHandleByIndex(0)
            util = pynvml.nvmlDeviceGetUtilizationRates(handle)
            gpu_val = float(util.gpu)
            mem = pynvml.nvmlDeviceGetMemoryInfo(handle)
            vram_val = mem.used / (1024 * 1024)  # Convert to MB
        except: pass
    return {
        "cpu": _proc.cpu_percent(),
        "ram_mb": _proc.memory_info().rss / (1024 * 1024),
        "gpu": gpu_val,
        "vram_mb": vram_val
    }


# --- FORCED DLL INJECTION ---
def fix_nvidia_dlls():
    # Find all site-packages
    try:
        paths = site.getsitepackages()
    except:
        paths = [os.path.join(sys.prefix, "Lib", "site-packages")]

    found = False
    for path in paths:
        # The specific 'bin' folders containing the .dll files
        cublas_bin = os.path.join(path, "nvidia", "cublas", "bin")
        cudnn_bin = os.path.join(path, "nvidia", "cudnn", "bin")
        
        if os.path.exists(cublas_bin):
            print(f" [SYSTEM] Manually linking: {cublas_bin}")
            # This is the magic line for Windows 11 / Python 3.8+
            os.add_dll_directory(cublas_bin)
            os.environ["PATH"] = cublas_bin + os.pathsep + os.environ["PATH"]
            found = True
            
        if os.path.exists(cudnn_bin):
            os.add_dll_directory(cudnn_bin)
            os.environ["PATH"] = cudnn_bin + os.pathsep + os.environ["PATH"]

    if not found:
        print("⚠️ [WARNING] NVIDIA DLL folders not found in site-packages.")

fix_nvidia_dlls()
# ---------------------------

# Import heavy AI libraries after setting path/DLL logic
from faster_whisper import WhisperModel
from openwakeword.model import Model
from fastapi import FastAPI
import uvicorn

# --- GLOBAL STATE ---
RUNNING = True


# --- HEALTH CHECK SERVER ---
app_health = FastAPI()
@app_health.get("/health")
async def health():
    return {"status": "ready", "module": "ears", "metrics": get_process_metrics()}

@app_health.post("/shutdown")
async def shutdown():
    global RUNNING
    RUNNING = False
    print(" [SYSTEM] Shutdown signal received. Freeing resources...")
    def delayed_exit():
        time.sleep(1.5)
        os._exit(0)
    threading.Thread(target=delayed_exit, daemon=True).start()
    return {"status": "shutting down"}

def run_health_server():
    uvicorn.run(app_health, host="127.0.0.1", port=8881, log_level="error")

threading.Thread(target=run_health_server, daemon=True).start()

# --- PYINSTALLER PATH HELPER ---
def get_path(relative_path):
    """ Get absolute path to resource, works for dev and for PyInstaller """
    if hasattr(sys, '_MEIPASS'):
        return os.path.join(sys._MEIPASS, relative_path)
    return os.path.join(os.path.abspath("."), relative_path)

os.environ["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"

# --- CONFIGURATION ---
FORMAT = pyaudio.paInt16
CHANNELS = 1
RATE = 16000
CHUNK = 512 # Optimized: Smaller chunks reduce audio buffer lag/latency
NODE_URL = "http://127.0.0.1:3000/jarvis/event"

# Ensure we're in the correct directory for model files
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
os.chdir(SCRIPT_DIR)  # Change to script directory
print(f" Working directory: {SCRIPT_DIR}")
print(f" Script path: {os.path.abspath(__file__)}")

def send_to_jarvis(text):
    """Send transcribed text to Java JARVIS application for processing"""
    try:
        java_url = "http://127.0.0.1:8082/jarvis/command"
        payload = {
            "command": text,
            "source": "ear"
        }
        
        print(f" [JARVIS] Sending to Java: {text}")
        print(f" [JARVIS] Java URL: {java_url}")

        session = requests.Session()
        session.trust_env = False
        response = session.post(java_url, json=payload, timeout=10)
        
        if response.status_code == 200:
            print(" [JARVIS] Java application received command")
        else:
            print(f" [JARVIS] Java application error: {response.status_code}")
            try:
                print(f" [JARVIS] Response body: {response.text}")
            except Exception:
                pass
            
    except Exception as e:
        print(f" [JARVIS] Error sending to Java: {e}")

def send_to_node(event_type, content):
    try:
        payload = {"type": event_type, "content": content}
        # Send as JSON string to Node.js
        print(json.dumps(payload))  # This will be captured by Node.js
        requests.post(NODE_URL, json=payload, timeout=0.5)
    except:
        pass # Don't crash if Node is offline

def listen_and_transcribe(stt_model, stream):
    # AEC: Check if Jarvis is currently speaking before transcribing
    if is_mouth_speaking():
        print(" [AEC] Jarvis is speaking, ignoring input.")
        return

    print(" Listening to your command...")
    report_status("AURAL_EARS", "LISTENING")
    
    frames = []
    # Record for 4 seconds of command
    for _ in range(0, int(RATE / CHUNK * 4)):
        data = stream.read(CHUNK)
        frames.append(data)
    
    audio_data = np.frombuffer(b''.join(frames), np.int16).astype(np.float32) / 32768.0
    
    # Use beam_size=5 for better accuracy on the 1060
    segments, _ = stt_model.transcribe(audio_data, beam_size=5, language="en")
    text = " ".join([seg.text for seg in segments]).strip()
    
    if text:
        print(f" Transcribed: {text}")
        # Send to Java JARVIS application instead of Node.js
        send_to_jarvis(text)
    else:
        report_status("AURAL_EARS", "READY")

def main():
    print(" [SYSTEM] Initializing Jarvis Ears...")

    # 1. Initialize Whisper (Optimized for GTX 1060)
    try:
        stt_model = WhisperModel("base", device="cuda", compute_type="float16")
        print("✅ [STT] Faster-Whisper loaded on GPU (float16)")
    except Exception as e:
        print(f" [STT] GPU init failed, using CPU: {e}")
        stt_model = WhisperModel("base", device="cpu", compute_type="int8")
        report_status("DEGRADED", f"GPU Failed, using CPU: {str(e)}")

    # --- INITIALIZE SOUND MANAGER ---
    sound_manager = get_sound_manager()
    
    def play_wake_sound():
        try:
            sound_manager.play_sound_async(SoundEvent.WAKE_WORD)
        except Exception as e:
            print(f" [SOUND] Failed to play wake sound: {e}")
    # 2. Initialize Wake Word (ONNX)
    # Use just the filename for openWakeWord, not full path
    wakeword_model = Model(
        wakeword_models=["Jarvis.onnx"],  # Just the filename, openWakeWord will find it
        inference_framework="onnx"
    )

    # 3. Audio Setup
    audio = pyaudio.PyAudio()
    stream = audio.open(format=FORMAT, channels=CHANNELS, rate=RATE, 
                        input=True, frames_per_buffer=CHUNK)

    print(" Jarvis Ear is online. Say 'Jarvis'...")
    # Optimization: Final READY report only after all engines and streams are active
    report_status("READY", "Ear System fully operational and listening")

    def run_with_retry(audio, stream):
        retry_count = 0
        max_retries = 3
        
        while retry_count < max_retries and RUNNING:
            try:
                while RUNNING:
                    # Read audio chunk from microphone
                    data = stream.read(CHUNK, exception_on_overflow=False)
                    audio_frame = np.frombuffer(data, dtype=np.int16)
                    
                    # 1. Feed to model and capture the result directly
                    # This returns a dictionary of probabilities
                    prediction = wakeword_model.predict(audio_frame)
                    
                    # 2. Check the specific key "hey_jarvis" in the returned prediction
                    if prediction["Jarvis"] > 0.5:
                        print("\n Yes, I'm here")
                        # PLAY THE SOUND!
                        play_wake_sound()
                        send_to_node("event", "wake_word_detected")
                        
                        # Run STT (Speech-to-Text)
                        listen_and_transcribe(stt_model, stream)
                        
                        # Reset wake word to avoid looping the same detection
                        wakeword_model.reset()
                        print("\n Listening for 'Jarvis'...")
                        
            except RuntimeError as e:
                if "CUDA failed with error out of memory" in str(e):
                    print(f"\n CUDA out of memory error (attempt {retry_count + 1}/{max_retries})")
                    print(" Restarting ear service to free memory...")
                    retry_count += 1
                    
                    # Clean up resources
                    try:
                        if stream:
                            stream.stop_stream()
                            stream.close()
                        audio.terminate()
                    except:
                        pass
                    
                    # Wait before restart
                    import time
                    time.sleep(1)
                    
                    # Reinitialize audio
                    try:
                        audio = pyaudio.PyAudio()
                        stream = audio.open(format=pyaudio.paInt16,
                                      channels=1,
                                      rate=16000,
                                      input=True,
                                      frames_per_buffer=CHUNK)
                        print(f" Ear service restarted (attempt {retry_count})")
                        continue
                    except Exception as init_error:
                        print(f" Failed to restart audio: {init_error}")
                        retry_count += 1
                        stream = None
                        continue
                else:
                    print(f" Unexpected error: {e}")
                    raise
            except Exception as e:
                print(f" Unexpected error: {e}")
                raise
        
            print(f" Max retries ({max_retries}) reached. Shutting down ear service...")
            break
        
    try:
        run_with_retry(audio, stream)
    except KeyboardInterrupt:
        print("\n Shutting down Ears...")
        report_status("OFFLINE", "User shutdown")
    finally:
        report_status("OFFLINE", "Service Terminated")
        # Clean up
        try:
            stream.stop_stream()
            stream.close()
            audio.terminate()
        except:
            pass

if __name__ == "__main__":
    main()
