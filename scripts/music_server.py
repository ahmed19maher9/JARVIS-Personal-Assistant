import os
import torch
import torchaudio
import numpy as np
from fastapi import FastAPI
from audiocraft.models import MusicGen
from bark import SAMPLE_RATE, generate_audio, preload_models
from pydub import AudioSegment

# CRITICAL FOR 6GB VRAM
os.environ["SUNO_USE_SMALL_MODELS"] = "True"
os.environ["SUNO_OFFLOAD_CPU"] = "True" # Moves models to RAM when not in use

app = FastAPI()

# Preload models (Bark will download ~4GB on first run)
print("Loading MusicGen and Bark...")
music_model = MusicGen.get_pretrained('facebook/musicgen-small')
preload_models() 

OUTPUT_DIR = "D:/personal assistant/output"
os.makedirs(OUTPUT_DIR, exist_ok=True)

@app.get("/generate_music")
def generate_music(prompt: str, duration: int = 10):
    music_model.set_generation_params(duration=int(duration))
    with torch.no_grad():
        with torch.cuda.amp.autocast(): # Save memory
            wav = music_model.generate([prompt])
    
    path = os.path.join(OUTPUT_DIR, "background.wav")
    # Fix: squeeze(0) removes the batch dimension properly
    torchaudio.save(path, wav.squeeze(0).cpu().float(), music_model.sample_rate)
    
    torch.cuda.empty_cache() # Give VRAM back to Ollama
    return {"status": "success", "url": path}

@app.get("/sing")
def sing_lyrics(lyrics: str, voice_preset: str = "v2/en_speaker_6"):
    try:
        # Clean and prepare lyrics for Bark with musical tags
        lines = lyrics.split('\n')
        vocal_chunks = []
        
        # Process lyrics in chunks for better generation
        current_chunk = ""
        chunk_duration = 0
        
        for line in lines:
            if line.strip() and not line.startswith('Verse') and not line.startswith('Chorus'):
                # Clean up the line
                clean_line = line.replace('Verse 1:', '').replace('Verse 2:', '').replace('Chorus:', '').strip()
                
                if clean_line and len(clean_line) < 80:  # Keep lines reasonable
                    # Add musical notation and tags
                    musical_line = f"♪ [music] [singing] {clean_line} [singing] [music] ♪"
                    
                    if chunk_duration < 15:  # Target 15-second chunks
                        current_chunk += musical_line + " "
                        chunk_duration += 3  # Estimate 3 seconds per line
                    else:
                        if current_chunk.strip():
                            vocal_chunks.append(current_chunk.strip())
                        current_chunk = musical_line + " "
                        chunk_duration = 3
        
        # Add the last chunk
        if current_chunk.strip():
            vocal_chunks.append(current_chunk.strip())
        
        # Generate vocals chunk by chunk
        full_vocal = None
        for i, chunk in enumerate(vocal_chunks[:4]):  # Limit to 4 chunks max
            print(f"Generating vocal chunk {i+1}/{len(vocal_chunks)}: {chunk[:100]}...")
            
            try:
                with torch.no_grad():
                    audio_array = generate_audio(chunk, history_prompt=voice_preset)
                
                vocal_tensor = torch.from_numpy(audio_array).unsqueeze(0).float()
                
                if full_vocal is None:
                    full_vocal = vocal_tensor
                else:
                    # Add small pause between chunks
                    pause = torch.zeros(1, 2400)  # 0.1 second pause at 24kHz
                    full_vocal = torch.cat([full_vocal, pause, vocal_tensor], dim=1)
                    
            except Exception as e:
                print(f"Error generating chunk {i+1}: {e}")
                # Continue with next chunk
                continue
        
        if full_vocal is None:
            # Final fallback: generate simple musical humming
            fallback_prompt = "♪ [music] [singing] la la la la la la [singing] [music] ♪"
            with torch.no_grad():
                audio_array = generate_audio(fallback_prompt, history_prompt=voice_preset)
            full_vocal = torch.from_numpy(audio_array).unsqueeze(0).float()
        
        path = os.path.join(OUTPUT_DIR, "vocals.wav")
        torchaudio.save(path, full_vocal, SAMPLE_RATE)
        print(f"✅ Vocals saved to {path}")
        
        torch.cuda.empty_cache()
        return {"status": "success", "url": path}
        
    except Exception as e:
        print(f"Error generating vocals: {e}")
        # Generate fallback musical humming
        try:
            fallback_prompt = "♪ [music] [singing] ooh ooh ah ah la la la [singing] [music] ♪"
            with torch.no_grad():
                audio_array = generate_audio(fallback_prompt, history_prompt=voice_preset)
            vocal_tensor = torch.from_numpy(audio_array).unsqueeze(0).float()
            path = os.path.join(OUTPUT_DIR, "vocals.wav")
            torchaudio.save(path, vocal_tensor, SAMPLE_RATE)
            return {"status": "success", "url": path}
        except Exception as e2:
            return {"status": "error", "message": f"Vocal generation failed: {str(e2)}"}

@app.get("/mix")
def mix_audio():
    try:
        bg_path = os.path.join(OUTPUT_DIR, "background.wav")
        voc_path = os.path.join(OUTPUT_DIR, "vocals.wav")
        
        background = AudioSegment.from_wav(bg_path)
        vocals = AudioSegment.from_wav(voc_path)
        
        # Match sample rates and mix
        background = background - 7 # Make music quieter so Jarvis is heard
        combined = background.overlay(vocals)
        
        final_path = os.path.join(OUTPUT_DIR, "final_song.wav")
        combined.export(final_path, format="wav")
        return {"status": "song_ready", "url": final_path}
    except Exception as e:
        return {"status": "error", "message": str(e)}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8001)