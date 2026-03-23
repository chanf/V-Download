#!/usr/bin/env python3
"""视频去重重复度评估工具（离线）。

输入原视频与处理后视频，输出：
1. JSON 原始指标
2. Markdown 人工可读报告

评估维度（V1）：
- 画面感知重复度：基于 8x8 灰度 aHash 的双向最小汉明距离
- 音频重复度：基于短时能量序列的时移相关性
- 结构对比：时长、体积、分辨率
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import statistics
import subprocess
import sys
from array import array
from pathlib import Path
from typing import Any


def run_cmd(cmd: list[str]) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)


def require_tool(name: str) -> None:
    try:
        run_cmd(["which", name])
    except Exception as exc:  # noqa: BLE001
        raise SystemExit(f"[ERROR] 未找到依赖工具：{name}") from exc


def ffprobe_metadata(video_path: Path) -> dict[str, Any]:
    cmd = [
        "ffprobe",
        "-v",
        "error",
        "-show_streams",
        "-show_format",
        "-print_format",
        "json",
        str(video_path),
    ]
    result = run_cmd(cmd)
    return json.loads(result.stdout.decode("utf-8", errors="replace"))


def parse_duration_seconds(meta: dict[str, Any]) -> float:
    fmt = meta.get("format", {}) or {}
    duration = fmt.get("duration")
    try:
        return float(duration)
    except Exception:  # noqa: BLE001
        return -1.0


def parse_size_bytes(meta: dict[str, Any]) -> int:
    fmt = meta.get("format", {}) or {}
    size = fmt.get("size")
    try:
        return int(size)
    except Exception:  # noqa: BLE001
        return -1


def parse_resolution(meta: dict[str, Any]) -> tuple[int, int]:
    for stream in meta.get("streams", []) or []:
        if (stream.get("codec_type") or "").lower() == "video":
            width = int(stream.get("width") or 0)
            height = int(stream.get("height") or 0)
            return width, height
    return 0, 0


def extract_ahashes(video_path: Path, fps: float, max_frames: int) -> list[int]:
    vf = f"fps={fps},scale=8:8:flags=area,format=gray"
    cmd = [
        "ffmpeg",
        "-v",
        "error",
        "-i",
        str(video_path),
        "-vf",
        vf,
        "-frames:v",
        str(max_frames),
        "-f",
        "rawvideo",
        "-pix_fmt",
        "gray",
        "pipe:1",
    ]
    result = run_cmd(cmd)
    raw = result.stdout
    frame_size = 64
    frame_count = len(raw) // frame_size
    hashes: list[int] = []
    for i in range(frame_count):
        frame = raw[i * frame_size : (i + 1) * frame_size]
        mean = sum(frame) / frame_size
        value = 0
        for pixel in frame:
            value = (value << 1) | (1 if pixel >= mean else 0)
        hashes.append(value)
    return hashes


def hamming_ratio_64(a: int, b: int) -> float:
    xor_value = a ^ b
    try:
        count = xor_value.bit_count()  # Python 3.8+
    except AttributeError:
        count = bin(xor_value).count("1")
    return count / 64.0


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    rank = max(0.0, min(1.0, p)) * (len(ordered) - 1)
    low = int(math.floor(rank))
    high = int(math.ceil(rank))
    if low == high:
        return ordered[low]
    frac = rank - low
    return ordered[low] * (1.0 - frac) + ordered[high] * frac


def directed_min_distance_series(source: list[int], target: list[int]) -> tuple[float, list[float]]:
    if not source or not target:
        return 1.0, []
    mins: list[float] = []
    for s in source:
        best = 1.0
        for t in target:
            d = hamming_ratio_64(s, t)
            if d < best:
                best = d
                if best <= 0.0:
                    break
        mins.append(best)
    return (statistics.fmean(mins) if mins else 1.0), mins


def visual_similarity(source_hashes: list[int], target_hashes: list[int]) -> dict[str, Any]:
    if not source_hashes or not target_hashes:
        return {
            "similarity": 0.0,
            "distance": 1.0,
            "source_to_target_distance": 1.0,
            "target_to_source_distance": 1.0,
            "source_to_target_series": [],
            "target_to_source_series": [],
            "distance_p50": 0.0,
            "distance_p95": 0.0,
        }
    d_st, s2t = directed_min_distance_series(source_hashes, target_hashes)
    d_ts, t2s = directed_min_distance_series(target_hashes, source_hashes)
    d = (d_st + d_ts) / 2.0
    merged = s2t + t2s
    return {
        "similarity": max(0.0, 1.0 - d),
        "distance": d,
        "source_to_target_distance": d_st,
        "target_to_source_distance": d_ts,
        "source_to_target_series": s2t,
        "target_to_source_series": t2s,
        "distance_p50": percentile(merged, 0.5),
        "distance_p95": percentile(merged, 0.95),
    }


def extract_audio_envelope(
    video_path: Path,
    sample_rate: int,
    window_ms: int,
    max_seconds: int,
) -> list[float]:
    cmd = [
        "ffmpeg",
        "-v",
        "error",
        "-i",
        str(video_path),
        "-vn",
        "-ac",
        "1",
        "-ar",
        str(sample_rate),
        "-t",
        str(max_seconds),
        "-f",
        "s16le",
        "pipe:1",
    ]
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode != 0:
        return []
    raw = result.stdout
    if not raw:
        return []

    samples = array("h")
    samples.frombytes(raw)
    if sys.byteorder != "little":
        samples.byteswap()

    window = max(1, int(sample_rate * (window_ms / 1000.0)))
    envelope: list[float] = []
    total = len(samples)
    for start in range(0, total, window):
        segment = samples[start : start + window]
        if not segment:
            continue
        power = sum(float(v) * float(v) for v in segment) / len(segment)
        envelope.append(math.sqrt(power))

    if not envelope:
        return []

    mean = statistics.fmean(envelope)
    variance = statistics.fmean((x - mean) ** 2 for x in envelope)
    std = math.sqrt(variance)
    if std < 1e-9:
        return [0.0 for _ in envelope]
    return [(x - mean) / std for x in envelope]


def shifted_correlation_series(
    a: list[float],
    b: list[float],
    max_shift_ratio: float = 0.2,
    max_shift_cap: int = 600,
    min_overlap: int = 20,
) -> tuple[float | None, list[tuple[int, float]]]:
    if not a or not b:
        return None, []

    max_shift = min(max_shift_cap, int(max(len(a), len(b)) * max_shift_ratio))
    best: float | None = None
    series: list[tuple[int, float]] = []

    for shift in range(-max_shift, max_shift + 1):
        if shift >= 0:
            a_start = shift
            b_start = 0
        else:
            a_start = 0
            b_start = -shift

        overlap = min(len(a) - a_start, len(b) - b_start)
        if overlap < min_overlap:
            continue

        dot = 0.0
        norm_a = 0.0
        norm_b = 0.0
        for i in range(overlap):
            va = a[a_start + i]
            vb = b[b_start + i]
            dot += va * vb
            norm_a += va * va
            norm_b += vb * vb

        if norm_a <= 1e-12 or norm_b <= 1e-12:
            continue
        corr = dot / math.sqrt(norm_a * norm_b)
        series.append((shift, corr))
        if best is None or corr > best:
            best = corr

    return best, series


def classify_repeat_score(score: float) -> str:
    if score >= 85:
        return "高重复（去重效果弱）"
    if score >= 70:
        return "中高重复（去重效果一般）"
    if score >= 55:
        return "中等重复（有一定去重效果）"
    return "低重复（去重效果明显）"


def generate_charts(result: dict[str, Any], output_root: Path) -> dict[str, str]:
    charts: dict[str, str] = {}
    try:
        import matplotlib

        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception:  # noqa: BLE001
        return charts

    metrics = result["metrics"]
    visual_s2t = metrics.get("visual_source_to_target_series", [])
    visual_t2s = metrics.get("visual_target_to_source_series", [])
    audio_curve = metrics.get("audio_corr_curve", [])

    # 图1：核心指标柱状图
    score_bar = output_root / "chart_scores.png"
    fig, ax = plt.subplots(figsize=(7, 4))
    names = ["Visual", "Audio", "Repeat/100"]
    values = [
        float(metrics.get("visual_similarity", 0.0)),
        float(metrics.get("audio_similarity", 0.0)),
        float(metrics.get("repeat_score", 0.0)) / 100.0,
    ]
    colors = ["#3b82f6", "#10b981", "#f97316"]
    ax.bar(names, values, color=colors)
    ax.set_ylim(0, 1.0)
    ax.set_ylabel("Score (0~1)")
    ax.set_title("Dedup Similarity Scores")
    for idx, val in enumerate(values):
        ax.text(idx, min(0.98, val + 0.02), f"{val:.3f}", ha="center", va="bottom", fontsize=9)
    fig.tight_layout()
    fig.savefig(score_bar, dpi=140)
    plt.close(fig)
    charts["score_bar"] = score_bar.name

    # 图2：帧最小距离分布
    frame_hist = output_root / "chart_frame_distance_hist.png"
    merged_frame_distance = [float(v) for v in (visual_s2t + visual_t2s) if isinstance(v, (int, float))]
    if merged_frame_distance:
        fig, ax = plt.subplots(figsize=(7, 4))
        ax.hist(merged_frame_distance, bins=20, color="#6366f1", alpha=0.85)
        ax.set_xlabel("Frame Min Distance (0~1)")
        ax.set_ylabel("Count")
        ax.set_title("Frame Distance Distribution")
        p50 = float(metrics.get("visual_distance_p50", 0.0))
        p95 = float(metrics.get("visual_distance_p95", 0.0))
        ax.axvline(p50, color="#ef4444", linestyle="--", linewidth=1.5, label=f"P50={p50:.3f}")
        ax.axvline(p95, color="#f59e0b", linestyle="--", linewidth=1.5, label=f"P95={p95:.3f}")
        ax.legend(loc="upper right")
        fig.tight_layout()
        fig.savefig(frame_hist, dpi=140)
        plt.close(fig)
        charts["frame_distance_hist"] = frame_hist.name

    # 图3：音频时移相关性曲线
    audio_curve_path = output_root / "chart_audio_corr_curve.png"
    if audio_curve:
        xs = [int(item[0]) for item in audio_curve if isinstance(item, (list, tuple)) and len(item) == 2]
        ys = [float(item[1]) for item in audio_curve if isinstance(item, (list, tuple)) and len(item) == 2]
        if xs and ys and len(xs) == len(ys):
            fig, ax = plt.subplots(figsize=(7, 4))
            ax.plot(xs, ys, color="#06b6d4", linewidth=1.5)
            ax.set_xlabel("Shift (windows)")
            ax.set_ylabel("Correlation (-1~1)")
            ax.set_title("Audio Shifted Correlation Curve")
            ax.axhline(0.0, color="#94a3b8", linewidth=1.0)
            raw_corr = float(metrics.get("audio_corr_raw", 0.0))
            ax.text(
                0.02,
                0.95,
                f"max corr={raw_corr:.4f}",
                transform=ax.transAxes,
                va="top",
                ha="left",
                fontsize=9,
                bbox={"facecolor": "white", "alpha": 0.7, "edgecolor": "#cbd5e1"},
            )
            fig.tight_layout()
            fig.savefig(audio_curve_path, dpi=140)
            plt.close(fig)
            charts["audio_corr_curve"] = audio_curve_path.name

    return charts


def build_markdown_report(result: dict[str, Any]) -> str:
    original = result["original"]
    candidate = result["candidate"]
    metrics = result["metrics"]
    verdict = result["verdict"]
    charts = result.get("charts", {})

    return "\n".join(
        [
            "# 视频去重重复度评估报告",
            "",
            f"- 评估时间：`{result['generated_at']}`",
            f"- 原视频：`{original['path']}`",
            f"- 去重后视频：`{candidate['path']}`",
            "",
            "## 结构对比",
            "",
            "| 指标 | 原视频 | 去重后 |",
            "|---|---:|---:|",
            f"| 时长（秒） | {original['duration_seconds']:.3f} | {candidate['duration_seconds']:.3f} |",
            f"| 文件大小（字节） | {original['size_bytes']} | {candidate['size_bytes']} |",
            f"| 分辨率 | {original['resolution']} | {candidate['resolution']} |",
            "",
            "## 重复度指标",
            "",
            f"- 画面相似度（0~1）：`{metrics['visual_similarity']:.4f}`",
            f"- 画面距离（0~1，越大越不重复）：`{metrics['visual_distance']:.4f}`",
            f"- 帧距离 P50（0~1）：`{metrics.get('visual_distance_p50', 0.0):.4f}`",
            f"- 帧距离 P95（0~1）：`{metrics.get('visual_distance_p95', 0.0):.4f}`",
            f"- 音频相似度（0~1）：`{metrics['audio_similarity']:.4f}`",
            f"- 综合重复度评分（0~100，越高越重复）：`{metrics['repeat_score']:.2f}`",
            "",
            "## 图表",
            "",
            (
                f"![核心指标柱状图]({charts['score_bar']})"
                if "score_bar" in charts
                else "- 未生成核心指标柱状图（请检查 matplotlib 环境）"
            ),
            (
                f"![帧距离分布图]({charts['frame_distance_hist']})"
                if "frame_distance_hist" in charts
                else "- 未生成帧距离分布图（样本不足或图表环境不可用）"
            ),
            (
                f"![音频相关性曲线图]({charts['audio_corr_curve']})"
                if "audio_corr_curve" in charts
                else "- 未生成音频相关性曲线图（音频样本不足或图表环境不可用）"
            ),
            "",
            "## 结论",
            "",
            f"- 判定：**{verdict['label']}**",
            f"- 建议：{verdict['advice']}",
            "",
            "## 方法说明（V1）",
            "",
            "- 画面：按固定采样率抽帧，缩放到 8x8 灰度后计算 aHash，并做双向最小汉明距离。",
            "- 音频：提取单声道短时能量序列，做时移相关性最大值估计。",
            "- 本报告用于工程对比与回归，不替代平台真实审核结果。",
            "",
        ]
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="视频去重重复度评估工具")
    parser.add_argument("--original", required=True, help="原视频路径")
    parser.add_argument("--candidate", required=True, help="去重后视频路径")
    parser.add_argument(
        "--output-dir",
        default="qa/dedup_validation/reports",
        help="报告输出目录（默认 qa/dedup_validation/reports）",
    )
    parser.add_argument("--fps", type=float, default=1.0, help="画面抽帧频率，默认 1.0")
    parser.add_argument("--max-frames", type=int, default=900, help="最大抽帧数，默认 900")
    parser.add_argument("--audio-sample-rate", type=int, default=8000, help="音频采样率，默认 8000")
    parser.add_argument("--audio-window-ms", type=int, default=250, help="音频能量窗口（ms），默认 250")
    parser.add_argument("--audio-max-seconds", type=int, default=300, help="音频分析最大秒数，默认 300")
    args = parser.parse_args()

    require_tool("ffmpeg")
    require_tool("ffprobe")

    original = Path(args.original).expanduser().resolve()
    candidate = Path(args.candidate).expanduser().resolve()
    if not original.exists():
        raise SystemExit(f"[ERROR] 原视频不存在：{original}")
    if not candidate.exists():
        raise SystemExit(f"[ERROR] 去重后视频不存在：{candidate}")

    timestamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    output_root = Path(args.output_dir).expanduser().resolve() / f"run_{timestamp}"
    output_root.mkdir(parents=True, exist_ok=True)

    original_meta = ffprobe_metadata(original)
    candidate_meta = ffprobe_metadata(candidate)

    original_hashes = extract_ahashes(original, fps=args.fps, max_frames=args.max_frames)
    candidate_hashes = extract_ahashes(candidate, fps=args.fps, max_frames=args.max_frames)
    visual = visual_similarity(original_hashes, candidate_hashes)

    original_audio_env = extract_audio_envelope(
        original,
        sample_rate=args.audio_sample_rate,
        window_ms=args.audio_window_ms,
        max_seconds=args.audio_max_seconds,
    )
    candidate_audio_env = extract_audio_envelope(
        candidate,
        sample_rate=args.audio_sample_rate,
        window_ms=args.audio_window_ms,
        max_seconds=args.audio_max_seconds,
    )
    corr, audio_corr_curve = shifted_correlation_series(original_audio_env, candidate_audio_env)
    if corr is None:
        audio_similarity = 0.0
    else:
        audio_similarity = max(0.0, min(1.0, (corr + 1.0) / 2.0))

    repeat_score = 100.0 * (0.7 * visual["similarity"] + 0.3 * audio_similarity)
    label = classify_repeat_score(repeat_score)
    advice = (
        "建议提高去重扰动强度（速度、裁剪、镜像/调色）并复测。"
        if repeat_score >= 70
        else "当前去重有效，建议结合平台实际发布反馈继续校准阈值。"
    )

    original_duration = parse_duration_seconds(original_meta)
    candidate_duration = parse_duration_seconds(candidate_meta)
    duration_ratio = (
        (candidate_duration / original_duration)
        if original_duration > 0 and candidate_duration > 0
        else -1.0
    )

    payload: dict[str, Any] = {
        "generated_at": dt.datetime.now().isoformat(timespec="seconds"),
        "original": {
            "path": str(original),
            "duration_seconds": original_duration,
            "size_bytes": parse_size_bytes(original_meta),
            "resolution": parse_resolution(original_meta),
            "frame_hash_count": len(original_hashes),
            "audio_envelope_count": len(original_audio_env),
        },
        "candidate": {
            "path": str(candidate),
            "duration_seconds": candidate_duration,
            "size_bytes": parse_size_bytes(candidate_meta),
            "resolution": parse_resolution(candidate_meta),
            "frame_hash_count": len(candidate_hashes),
            "audio_envelope_count": len(candidate_audio_env),
        },
        "metrics": {
            "visual_similarity": visual["similarity"],
            "visual_distance": visual["distance"],
            "visual_source_to_target_distance": visual["source_to_target_distance"],
            "visual_target_to_source_distance": visual["target_to_source_distance"],
            "visual_source_to_target_series": visual["source_to_target_series"],
            "visual_target_to_source_series": visual["target_to_source_series"],
            "visual_distance_p50": visual["distance_p50"],
            "visual_distance_p95": visual["distance_p95"],
            "audio_similarity": audio_similarity,
            "audio_corr_raw": corr if corr is not None else 0.0,
            "audio_corr_curve": audio_corr_curve,
            "duration_ratio": duration_ratio,
            "repeat_score": repeat_score,
        },
        "verdict": {
            "label": label,
            "advice": advice,
        },
        "config": {
            "fps": args.fps,
            "max_frames": args.max_frames,
            "audio_sample_rate": args.audio_sample_rate,
            "audio_window_ms": args.audio_window_ms,
            "audio_max_seconds": args.audio_max_seconds,
        },
    }
    payload["charts"] = generate_charts(payload, output_root)

    json_path = output_root / "result.json"
    md_path = output_root / "report.md"
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    md_path.write_text(build_markdown_report(payload), encoding="utf-8")

    print("[DONE] 去重重复度评估完成")
    print(f"[OUT] JSON: {json_path}")
    print(f"[OUT] Markdown: {md_path}")
    print(
        "[SUMMARY] "
        f"visual={payload['metrics']['visual_similarity']:.4f}, "
        f"audio={payload['metrics']['audio_similarity']:.4f}, "
        f"score={payload['metrics']['repeat_score']:.2f}, "
        f"verdict={payload['verdict']['label']}"
    )


if __name__ == "__main__":
    main()
