"""
JARVIS GYM - Professional Training Engine
Optimized for Llama-3.2 fine-tuning via Unsloth.
"""
import torch
# Manually initialize the inductor config so Unsloth can find it
if not hasattr(torch._inductor, 'config'):
    import torch._inductor.config
import site
import sys
import os
# Import Jarvis sound manager
from jarvis_sound_manager import get_sound_manager, SoundEvent
from jarvis_utils import report_status

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_HF_CACHE_DIR = os.path.join(_SCRIPT_DIR, "tensors")
os.makedirs(_HF_CACHE_DIR, exist_ok=True)
os.environ.setdefault("HF_HOME", _HF_CACHE_DIR)
os.environ.setdefault("HF_HUB_CACHE", os.path.join(_HF_CACHE_DIR, "hub"))
os.environ.setdefault("TRANSFORMERS_CACHE", os.path.join(_HF_CACHE_DIR, "transformers"))
os.environ.setdefault("HF_DATASETS_CACHE", os.path.join(_HF_CACHE_DIR, "datasets"))

# --- FORCED DLL INJECTION (Windows 11 CUDA Fix) ---
def fix_nvidia_dlls():
    """Ensures CUDA DLLs are visible to PyTorch/Unsloth on Windows 11."""
    if sys.platform != 'win32': return
    try:
        paths = site.getsitepackages()
    except:
        paths = [os.path.join(sys.prefix, "Lib", "site-packages")]

    found = False
    for path in paths:
        # Scan for nvidia internal bin directories used by Unsloth/Transformers
        for lib in ["cublas", "cudnn", "cuda_runtime", "cusolver", "curand"]:
            bin_path = os.path.join(path, "nvidia", lib, "bin")
            if os.path.exists(bin_path):
                os.add_dll_directory(bin_path)
                os.environ["PATH"] = bin_path + os.pathsep + os.environ["PATH"]
                found = True
    
    if found:
        print(" [SYSTEM] Manually linked NVIDIA CUDA libraries for Brain Engine.")
    
    # Specifically point to the torch lib folder
    torch_lib = os.path.join(sys.prefix, "Lib", "site-packages", "torch", "lib")
    if os.path.exists(torch_lib):
        os.add_dll_directory(torch_lib)
        print(" [SYSTEM] Added torch lib folder to DLL search path.")

fix_nvidia_dlls()
# --------------------------------------------------

# Bypass Unsloth's internal installation check for PyInstaller
os.environ["UNSLOTH_IS_PRESENT"] = "1" 

