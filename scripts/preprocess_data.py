import json
import os

# Get the directory where this script is located: D:\personal_assistant\code java\scripts
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# Project root is the parent: D:\personal_assistant\code java
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
# The conversations folder is in the parent of the project root: D:\personal_assistant\conversations
CONVERSATIONS_DIR = os.path.join(os.path.dirname(PROJECT_ROOT), "conversations")

CONV_FILE = os.path.join(CONVERSATIONS_DIR, "conversations.jsonl")
MEM_FILE = os.path.join(CONVERSATIONS_DIR, "memories.jsonl")
OUTPUT_FILE = os.path.join(CONVERSATIONS_DIR, "trainingData.jsonl")

def preprocess():
    print("🛠️ JARVIS Data Preprocessor: Merging records...")
    
    memories = []
    if os.path.exists(MEM_FILE):
        with open(MEM_FILE, 'r', encoding='utf-8') as f:
            for line in f:
                try:
                    memories.append(json.loads(line)['content'])
                except: continue

    combined_memo = "\n".join(memories[-10:]) # Take last 10 insights for context density
    
    processed_count = 0
    if os.path.exists(CONV_FILE):
        with open(CONV_FILE, 'r', encoding='utf-8') as f_in, \
             open(OUTPUT_FILE, 'w', encoding='utf-8') as f_out:
            
            for line in f_in:
                try:
                    data = json.loads(line)
                    # Inject memories as a system instruction at the start of every conversation
                    system_msg = {
                        "from": "system",
                        "value": f"### NEURAL MEMORIES & PREFERENCES:\n{combined_memo}"
                    }
                    data['conversations'].insert(0, system_msg)
                    
                    f_out.write(json.dumps(data) + "\n")
                    processed_count += 1
                except Exception as e:
                    print(f"⚠️ Skipping corrupted line: {e}")

    print(f"✅ Preprocessing complete. {processed_count} sessions optimized in {OUTPUT_FILE}")

if __name__ == "__main__":
    preprocess()