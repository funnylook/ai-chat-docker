# AI Chat Docker Backend
# Flask API: 文件上传 + 模型路由 + 推理强度控制

import os
import json
import time
import hashlib
import requests
from pathlib import Path
from functools import wraps

from flask import Flask, request, jsonify, Response, stream_with_context
from werkzeug.utils import secure_filename
import yaml

# ============================================================
# Config
# ============================================================

BASE_DIR = Path(__file__).parent
CONFIG_PATH = BASE_DIR / "config.yaml"

def load_config():
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)

config = load_config()

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = config.get("max_upload_mb", 50) * 1024 * 1024

UPLOAD_DIR = BASE_DIR / "uploads"
UPLOAD_DIR.mkdir(exist_ok=True)

# ============================================================
# Helpers
# ============================================================

def get_models():
    """Return available models from config."""
    return config.get("models", [])

def get_model_config(model_id):
    """Find model config by id."""
    for m in get_models():
        if m["id"] == model_id:
            return m
    return None

def get_reasoning_effort(effort, model_config):
    """Map reasoning intensity to model-specific parameters."""
    effort_map = model_config.get("reasoning_effort_map", {})
    if effort in effort_map:
        return effort_map[effort]
    # Default mapping
    mapping = {
        "low":    {"temperature": 0.3, "reasoning_effort": "low",    "thinking_budget": 1024},
        "medium": {"temperature": 0.7, "reasoning_effort": "medium", "thinking_budget": 4096},
        "high":   {"temperature": 1.0, "reasoning_effort": "high",   "thinking_budget": 16384},
    }
    return mapping.get(effort, mapping["medium"])

def allowed_file(filename):
    """Check if file extension is allowed."""
    ext = filename.rsplit('.', 1)[-1].lower() if '.' in filename else ''
    allowed = config.get("allowed_extensions", [])
    # If allowed list is empty or contains '*', allow all
    if not allowed or '*' in allowed:
        return True
    return ext in allowed

def file_info(file_path):
    """Get file metadata."""
    stat = os.stat(file_path)
    suffix = Path(file_path).suffix.lower()
    # Determine file category
    if suffix in ['.jpg', '.jpeg', '.png', '.gif', '.bmp', '.webp', '.svg']:
        category = "image"
    elif suffix in ['.pdf', '.doc', '.docx', '.txt', '.md', '.csv', '.json', '.xml', '.html']:
        category = "document"
    elif suffix in ['.mp3', '.wav', '.ogg', '.m4a', '.flac']:
        category = "audio"
    elif suffix in ['.mp4', '.avi', '.mkv', '.mov', '.webm']:
        category = "video"
    else:
        category = "file"
    return {
        "filename": os.path.basename(file_path),
        "size": stat.st_size,
        "category": category,
        "extension": suffix.lstrip('.'),
    }

# ============================================================
# Routes: Models
# ============================================================

@app.route("/api/models", methods=["GET"])
def list_models():
    """Return available models for the app to display."""
    models = get_models()
    result = []
    for m in models:
        result.append({
            "id": m["id"],
            "name": m.get("name", m["id"]),
            "provider": m.get("provider", "unknown"),
            "supports_reasoning": m.get("supports_reasoning", False),
            "supports_vision": m.get("supports_vision", False),
            "supports_files": m.get("supports_files", True),
            "max_tokens": m.get("max_tokens", 4096),
        })
    return jsonify({"models": result})

# ============================================================
# Routes: Chat (with optional file)
# ============================================================

