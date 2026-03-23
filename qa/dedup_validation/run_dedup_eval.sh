#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || $# -lt 2 ]]; then
  cat <<'EOF'
用法:
  qa/dedup_validation/run_dedup_eval.sh <原视频路径> <去重后视频路径> [输出目录]

示例:
  qa/dedup_validation/run_dedup_eval.sh \
    "video/source.mp4" \
    "video/dedup.mp4" \
    "qa/dedup_validation/reports"
EOF
  exit 0
fi

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
ORIGINAL_VIDEO="$1"
CANDIDATE_VIDEO="$2"
OUTPUT_DIR="${3:-$ROOT_DIR/qa/dedup_validation/reports}"

python3 "$ROOT_DIR/qa/dedup_validation/dedup_similarity_eval.py" \
  --original "$ORIGINAL_VIDEO" \
  --candidate "$CANDIDATE_VIDEO" \
  --output-dir "$OUTPUT_DIR"

