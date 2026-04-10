import requests
import os
import time

JAVA_BACKEND_URL = "http://127.0.0.1:8082/jarvis"
MOUTH_URL = "http://127.0.0.1:8880/health"

def report_status(module_name: str, status: str, message: str = ""):
    """Centralized status reporting to Java Controller"""
    try:
        payload = {"module": module_name, "status": status, "message": message}
        requests.post(f"{JAVA_BACKEND_URL}/status", json=payload, timeout=0.5)
    except: pass

def is_mouth_speaking() -> bool:
    """Check if the mouth module is currently active (AEC Logic)"""
    try:
        # If Mouth service returns a metric showing CPU/GPU activity, assume it's speaking
        response = requests.get(MOUTH_URL, timeout=0.2)
        if response.status_code == 200:
            # Simplistic check: If mouth is 'busy' or has high activity
            return response.json().get("status") == "BUSY"
    except: pass
    return False