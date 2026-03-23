#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

MODEL_NAME="${MODEL_NAME:-small}"
FRAME_FPS="${FRAME_FPS:-1}"
MAX_OCR_FRAMES="${MAX_OCR_FRAMES:-60}"
OCR_LANG="${OCR_LANG:-eng}"

usage() {
  cat <<'EOF'
用法:
  scripts/whisper_offline_verify.sh <视频路径> [输出目录]

功能:
  1) 提取视频元信息 (ffprobe)
  2) 分离16k单声道WAV音频
  3) 使用Whisper离线识别语言与文案
  4) 抽帧作为可视化证据
  5) 若本机存在tesseract，额外输出OCR证据

环境变量(可选):
  MODEL_NAME      Whisper模型名，默认 small
  FRAME_FPS       抽帧帧率，默认 1
  MAX_OCR_FRAMES  OCR最大处理帧数，默认 60
  OCR_LANG        OCR语言，默认 eng
EOF
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令: $1"
    exit 1
  fi
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

VIDEO_INPUT="${1:-}"
if [[ -z "$VIDEO_INPUT" ]]; then
  usage
  exit 1
fi

if [[ ! -f "$VIDEO_INPUT" ]]; then
  echo "输入视频不存在: $VIDEO_INPUT"
  exit 1
fi

require_cmd ffmpeg
require_cmd ffprobe
require_cmd python3

if ! python3 - <<'PY' >/dev/null 2>&1
import whisper  # noqa: F401
PY
then
  echo "Python环境未安装 openai-whisper，请先执行: pip3 install openai-whisper"
  exit 1
fi

VIDEO_ABS="$(python3 - <<'PY' "$VIDEO_INPUT"
import os, sys
print(os.path.abspath(sys.argv[1]))
PY
)"

VIDEO_BASENAME="$(basename "$VIDEO_ABS")"
VIDEO_STEM="${VIDEO_BASENAME%.*}"
SAFE_STEM="$(printf '%s' "$VIDEO_STEM" | sed 's/[^A-Za-z0-9._-]/_/g')"
TIMESTAMP="$(date +"%Y%m%d_%H%M%S")"

OUT_DIR="${2:-$ROOT_DIR/video/whisper_verify/${SAFE_STEM}_${TIMESTAMP}}"
FRAMES_DIR="$OUT_DIR/frames"
OCR_DIR="$OUT_DIR/ocr"

mkdir -p "$OUT_DIR" "$FRAMES_DIR" "$OCR_DIR"

METADATA_JSON="$OUT_DIR/metadata.json"
WAV_FILE="$OUT_DIR/audio_16k_mono.wav"
TRANSCRIPT_JSON="$OUT_DIR/transcript.json"
TRANSCRIPT_TXT="$OUT_DIR/transcript.txt"
SEGMENTS_TXT="$OUT_DIR/segments.txt"
OCR_TSV="$OCR_DIR/ocr_evidence.tsv"
OCR_HITS="$OCR_DIR/ocr_hits.txt"
REPORT_MD="$OUT_DIR/report.md"

echo "[1/5] 写入视频元信息..."
ffprobe -v error -print_format json -show_format -show_streams "$VIDEO_ABS" > "$METADATA_JSON"

echo "[2/5] 分离16k单声道音频..."
ffmpeg -y -i "$VIDEO_ABS" -vn -ac 1 -ar 16000 -c:a pcm_s16le "$WAV_FILE" >/dev/null 2>&1

echo "[3/5] Whisper离线识别..."
python3 - <<'PY' "$MODEL_NAME" "$WAV_FILE" "$TRANSCRIPT_JSON" "$TRANSCRIPT_TXT" "$SEGMENTS_TXT"
import json
import os
import sys

import whisper

model_name, wav_path, transcript_json, transcript_txt, segments_txt = sys.argv[1:6]

model = whisper.load_model(model_name)
result = model.transcribe(wav_path, fp16=False, verbose=False)

payload = {
    "model": model_name,
    "detected_language": result.get("language"),
    "text": (result.get("text") or "").strip(),
    "segments": result.get("segments", []),
}

os.makedirs(os.path.dirname(transcript_json), exist_ok=True)
with open(transcript_json, "w", encoding="utf-8") as f:
    json.dump(payload, f, ensure_ascii=False, indent=2)

with open(transcript_txt, "w", encoding="utf-8") as f:
    f.write(payload["text"] + ("\n" if payload["text"] else ""))

with open(segments_txt, "w", encoding="utf-8") as f:
    for seg in payload["segments"]:
        start = float(seg.get("start", 0.0))
        end = float(seg.get("end", 0.0))
        text = (seg.get("text") or "").strip()
        f.write(f"[{start:.1f}-{end:.1f}] {text}\n")

print(f"detected_language={payload['detected_language']}")
print(f"text_length={len(payload['text'])}")
PY

