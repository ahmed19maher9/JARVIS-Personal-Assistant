import os
import sys
import json
import time
import threading
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import uvicorn
try:
    from sentence_transformers import SentenceTransformer
except ImportError:
    print("❌ [MEMORY ERROR] Missing dependency: 'sentence-transformers'. Please run: pip install sentence-transformers einops")
    os._exit(1)

app = FastAPI(title="Jarvis Cortex Memory (Semantic Vectors)")

# --- CONFIGURATION ---
# Using Nomic Embed v1.5 directly for true offline execution (bypassing Ollama service)
MODEL_NAME = 'nomic-ai/nomic-embed-text-v1.5'
MEMORY_PATH = os.path.join(os.path.dirname(__file__), "../conversations/memories.json")

print(f"🧠 [MEMORY] Loading Embedding Engine: {MODEL_NAME}...")
# Load model locally. trust_remote_code is required for Nomic's architecture.
embed_model = SentenceTransformer(MODEL_NAME, trust_remote_code=True)
print("✅ [MEMORY] Embedding Engine Online.")

class MemoryItem(BaseModel):
    content: str
    metadata: dict = {}

class Query(BaseModel):
    text: str
    top_k: int = 3

class VectorStore:
    def __init__(self, path):
        self.path = path
        self.entries = []
        self.load()

    def load(self):
        if os.path.exists(self.path):
            try:
                with open(self.path, 'r', encoding='utf-8') as f:
                    self.entries = json.load(f)
                print(f"✅ Loaded {len(self.entries)} memory vectors from disk.")
            except Exception as e:
                print(f"⚠️ Memory load failed: {e}")

    def save(self):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        try:
            with open(self.path, 'w', encoding='utf-8') as f:
                json.dump(self.entries, f, ensure_ascii=False)
        except Exception as e:
            print(f"❌ Failed to save memory: {e}")

    def add(self, text, vector, metadata):
        self.entries.append({
            "text": text,
            "vector": vector.tolist() if isinstance(vector, np.ndarray) else vector,
            "metadata": metadata,
            "timestamp": time.time()
        })
        self.save()

store = VectorStore(MEMORY_PATH)

@app.get("/health")
async def health():
    return {"status": "ready", "module": "memory", "vectors": len(store.entries)}

@app.post("/memory/add")
async def add_memory(item: MemoryItem):
    try:
        # Nomic v1.5 requires 'search_document: ' prefix for storage
        vector = embed_model.encode(f"search_document: {item.content}")
        store.add(item.content, vector, item.metadata)
        return {"status": "success", "detail": "Vectorized and stored"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/memory/query")
async def query_memory(q: Query):
    if not store.entries:
        return {"results": []}
    try:
        # Nomic v1.5 requires 'search_query: ' prefix for queries
        query_vec = embed_model.encode(f"search_query: {q.text}")
        
        results = []
        for entry in store.entries:
            entry_vec = np.array(entry["vector"])
            # Calculate Cosine Similarity
            similarity = np.dot(query_vec, entry_vec) / (np.linalg.norm(query_vec) * np.linalg.norm(entry_vec))
            results.append({"text": entry["text"], "score": float(similarity)})
        
        results.sort(key=lambda x: x["score"], reverse=True)
        return {"results": results[:q.top_k]}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/shutdown")
async def shutdown():
    threading.Thread(target=lambda: (time.sleep(1), os._exit(0)), daemon=True).start()
    return {"status": "shutting down"}

if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8884, log_level="error")