# Suppress HuggingFace symlink warnings on Windows 11
os.environ["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"

# Force UTF-8 for Windows 11 console to prevent encoding errors in logs
if sys.stdout.encoding != 'utf-8':
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

# Increase recursion depth for complex AI libraries
sys.setrecursionlimit(10000)

import time
import logging
import gc
import subprocess
import argparse  # Added for CLI arguments
import re
import json
# Manually register the torch lib folder for DLL searching
torch_dll_path = os.path.join(sys.prefix, 'Lib', 'site-packages', 'torch', 'lib')
if os.path.exists(torch_dll_path):
    os.add_dll_directory(torch_dll_path)
import requests
from unsloth import FastLanguageModel
from unsloth.chat_templates import get_chat_template, standardize_sharegpt
from trl import SFTTrainer
from transformers import TrainingArguments
from datasets import load_dataset


def _maybe_enable_cuda_speedups():
    if not torch.cuda.is_available():
        return

    try:
        torch.set_float32_matmul_precision("high")
    except Exception:
        pass

    try:
        torch.backends.cuda.matmul.allow_tf32 = True
        torch.backends.cudnn.allow_tf32 = True
    except Exception:
        pass


def _get_latest_checkpoint(checkpoint_dir):
    if not checkpoint_dir or not os.path.isdir(checkpoint_dir):
        return None

    latest = None
    latest_step = -1
    for name in os.listdir(checkpoint_dir):
        if not name.startswith("checkpoint-"):
            continue
        m = re.match(r"checkpoint-(\d+)$", name)
        if not m:
            continue
        step = int(m.group(1))
        if step > latest_step:
            latest_step = step
            latest = os.path.join(checkpoint_dir, name)
    return latest

try:
    from pypdf import PdfReader
except ImportError:
    PdfReader = None

# --- LOGGING CONFIGURATION ---
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[logging.FileHandler("jarvis_master.log", encoding='utf-8'), logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger("JARVIS-Master")


def convert_textbook_to_jsonl(input_path):
    """Integrates textbook_to_sharegpt logic for background conversion."""
    subject = "Textbook Knowledge"
    output_path = os.path.join(os.path.dirname(input_path), "jarvis_gym_temp.jsonl")
    
    logger.info(f"📖 [SYSTEM] Converting textbook to training data: {input_path}")
    report_status("CORTEX_BRAIN", "BUSY", "Converting Textbook to Dataset...")

    full_text = ""
    if input_path.lower().endswith(".pdf"):
        if PdfReader is None:
            raise ImportError("pypdf is required for PDF processing. Run: pip install pypdf")
        reader = PdfReader(input_path)
        num_pages = len(reader.pages)
        for i, page in enumerate(reader.pages):
            full_text += page.extract_text() + "\n"
            if i % 5 == 0 or i == num_pages - 1:
                report_status("CORTEX_BRAIN", "BUSY", f"Converting Textbook: {i+1}/{num_pages} pages processed...")
    else:
        with open(input_path, 'r', encoding='utf-8') as f:
            full_text = f.read()

    # Cleaning and Chunking
    text = re.sub(r'\s+', ' ', full_text)
    text = re.sub(r'\[\d+\]', '', text).strip()
    
    chunks, start, chunk_size, overlap = [], 0, 1500, 200
    while start < len(text):
        chunks.append(text[start : start + chunk_size])
        start += (chunk_size - overlap)

    with open(output_path, 'w', encoding='utf-8') as f:
        for i, chunk in enumerate(chunks):
            record = {"id": f"textbook_gen_{i}", "conversations": [{"from": "human", "value": f"Explain this concept from the {subject}."}, {"from": "gpt", "value": chunk}]}
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
            
    logger.info(f"✅ [SYSTEM] Conversion complete. Generated {len(chunks)} blocks.")
    return output_path

# --- 1. SYSTEM & VRAM OPTIMIZATION ---
os.environ["PYTORCH_CUDA_ALLOC_CONF"] = "expandable_segments:True"

def flush_memory():
    gc.collect()
    torch.cuda.empty_cache()
    torch.cuda.ipc_collect()

def resolve_model_id(model_id):
    """Dynamically resolve Ollama tags to Unsloth HuggingFace IDs"""
    # If it's already a HF path or a local path, don't change it
    if "/" in model_id or "\\" in model_id:
        return model_id

    # Standardize Ollama naming (e.g., qwen2:0.5b -> base: qwen2, tag: 0.5b)
    parts = model_id.lower().split(":")
    base = parts[0]
    tag = parts[1] if len(parts) > 1 else ""

    # Mapping heuristics for Unsloth's naming convention
    # Pattern: unsloth/<ModelFamily>-<Size>-Instruct-bnb-4bit
    family_map = {
        "llama3.2": "Llama-3.2",
        "llama3.1": "Llama-3.1",
        "qwen2": "Qwen2",
        "phi3": "Phi-3-mini-4k",
        "mistral": "Mistral-7B-v0.3",
        "jarvis": "Llama-3.2-3B" # Custom alias for your main model
    }

    if base in family_map:
        resolved_family = family_map[base]
        size = tag.upper() if tag else ""
        
        # Construct the likely Unsloth repo name
        # Most Unsloth pre-quantized models use 'Instruct' and 'bnb-4bit'
        model_parts = [resolved_family]
        if size and size != "LATEST": model_parts.append(size)
        model_parts.append("Instruct")
        
        return f"unsloth/{'-'.join(model_parts)}-bnb-4bit"

    return model_id

# --- 2. TRAINING PHASE ---
def run_training(args):
    # Initialize sound manager
    sound_manager = get_sound_manager()

    _maybe_enable_cuda_speedups()
    
    resolved_model = resolve_model_id(args.model_id)

    logger.info(f"🚀 PHASE 1: Training starting. Resolved [{args.model_id}] -> [{resolved_model}]")
    
    # Aggressive VRAM cleanup before model initialization
    flush_memory()
    report_status("CORTEX_BRAIN", "BUSY", f"Training Jarvis: {resolved_model} on {os.path.basename(args.data)}")
    
    # Play processing sound to indicate training start
    sound_manager.play_sound_async(SoundEvent.BEEP)
    
    if not os.path.exists(args.data):
        logger.error(f"❌ Data file not found at: {args.data}")
        sys.exit(1)

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name = resolved_model,
        max_seq_length = args.max_seq_length,
        load_in_4bit = True,
    )

    model = FastLanguageModel.get_peft_model(
        model,
        r = args.lora_r,
        target_modules = ["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        lora_alpha = args.lora_alpha,
        lora_dropout = args.lora_dropout,
        bias = "none",
        use_gradient_checkpointing = "unsloth",
        random_state = args.seed,
    )

    dataset = load_dataset("json", data_files=args.data, split="train")
    dataset = standardize_sharegpt(dataset)
    
    tokenizer = get_chat_template(
        tokenizer,
        chat_template = "llama-3.2",
        mapping = {"role" : "from", "content" : "value", "user" : "human", "assistant" : "gpt"},
    )

    num_proc = max(1, int(args.dataset_num_proc))
    dataset = dataset.map(
        lambda x: {"text": [tokenizer.apply_chat_template(c, tokenize=False) for c in x["conversations"]]},
        batched=True,
        num_proc=num_proc,
    )

    checkpoint_dir = os.path.join(args.out_dir, "checkpoints")
    os.makedirs(checkpoint_dir, exist_ok=True)

    trainer = SFTTrainer(
        model = model,
        tokenizer = tokenizer,
        train_dataset = dataset,
        dataset_text_field = "text",
        max_seq_length = args.max_seq_length,
        args = TrainingArguments(
            per_device_train_batch_size = args.batch_size,
            gradient_accumulation_steps = args.grad_accum,
            max_steps = args.steps,
            learning_rate = args.lr,
            fp16 = not torch.cuda.is_bf16_supported(),
            bf16 = torch.cuda.is_bf16_supported(),
            logging_steps = 1,
            optim = args.optim,
            weight_decay = args.weight_decay,
            warmup_steps = args.warmup,
            seed = args.seed,
            lr_scheduler_type = args.lr_scheduler,
            save_safetensors = True, # Future-proof: Always save in safetensors format
            output_dir = checkpoint_dir,
            save_steps = args.save_steps,
            save_total_limit = args.save_total_limit,
            dataloader_num_workers = args.dataloader_num_workers,
            report_to = [],
        ),
    )

    resume_path = None
    if args.resume:
        resume_path = _get_latest_checkpoint(checkpoint_dir)
        if resume_path:
            logger.info(f"🔁 Resuming from checkpoint: {resume_path}")
        else:
            logger.info("🔁 Resume requested, but no checkpoint found. Starting fresh.")

    trainer.train(resume_from_checkpoint = resume_path)
    return model, tokenizer

# --- 3. MERGE & OLLAMA UPDATE ---
def finalize_jarvis(model, tokenizer, final_merged_path, modelfile_path, quantization, model_name):
    # Initialize sound manager
    sound_manager = get_sound_manager()
    
    logger.info(f"🔄 PHASE 2: Merging weights to {final_merged_path} (4-bit optimized).")
    report_status("CORTEX_BRAIN", "BUSY", "Merging Weights & Finalizing (Standby)...")
    
    # Play processing sound for merge start
    sound_manager.play_sound_async(SoundEvent.BEEP)
    
    # Aggressive VRAM cleanup before the merge operation
    flush_memory()

    # Create directory if it doesn't exist
    os.makedirs(os.path.dirname(final_merged_path), exist_ok=True)
    
    # Using merged_4bit avoids downloading 12GB of 16-bit weights
    model.save_pretrained_merged(final_merged_path, tokenizer, save_method = "merged_16bit")
    
    del model
    del tokenizer
    flush_memory()

    logger.info("🤖 PHASE 3: Updating Ollama...")
    report_status("CORTEX_BRAIN", "BUSY", f"Quantizing to {quantization} and updating Ollama...")
    try:
        command = ["ollama", "create", model_name, "-f", modelfile_path, "--quantize", quantization]
        
        result = subprocess.run(
            command,
            capture_output=True, 
            text=True, 
            check=True,
            shell=True 
        )
        logger.info(f"✅ OLLAMA SUCCESS: {result.stdout}")
        # Play completion sound when training is complete
        sound_manager.play_sound_async(SoundEvent.CONFIRMATION)
    except subprocess.CalledProcessError as e:
        logger.error(f"❌ OLLAMA ERROR: {e.stderr}")
        if e.stdout:
            logger.error(f"Ollama Output: {e.stdout}")
        # Play error sound on failure
        sound_manager.play_sound_async(SoundEvent.OVERLOAD)

if __name__ == "__main__":
    # --- 4. CLI ARGUMENT PARSING ---
    parser = argparse.ArgumentParser(description="Jarvis Command-Line Trainer")
    
    # Basic Config
    parser.add_argument("--data", type=str, default="../conversations/jarvis_optimized_sharegpt.jsonl", help="Path to training data .jsonl")
    parser.add_argument("--out_dir", type=str, default="../models/jarvis_build", help="Base directory for model output")
    parser.add_argument("--model_id", type=str, default="unsloth/Llama-3.2-3B-Instruct-bnb-4bit", help="Base model to train")
    parser.add_argument("--resume", action="store_true", help="Resume from the latest checkpoint in <out_dir>/checkpoints.")
    parser.add_argument("--model_name", type=str, default="jarvis", help="Name for the resulting Ollama model")
    
    # Hyperparameters
    parser.add_argument("--lr", type=float, default=2e-4, help="Learning Rate: Speed of learning. High = risky, Low = slow.")
    parser.add_argument("--steps", type=int, default=60, help="Training Steps: How many iterations to run.")
    parser.add_argument("--batch_size", type=int, default=2, help="Batch Size: Samples processed per step.")
    parser.add_argument("--grad_accum", type=int, default=4, help="Gradient Accumulation: Virtual batch size multiplier.")
    parser.add_argument("--max_seq_length", type=int, default=2048, help="Max Sequence Length: History window size.")
    parser.add_argument("--lora_r", type=int, default=16, help="LoRA Rank: Complexity of the learned adapter.")
    parser.add_argument("--lora_alpha", type=int, default=16, help="LoRA Alpha: Influence scale of the training.")
    parser.add_argument("--lora_dropout", type=float, default=0.0, help="Dropout: Regularization to prevent overfitting.")
    parser.add_argument("--weight_decay", type=float, default=0.01, help="Weight Decay: Prevents weights from exploding.")
    parser.add_argument("--optim", type=str, default="paged_adamw_8bit", help="Optimizer: Algorithm for updating weights.")
    parser.add_argument("--warmup", type=int, default=10, help="Warmup Steps: Initial steps with reduced learning rate.")
    parser.add_argument("--lr_scheduler", type=str, default="linear", help="LR Scheduler: How learning rate changes over time.")
    parser.add_argument("--quant", type=str, default="q4_k_m", help="Output Quantization: Bit-depth for Ollama.")
    parser.add_argument("--seed", type=int, default=3407, help="Random Seed: For reproducibility.")

    # Performance / checkpointing
    parser.add_argument("--dataset_num_proc", type=int, default=4, help="Parallel workers for dataset preprocessing (map).")
    parser.add_argument("--dataloader_num_workers", type=int, default=0, help="PyTorch DataLoader workers.")
    parser.add_argument("--save_steps", type=int, default=20, help="How often to save checkpoints (in steps).")
    parser.add_argument("--save_total_limit", type=int, default=2, help="Max number of checkpoints to keep.")

    args = parser.parse_args()

    # Dynamic Path Construction
    final_merged_path = os.path.join(args.out_dir, "jarvis_merged")
    modelfile_path = os.path.join(args.out_dir, "Modelfile")

    # --- PRE-PROCESSING: Automatic Textbook Conversion ---
    if args.data.lower().endswith((".pdf", ".txt")):
        try:
            args.data = convert_textbook_to_jsonl(args.data)
        except Exception as e:
            logger.error(f" TEXTBOOK CONVERSION FAILED: {str(e)}")
            report_status("ERROR", f"Conversion Failed: {str(e)}")
            sys.exit(1)

    try:
        # Run Process
        trained_model, trained_tokenizer = run_training(args)
        finalize_jarvis(trained_model, trained_tokenizer, final_merged_path, modelfile_path, args.quant, args.model_name)
        
        logger.info(" MISSION COMPLETE. Jarvis is updated and ready.")
        report_status("CORTEX_BRAIN", "READY", "Brain update complete. Ollama refreshed.")
    except Exception as e:
        logger.error(f" CRITICAL FAILURE: {str(e)}")
        report_status("CORTEX_BRAIN", "ERROR", f"Brain Script Failed: {str(e)}")
        sys.exit(1)