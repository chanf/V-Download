#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

WORK_DIR="${WORK_DIR:-video/asr_benchmark}"
RESOLUTION="${RESOLUTION:-720x1280}"
FPS="${FPS:-25}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令: $1"
    exit 1
  fi
}

for cmd in say ffmpeg ffprobe python3; do
  require_cmd "$cmd"
done

mkdir -p "$WORK_DIR"

# 语种 -> voice / 背景色
keys=("zh" "en" "pt" "es")
voices=("Tingting" "Samantha" "Joana" "Paulina")
colors=("0x1F3B6D" "0x1E5A5A" "0x6A4B2A" "0x5A3E7A")

for i in "${!keys[@]}"; do
  key="${keys[$i]}"
  voice="${voices[$i]}"
  bg_color="${colors[$i]}"

  expected_txt="$WORK_DIR/expected_${key}.txt"
  aiff_file="$WORK_DIR/bench_${key}.aiff"
  wav_file="$WORK_DIR/bench_${key}.wav"
  srt_file="$WORK_DIR/bench_${key}.srt"
  out_mp4="$WORK_DIR/bench_${key}.mp4"

  if [[ ! -f "$expected_txt" ]]; then
    echo "缺少文案文件: $expected_txt"
    exit 1
  fi

  echo "[${key}] 1/4 生成语音..."
  say -v "$voice" -f "$expected_txt" -o "$aiff_file"

  echo "[${key}] 2/4 转换WAV..."
  ffmpeg -y -i "$aiff_file" -ar 16000 -ac 1 -c:a pcm_s16le "$wav_file" >/dev/null 2>&1

  duration="$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$wav_file")"

  echo "[${key}] 3/4 生成字幕SRT..."
  python3 - <<'PY' "$expected_txt" "$srt_file" "$duration"
import re
import sys
from pathlib import Path

expected_file, srt_file, duration_raw = sys.argv[1:4]
duration = float(duration_raw)
text = Path(expected_file).read_text(encoding='utf-8').strip()

def contains_cjk(raw: str) -> bool:
    return bool(re.search(r"[\u4e00-\u9fff]", raw))


def split_sentences(raw: str) -> list[str]:
    chunks = [item.strip() for item in re.findall(r"[^。！？.!?]+[。！？.!?]?", raw) if item.strip()]
    if chunks:
        return chunks
    fallback = raw.strip()
    return [fallback] if fallback else []


def wrap_inside_one_cue(sentence: str) -> str:
    sentence = sentence.strip()
    if not sentence:
        return sentence

    if contains_cjk(sentence):
        max_chars = 20

        # 优先按中文短语断句，避免把“，”单独放到下一行。
        phrases = [p for p in re.findall(r"[^，、；：,]+[，、；：,]?", sentence) if p]
        if not phrases:
            phrases = [sentence]

        lines = []
        current = ""
        for phrase in phrases:
            if len(current + phrase) <= max_chars:
                current += phrase
            else:
                if current:
                    lines.append(current)
                if len(phrase) <= max_chars:
                    current = phrase
                else:
                    # 超长短语才硬切
                    chunks = [phrase[i:i + max_chars] for i in range(0, len(phrase), max_chars)]
                    lines.extend(chunks[:-1])
                    current = chunks[-1]
        if current:
            lines.append(current)

        if not lines:
            lines = [sentence]
        return r"\N".join(lines)

    words = sentence.split()
    max_words = 8
    if len(words) <= max_words:
        return " ".join(words)
    lines = [" ".join(words[i:i + max_words]) for i in range(0, len(words), max_words)]
    return r"\N".join(lines)


sentences = split_sentences(text)
if not sentences:
    sentences = [text]

weights = [max(len(re.sub(r"\s+", "", s)), 1) for s in sentences]
count = len(sentences)
cue_gap = 0.08
available = duration - cue_gap * max(count - 1, 0)
min_seg = 1.2

if count * min_seg >= max(available, 0.1):
    seg_durations = [max(available / count, 0.1)] * count
else:
    base_total = min_seg * count
    remain = available - base_total
    weight_sum = sum(weights)
    seg_durations = [min_seg + remain * (w / weight_sum) for w in weights]

start = 0.0
blocks = []
for idx, (sentence, seg_dur) in enumerate(zip(sentences, seg_durations)):
    if idx == count - 1:
        end = duration
    else:
        end = min(duration, start + seg_dur)
    blocks.append((start, end, wrap_inside_one_cue(sentence)))
    start = min(duration, end + cue_gap)


def fmt(ts: float) -> str:
    ms = int(round(ts * 1000))
    h = ms // 3600000
    ms %= 3600000
    m = ms // 60000
    ms %= 60000
    s = ms // 1000
    ms %= 1000
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"

lines = []
for i, (st, ed, subtitle_text) in enumerate(blocks, 1):
    lines.append(str(i))
    lines.append(f"{fmt(st)} --> {fmt(ed)}")
    lines.append(subtitle_text)
    lines.append("")

Path(srt_file).write_text("\n".join(lines), encoding='utf-8')
print(f"written {srt_file}, segments={len(blocks)}")
PY

  echo "[${key}] 4/4 合成非黑屏字幕视频..."
  ffmpeg -y \
    -f lavfi -i "color=c=${bg_color}:s=${RESOLUTION}:r=${FPS}:d=${duration}" \
    -i "$wav_file" \
    -vf "drawbox=x=0:y=0:w=iw:h=88:color=black@0.55:t=fill,drawbox=x=0:y=ih-156:w=iw:h=156:color=black@0.32:t=fill,subtitles=${srt_file}:force_style='FontName=PingFang SC,FontSize=24,PrimaryColour=&H00F7F3EC,OutlineColour=&H00141414,BorderStyle=1,Outline=2,Shadow=0,Bold=1,MarginV=58,Alignment=2,Spacing=0.2'" \
    -shortest \
    -c:v libx264 -pix_fmt yuv420p -preset medium -crf 20 \
    -c:a aac -b:a 128k \
    "$out_mp4" >/dev/null 2>&1

  echo "[${key}] done -> $out_mp4"

done

echo "全部完成：$(ls -1 "$WORK_DIR"/bench_*.mp4 | tr '\n' ' ')"
