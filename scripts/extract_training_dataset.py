import argparse
import json
import os
import re
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Dict, Iterable, List, Optional, Tuple


_EMAIL_RE = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE)
_IP_RE = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")


@dataclass
class Turn:
    role: str
    content: str
    ts: Optional[int] = None


def _load_jsonl(path: str) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    except FileNotFoundError:
        return []
    return rows


def redact(text: str) -> str:
    if not text:
        return ""
    text = _EMAIL_RE.sub("<REDACTED_EMAIL>", text)
    text = _IP_RE.sub("<REDACTED_IP>", text)
    text = re.sub(r"(?i)\b(api[_-]?key|secret|password|token)\b\s*[:=]\s*\S+", r"\1=<REDACTED>", text)
    return text


def normalize_text(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def parse_conversation(rows: List[Dict[str, Any]]) -> Tuple[Dict[str, Any], List[Turn]]:
    header: Dict[str, Any] = {}
    turns: List[Turn] = []

    for row in rows:
        # Handle the actual format: {"id": "...", "conversations": [{"from": "...", "value": "..."}]}
        if "conversations" in row and isinstance(row["conversations"], list):
            header = {"id": row.get("id", "")}
            for conv in row["conversations"]:
                role_map = {"human": "user", "gpt": "assistant"}
                role = role_map.get(conv.get("from", ""), "")
                content = normalize_text(redact(conv.get("value", "")))
                if role and content:
                    turns.append(Turn(role=role, content=content))
            continue
        
        # Skip non-message entries
        if not isinstance(row.get("conversations", []), list):
            continue

    return header, turns


def to_pairs(turns: List[Turn]) -> List[Tuple[str, str]]:
    pairs: List[Tuple[str, str]] = []
    i = 0
    while i < len(turns) - 1:
        if turns[i].role != "user":
            i += 1
            continue
        j = i + 1
        while j < len(turns) and turns[j].role != "assistant":
            j += 1
        if j >= len(turns):
            break

        user = turns[i].content
        assistant = turns[j].content

        if user and assistant:
            pairs.append((user, assistant))
        i = j + 1

    return pairs


def memo_summary(turns: List[Turn], max_chars: int = 900) -> str:
    user_goals: List[str] = []
    assistant_actions: List[str] = []

    for t in turns:
        if t.role == "user":
            if len(user_goals) < 6:
                user_goals.append(t.content)
        elif t.role == "assistant":
            if len(assistant_actions) < 6:
                assistant_actions.append(t.content)

    summary = "User requests:\n" + "\n".join(f"- {g}" for g in user_goals)
    summary += "\n\nAssistant responses:\n" + "\n".join(f"- {a}" for a in assistant_actions)
    summary = normalize_text(summary)

    if len(summary) > max_chars:
        summary = summary[: max_chars - 3].rstrip() + "..."

    return summary


def make_sharegpt_records(
    source_file: str,
    session_header: Dict[str, Any],
    pairs: List[Tuple[str, str]],
    memo: str,
    include_memo: bool,
) -> Iterable[Dict[str, Any]]:
    for idx, (u, a) in enumerate(pairs):
        conversations: List[Dict[str, str]] = []
        if include_memo and memo:
            conversations.append({"from": "system", "value": memo})
        conversations.append({"from": "human", "value": u})
        conversations.append({"from": "gpt", "value": a})

        yield {
            "id": f"{session_header.get('session_id','unknown')}_{idx}",
            "source": os.path.basename(source_file),
            "session_id": session_header.get("session_id", ""),
            "started_ts": session_header.get("started_ts"),
            "ended_ts": session_header.get("ended_ts"),
            "conversations": conversations,
        }


def iter_conversation_files(conversations_dir: str) -> List[str]:
    if not os.path.isdir(conversations_dir):
        return []
    files: List[str] = []
    for name in os.listdir(conversations_dir):
        if name.lower().endswith(".jsonl"):
            files.append(os.path.join(conversations_dir, name))
    files.sort()
    return files


def main() -> None:
    parser = argparse.ArgumentParser(description="Extract training dataset from exported JARVIS conversations")
    parser.add_argument("--conversations_dir", type=str, default="conversations", help="Folder containing exported .jsonl transcripts")
    parser.add_argument("--out", type=str, default="conversations/trainingData.jsonl", help="Output ShareGPT-style jsonl")
    parser.add_argument("--include_memo", action="store_true", help="Include memo summary as a system message")
    parser.add_argument("--min_conversation_length", type=int, default=2, help="Minimum conversation turns required")
    parser.add_argument("--filter_errors", action="store_true", help="Filter out error-state messages")

    args = parser.parse_args()

    conv_files = iter_conversation_files(args.conversations_dir)
    if not conv_files:
        raise SystemExit(f"No conversation .jsonl files found in: {args.conversations_dir}")

    # Load memories for integration
    memories = []
    mem_file = os.path.join(args.conversations_dir, "memories.jsonl")
    if os.path.exists(mem_file):
        with open(mem_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        mem_data = json.loads(line)
                        if mem_data.get("type") == "insight" and mem_data.get("content"):
                            # Filter out error messages and repetitive content
                            content = mem_data["content"]
                            if not any(phrase in content.lower() for phrase in [
                                "having trouble connecting", "neural connection was reset", 
                                "please ensure ollama is running", "system error"
                            ]):
                                memories.append(content)
                    except json.JSONDecodeError:
                        continue

    os.makedirs(os.path.dirname(args.out), exist_ok=True)

    total_records = 0
    seen_conversations = set()  # Deduplication
    with open(args.out, "w", encoding="utf-8") as out_f:
        for path in conv_files:
            rows = _load_jsonl(path)
            if not rows:
                print(f"Warning: No valid data found in {path}")
                continue
                
            header, turns = parse_conversation(rows)
            if not turns:
                print(f"Warning: No conversation turns found in {path}")
                continue

            # Filter conversations by minimum length and quality
            if len(turns) < args.min_conversation_length:
                print(f"Skipping {path}: too short ({len(turns)} turns, min {args.min_conversation_length})")
                continue

            pairs = to_pairs(turns)
            if not pairs:
                print(f"Skipping {path}: no valid conversation pairs")
                continue

            # Create dynamic memo summary with deduplication
            memo = ""
            if args.include_memo and memories:
                # Remove duplicates and limit to recent memories
                unique_memories = []
                seen = set()
                for mem in memories:
                    mem_key = mem[:50]  # Use first 50 chars for deduplication
                    if mem_key not in seen:
                        unique_memories.append(mem)
                        seen.add(mem_key)
                
                if unique_memories:
                    # Dynamic system prompt - integrate actual memories
                    memo = "You are JARVIS, a helpful AI assistant. Key memories and preferences:\n" + "\n".join(f"- {mem}" for mem in unique_memories[:5])
                else:
                    # Fallback basic identity if no memories
                    memo = "You are JARVIS, a helpful AI assistant."
            
            # Generate training records
            for rec in make_sharegpt_records(path, header, pairs, memo, args.include_memo):
                # Skip if this exact conversation was already processed
                conv_id = f"{header.get('id', 'unknown')}_{hash(str(pairs))}"
                if conv_id in seen_conversations:
                    print(f"Skipping duplicate conversation: {conv_id}")
                    continue
                    
                seen_conversations.add(conv_id)
                out_f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                total_records += 1

    stamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{stamp}] Wrote {total_records} records to {args.out}")


if __name__ == "__main__":
    main()
