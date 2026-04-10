import os

import sys

import multiprocessing

import torch

import threading

import time

import soundfile as sf

import re

import psutil

import requests

from fastapi import FastAPI, HTTPException

from pydantic import BaseModel

import uvicorn

from kokoro_onnx import Kokoro

import sounddevice as sd



# --- CONFIGURATION ---

# This finds the folder where the script actually lives

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))



# Define the missing variable

VOICES_DIR = os.path.join(SCRIPT_DIR, "kokoro", "hf_cache", "voices")

OUTPUT_FILE = os.path.join(SCRIPT_DIR, "reply.wav")



# Ensure working directory is set to script location for relative path resolution

os.chdir(SCRIPT_DIR)



# Force UTF-8 for Windows 11 console

sys.stdout.reconfigure(encoding='utf-8')



app = FastAPI(title="Jarvis Mouth Engine")



try:

    import pynvml

    pynvml.nvmlInit()

except:

    pynvml = None



_proc = psutil.Process(os.getpid())

try:

    # Set process to High Priority

    if sys.platform == 'win32':

        _proc.nice(psutil.HIGH_PRIORITY_CLASS)

except Exception as e: print(f" [SYSTEM] Could not set high priority: {e}")

_proc.cpu_percent() # Seed



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

    # cpu_percent(interval=None) is non-blocking and provides usage since last call

    return {

        "cpu": _proc.cpu_percent(interval=None),

        "ram_mb": _proc.memory_info().rss / (1024 * 1024),

        "gpu": gpu_val,

        "vram_mb": vram_val

    }



def report_status(status, detail=""):

    """Proactively report status to Java Controller"""

    try:

        java_status_url = "http://127.0.0.1:8082/jarvis/status"

        payload = {"module": "VOCAL_MOUTH", "status": status, "message": detail}

        requests.post(java_status_url, json=payload, timeout=1)

    except:

        pass



# --- MODEL INITIALIZATION ---

# Initialize Kokoro with ONNX (DirectML/CUDA support)

# It will automatically download small model files if missing

try:

    kokoro = Kokoro("kokoro-v1.0.onnx", "voices.json")

    print(" [SYSTEM] Mouth Engine Online (Kokoro ONNX)")

    report_status("READY", "Mouth Engine Online (Kokoro ONNX)")

    

except Exception as e:

    print(f" [CRITICAL] Failed to initialize Kokoro ONNX: {e}")

    report_status("ERROR", f"Initialization failed: {str(e)}")

    # Emergency fallback - create dummy kokoro object

    kokoro = None

    report_status("DEGRADED", "Kokoro ONNX unavailable")



class SpeechRequest(BaseModel):

    input: str

    voice: str = "af_heart" # Default high-quality female voice

    speed: float = 1.0



@app.post("/v1/audio/speech")

async def generate_speech(request: SpeechRequest):

    try:

        if kokoro is None:

            raise HTTPException(status_code=503, detail="Kokoro ONNX not available")

            

        # Sanitize input: Strip whitespace and check for empty strings

        text = request.input.strip()

        if not text:

            return {"status": "success", "file": None, "message": "Input was empty or only whitespace"}



        # Remove emojis and non-ASCII characters for stability

        text = re.sub(r'[^\x00-\x7F]+', '', text)

        text = re.sub(r'\s+', ' ', text).strip()

        if not text:

            return {"status": "success", "file": None, "message": "Input contained only unsupported characters"}



        print(f" [JARVIS] Generating speech for: {text[:50]}...")



        # Use Kokoro ONNX API - much simpler and more stable

        try:

            samples, sample_rate = kokoro.create(text, voice=request.voice, speed=request.speed, lang="en-us")

            

            # Save to the standard output file

            sf.write(OUTPUT_FILE, samples, sample_rate)

            print(f" [SUCCESS] Speech generated successfully: {len(samples)} samples at {sample_rate}Hz")

            

            return {"status": "success", "file": OUTPUT_FILE}

            

        except Exception as kokoro_error:

            print(f" [KOKORO ERROR] {kokoro_error}")

            raise HTTPException(status_code=500, detail=f"Kokoro ONNX generation failed: {str(kokoro_error)}")



    except Exception as e:

        print(f" [ERROR] Generation failed: {e}")

        raise HTTPException(status_code=500, detail=str(e))



@app.get("/health")

async def health_check():

    return {"status": "ready", "device": "cuda" if torch.cuda.is_available() else "cpu", "metrics": get_process_metrics()}



@app.post("/shutdown")

async def shutdown():

    report_status("OFFLINE", "Shutdown requested via API")

    def delayed_exit():

        time.sleep(1)

        os._exit(0)

    threading.Thread(target=delayed_exit, daemon=True).start()

    return {"status": "shutting down"}



def run_with_retry():

    retry_count = 0

    max_retries = 3

    

    while retry_count < max_retries:

        try:

            # Ensure environment variables are set before starting the server

            os.environ["TOKENIZERS_PARALLELISM"] = "false"

            

            # Check if port is available

            print(f"📡 [MOUTH] Attempting to bind to 127.0.0.1:8880...")

            

            # Initialize your model

            # Standard port for Jarvis communication

            uvicorn.run(app, host="127.0.0.1", port=8880)

            break  # If successful, break the loop

            

        except RuntimeError as e:

            if "CUDA failed with error out of memory" in str(e):

                print(f"\n CUDA out of memory error in mouth service (attempt {retry_count + 1}/{max_retries})")

                print(" Restarting mouth service to free memory...")

                retry_count += 1

                

                # Clear CUDA cache

                try:

                    import torch

                    if torch.cuda.is_available():

                        torch.cuda.empty_cache()

                        print(" CUDA cache cleared")

                except:

                    pass

                

                # Wait before restart

                import time

                time.sleep(3)

                

                print(f" Mouth service restarting (attempt {retry_count})")

                continue

            else:

                print(f" Unexpected error in mouth service: {e}")

                raise

        except Exception as e:

            print(f" Unexpected error in mouth service: {e}")

            raise

    

    print(f" Max retries ({max_retries}) reached. Shutting down mouth service...")

    report_status("OFFLINE", "Max retries reached")



if __name__ == "__main__":

    multiprocessing.freeze_support()

    report_status("STARTING", "Initializing Mouth Engine")

    run_with_retry()