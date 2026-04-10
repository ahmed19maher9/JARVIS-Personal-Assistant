"""
JARVIS Sound Manager
Handles all sound effects and audio feedback for the JARVIS system
"""
import os
import pygame
import threading
import time
from typing import Optional, Dict
from enum import Enum

class SoundEvent(Enum):
    """Enumeration of all possible sound events"""
    STARTUP = "jarvis_intro-1"
    WAKE_WORD = "jarvis_connected"
    DISCONNECT = "jarvis_disconnected"
    OVERLOAD = "jarvis_overload"
    CONFIRMATION = "as-you-wish-sir-jarvis"
    CLICK = "click"
    BEEP = "beep"
    WHISTLE = "whistle"

class JarvisSoundManager:
    """Centralized sound management for JARVIS system"""
    
    def __init__(self, sounds_directory: str = "resources/sounds"):
        self.sounds_directory = sounds_directory
        self.sounds: Dict[str, pygame.mixer.Sound] = {}
        self.enabled = True
        self.volume = 0.7  # Default volume level
        self._initialized = False
        self._lock = threading.Lock()
        
    def initialize(self) -> bool:
        """Initialize pygame mixer and load all sound files"""
        try:
            if not self._initialized:
                pygame.mixer.init(frequency=22050, size=-16, channels=2, buffer=512)
                self._load_sounds()
                self._initialized = True
                print("[SOUND] Sound manager initialized successfully")
                return True
        except Exception as e:
            print(f"[SOUND] Failed to initialize sound manager: {e}")
            return False
        return False
    
    def _load_sounds(self):
        """Load all sound files from the sounds directory"""
        if not os.path.exists(self.sounds_directory):
            print(f"[SOUND] Sounds directory not found: {self.sounds_directory}")
            return
            
        sound_files = {
            SoundEvent.STARTUP: "jarvis_intro-1.mp3",
            SoundEvent.WAKE_WORD: "jarvis_connected.mp3", 
            SoundEvent.DISCONNECT: "jarvis_disconnected.mp3",
            SoundEvent.OVERLOAD: "jarvis_overload.mp3",
            SoundEvent.CONFIRMATION: "as-you-wish-sir-jarvis.mp3",
            SoundEvent.CLICK: "click.mp3",
            SoundEvent.BEEP: "beep.mp3",
            SoundEvent.WHISTLE: "whistle.mp3"
        }
        
        for event, filename in sound_files.items():
            filepath = os.path.join(self.sounds_directory, filename)
            if os.path.exists(filepath):
                try:
                    sound = pygame.mixer.Sound(filepath)
                    sound.set_volume(self.volume)
                    self.sounds[event.value] = sound
                    print(f"[SOUND] Loaded: {filename}")
                except Exception as e:
                    print(f"[SOUND] Failed to load {filename}: {e}")
            else:
                print(f"[SOUND] Sound file not found: {filepath}")
    
    def play_sound(self, event: SoundEvent, wait_for_completion: bool = False) -> bool:
        """Play a sound effect for a specific event"""
        if not self.enabled or not self._initialized:
            return False
            
        with self._lock:
            sound_key = event.value
            if sound_key in self.sounds:
                try:
                    sound = self.sounds[sound_key]
                    if wait_for_completion:
                        sound.play()
                        # Wait for sound to finish playing
                        while pygame.mixer.get_busy():
                            time.sleep(0.1)
                    else:
                        sound.play()
                    return True
                except Exception as e:
                    print(f"[SOUND] Error playing sound {event.value}: {e}")
            return False
    
    def play_sound_async(self, event: SoundEvent):
        """Play a sound effect asynchronously"""
        def _play():
            self.play_sound(event, wait_for_completion=False)
        
        thread = threading.Thread(target=_play, daemon=True)
        thread.start()
    
    def set_volume(self, volume: float):
        """Set volume for all sounds (0.0 to 1.0)"""
        self.volume = max(0.0, min(1.0, volume))
        for sound in self.sounds.values():
            sound.set_volume(self.volume)
    
    def enable(self):
        """Enable sound effects"""
        self.enabled = True
    
    def disable(self):
        """Disable sound effects"""
        self.enabled = False
    
    def is_enabled(self) -> bool:
        """Check if sound effects are enabled"""
        return self.enabled
    
    def get_available_sounds(self) -> list:
        """Get list of available sound events"""
        return list(self.sounds.keys())
    
    def stop_all(self):
        """Stop all currently playing sounds"""
        if self._initialized:
            pygame.mixer.stop()
    
    def cleanup(self):
        """Clean up resources"""
        if self._initialized:
            self.stop_all()
            pygame.mixer.quit()
            self._initialized = False

# Global sound manager instance
_sound_manager: Optional[JarvisSoundManager] = None

def get_sound_manager() -> JarvisSoundManager:
    """Get the global sound manager instance"""
    global _sound_manager
    if _sound_manager is None:
        # Determine the sounds directory relative to this script
        script_dir = os.path.dirname(os.path.abspath(__file__))
        sounds_dir = os.path.join(script_dir, "resources", "sounds")
        _sound_manager = JarvisSoundManager(sounds_dir)
        _sound_manager.initialize()
    return _sound_manager

def play_sound(event: SoundEvent, wait_for_completion: bool = False) -> bool:
    """Convenience function to play a sound"""
    return get_sound_manager().play_sound(event, wait_for_completion)

def play_sound_async(event: SoundEvent):
    """Convenience function to play a sound asynchronously"""
    get_sound_manager().play_sound_async(event)