echo "[4/5] 抽帧证据..."
ffmpeg -y -i "$VIDEO_ABS" -vf "fps=${FRAME_FPS}" "$FRAMES_DIR/frame_%05d.jpg" >/dev/null 2>&1
FRAME_COUNT="$(find "$FRAMES_DIR" -name 'frame_*.jpg' | wc -l | tr -d ' ')"
echo "抽帧完成: ${FRAME_COUNT} 张"

echo "[5/5] OCR证据(可选)..."
if command -v tesseract >/dev/null 2>&1; then
  python3 - <<'PY' "$FRAMES_DIR" "$OCR_TSV" "$OCR_HITS" "$MAX_OCR_FRAMES" "$OCR_LANG"
import os
import re
import subprocess
import sys

frames_dir, ocr_tsv, ocr_hits, max_ocr_frames_raw, ocr_lang = sys.argv[1:6]
max_ocr_frames = max(1, int(max_ocr_frames_raw))

frames = sorted(
    os.path.join(frames_dir, name)
    for name in os.listdir(frames_dir)
    if name.startswith("frame_") and name.lower().endswith(".jpg")
)

frames = frames[:max_ocr_frames]

os.makedirs(os.path.dirname(ocr_tsv), exist_ok=True)

def normalize_text(raw: str) -> str:
    text = raw.replace("\r", "\n")
    text = re.sub(r"\s+", " ", text).strip()
    return text

rows = []
for frame in frames:
    cmd = ["tesseract", frame, "stdout", "--psm", "6", "-l", ocr_lang]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    text = normalize_text(proc.stdout or "")
    if text:
        rows.append((os.path.basename(frame), text))

with open(ocr_tsv, "w", encoding="utf-8") as f:
    f.write("frame\ttext\n")
    for frame_name, text in rows:
        safe = text.replace("\t", " ")
        f.write(f"{frame_name}\t{safe}\n")

phone_like = []
for _, text in rows:
    phone_like.extend(re.findall(r"\b\d{3,}[- ]?\d{3,}\b", text))

with open(ocr_hits, "w", encoding="utf-8") as f:
    if phone_like:
        f.write("可能的联系方式:\n")
        for item in sorted(set(phone_like)):
            f.write(f"- {item}\n")
    else:
        f.write("未提取到明显的联系方式模式。\n")

print(f"ocr_frames_processed={len(frames)}")
print(f"ocr_rows_with_text={len(rows)}")
PY
else
  echo "未检测到 tesseract，跳过 OCR。"
  printf "frame\ttext\n" > "$OCR_TSV"
  echo "未检测到 tesseract，未生成OCR命中结果。" > "$OCR_HITS"
fi

python3 - <<'PY' "$VIDEO_ABS" "$MODEL_NAME" "$FRAME_FPS" "$OUT_DIR" "$METADATA_JSON" "$TRANSCRIPT_JSON" "$TRANSCRIPT_TXT" "$SEGMENTS_TXT" "$FRAMES_DIR" "$OCR_TSV" "$OCR_HITS" "$REPORT_MD"
import json
import os
import sys

(
    video_abs,
    model_name,
    frame_fps,
    out_dir,
    metadata_json,
    transcript_json,
    transcript_txt,
    segments_txt,
    frames_dir,
    ocr_tsv,
    ocr_hits,
    report_md,
) = sys.argv[1:]

language = "unknown"
text = ""
segments_count = 0
try:
    with open(transcript_json, "r", encoding="utf-8") as f:
        payload = json.load(f)
    language = payload.get("detected_language") or "unknown"
    text = (payload.get("text") or "").strip()
    segments_count = len(payload.get("segments") or [])
except Exception:
    pass

frame_count = len([n for n in os.listdir(frames_dir) if n.startswith("frame_") and n.endswith(".jpg")])

preview = text[:320] + ("..." if len(text) > 320 else "")

with open(report_md, "w", encoding="utf-8") as f:
    f.write("# Whisper 离线校验报告\n\n")
    f.write(f"- 输入视频: `{video_abs}`\n")
    f.write(f"- Whisper模型: `{model_name}`\n")
    f.write(f"- 检测语言: `{language}`\n")
    f.write(f"- 抽帧FPS: `{frame_fps}`\n")
    f.write(f"- 抽帧数量: `{frame_count}`\n")
    f.write(f"- 分段数量: `{segments_count}`\n\n")
    f.write("## 文案预览\n\n")
    if preview:
        f.write(preview + "\n\n")
    else:
        f.write("(无识别文本)\n\n")
    f.write("## 产物文件\n\n")
    f.write(f"- 元信息: `{metadata_json}`\n")
    f.write(f"- 文案JSON: `{transcript_json}`\n")
    f.write(f"- 文案文本: `{transcript_txt}`\n")
    f.write(f"- 分段文本: `{segments_txt}`\n")
    f.write(f"- 抽帧目录: `{frames_dir}`\n")
    f.write(f"- OCR证据: `{ocr_tsv}`\n")
    f.write(f"- OCR命中: `{ocr_hits}`\n")

print(f"report={report_md}")
print(f"language={language}")
print(f"text_length={len(text)}")
PY

echo
echo "离线校验完成。"
echo "输出目录: $OUT_DIR"
echo "报告文件: $REPORT_MD"