@app.route("/api/chat", methods=["POST"])
def chat():
    """
    Chat endpoint with file support.
    Accepts multipart/form-data:
      - model: model id
      - reasoning_effort: low/medium/high
      - messages: JSON array of {role, content}
      - file: optional file attachment
    """
    model_id = request.form.get("model", "")
    reasoning_effort = request.form.get("reasoning_effort", "medium")
    messages_raw = request.form.get("messages", "[]")

    if not model_id:
        return jsonify({"error": "model is required"}), 400

    model_config = get_model_config(model_id)
    if not model_config:
        return jsonify({"error": f"unknown model: {model_id}"}), 400

    try:
        messages = json.loads(messages_raw)
    except json.JSONDecodeError:
        return jsonify({"error": "invalid messages JSON"}), 400

    # Handle file upload
    file_url = None
    file_meta = None
    if "file" in request.files:
        f = request.files["file"]
        if f.filename and f.filename.strip():
            filename = secure_filename(f.filename)
            if not allowed_file(filename):
                return jsonify({"error": f"file type not allowed: {filename}"}), 400

            # Save with timestamp to avoid collisions
            ts = int(time.time() * 1000)
            save_name = f"{ts}_{filename}"
            save_path = UPLOAD_DIR / save_name
            f.save(str(save_path))

            file_meta = file_info(str(save_path))
            file_url = f"/uploads/{save_name}"

            # Inject file context into messages
            file_context = f"\n\n[用户上传了文件: {filename}（{file_meta['category']}，{file_meta['size']} 字节）]"
            if file_meta["category"] == "image" and model_config.get("supports_vision"):
                file_context += "\n[图片已附加，模型可以查看]"
            elif file_meta["category"] == "document":
                # Try to extract text from document
                text = extract_text(save_path, file_meta["extension"])
                if text:
                    file_context += f"\n[文件内容预览]:\n{text[:3000]}"

            # Append to last user message or create new
            if messages and messages[-1]["role"] == "user":
                messages[-1]["content"] += file_context
            else:
                messages.append({"role": "user", "content": file_context.lstrip()})

    # Build request to upstream API
    effort_params = get_reasoning_effort(reasoning_effort, model_config)

    api_url = model_config.get("api_url", "")
    api_key = model_config.get("api_key", "")

    if not api_url or not api_key:
        return jsonify({"error": f"model {model_id} not properly configured"}), 500

    # Construct upstream request
    upstream_body = {
        "model": model_id,
        "messages": messages,
        "stream": True,
        "temperature": effort_params.get("temperature", 0.7),
        "max_tokens": model_config.get("max_tokens", 4096),
    }

    # Add reasoning effort if model supports it
    if model_config.get("supports_reasoning"):
        if "reasoning_effort" in effort_params:
            upstream_body["reasoning_effort"] = effort_params["reasoning_effort"]
        if "thinking_budget" in effort_params and model_config.get("supports_thinking_budget"):
            upstream_body["thinking"] = {"type": "enabled", "budget_tokens": effort_params["thinking_budget"]}

    # Handle vision: if file is image and model supports vision, add image to last message
    if file_meta and file_meta["category"] == "image" and model_config.get("supports_vision") and file_url:
        # Read image and encode as base64
        import base64
        with open(UPLOAD_DIR / save_name, "rb") as img_f:
            img_b64 = base64.b64encode(img_f.read()).decode()

        # Modify last user message to include image
        last_msg = messages[-1]
        if last_msg["role"] == "user":
            messages[-1] = {
                "role": "user",
                "content": [
                    {"type": "text", "text": last_msg["content"]},
                    {"type": "image_url", "image_url": {"url": f"data:image/{file_meta['extension']};base64,{img_b64}"}}
                ]
            }
            upstream_body["messages"] = messages

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    def generate():
        try:
            resp = requests.post(api_url, json=upstream_body, headers=headers, stream=True, timeout=120)
            if resp.status_code != 200:
                error_text = resp.text[:500]
                yield f"data: {json.dumps({'error': f'upstream error {resp.status_code}: {error_text}'})}\n\n"
                return

            for line in resp.iter_lines():
                if line:
                    decoded = line.decode("utf-8")
                    yield f"{decoded}\n\n"

        except Exception as e:
            yield f"data: {json.dumps({'error': str(e)})}\n\n"

    return Response(stream_with_context(generate()), content_type="text/event-stream")

# ============================================================
# Routes: File download (for uploaded files)
# ============================================================

@app.route("/uploads/<filename>", methods=["GET"])
def serve_file(filename):
    """Serve uploaded files."""
    safe = secure_filename(filename)
    file_path = UPLOAD_DIR / safe
    if not file_path.exists():
        return jsonify({"error": "file not found"}), 404
    from flask import send_file
    return send_file(str(file_path))

# ============================================================
# Text extraction for documents
# ============================================================

def extract_text(file_path, extension):
    """Extract text from common document types."""
    try:
        if extension == "pdf":
            from pypdf import PdfReader
            reader = PdfReader(file_path)
            text = ""
            for page in reader.pages:
                text += page.extract_text() or ""
            return text[:5000]
        elif extension in ("txt", "md", "csv", "json", "xml", "html", "log"):
            with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                return f.read()[:5000]
        elif extension in ("docx",):
            from docx import Document
            doc = Document(file_path)
            return "\n".join([p.text for p in doc.paragraphs])[:5000]
    except Exception as e:
        return f"[无法提取文件内容: {e}]"
    return ""

# ============================================================
# Health check
# ============================================================

@app.route("/api/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "models_count": len(get_models())})

# ============================================================
# Main
# ============================================================

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
