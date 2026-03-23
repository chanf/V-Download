#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import List, Optional, Sequence, Tuple


HTTP_URL_PATTERN = re.compile(r"https?://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+", re.IGNORECASE)
TRAILING_URL_CHARS = set("，。！？；：,.!?;:)]}>'\"")
LOG_SUCCESS_PATTERN = re.compile(r"(download success|下载完成)", re.IGNORECASE)
LOG_FAIL_PATTERN = re.compile(r"(download failed|下载失败)", re.IGNORECASE)
BOUNDS_PATTERN = re.compile(r"\[(\d+),(\d+)]\[(\d+),(\d+)]")


@dataclass
class CaseResult:
    index: int
    raw_input: str
    extracted_url: Optional[str]
    status: str
    elapsed_sec: float
    new_files: List[str]
    error_summary: str
    log_tail: str


def run_cmd(
    cmd: Sequence[str],
    timeout: float = 60.0,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        list(cmd),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=timeout,
        check=False,
    )


def build_adb_prefix(serial: Optional[str]) -> List[str]:
    prefix = ["adb"]
    if serial:
        prefix.extend(["-s", serial])
    return prefix


def adb(
    adb_prefix: Sequence[str],
    args: Sequence[str],
    timeout: float = 60.0,
) -> subprocess.CompletedProcess[str]:
    return run_cmd([*adb_prefix, *args], timeout=timeout)


def trim_url_tail(url: str) -> str:
    value = url.strip()
    while value and value[-1] in TRAILING_URL_CHARS:
        value = value[:-1]
    return value


def extract_first_url(text: str) -> Optional[str]:
    match = HTTP_URL_PATTERN.search(text)
    if not match:
        return None
    return trim_url_tail(match.group(0))


def parse_url_lines(url_file: Path) -> List[str]:
    lines: List[str] = []
    for raw in url_file.read_text(encoding="utf-8").splitlines():
        text = raw.strip()
        if not text:
            continue
        if text.startswith("#"):
            continue
        lines.append(text)
    return lines


def parse_connected_devices(devices_output: str) -> List[str]:
    result: List[str] = []
    for line in devices_output.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices attached"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            result.append(parts[0])
    return result


def ensure_adb_ready(adb_prefix: Sequence[str], serial: Optional[str]) -> None:
    proc = adb(adb_prefix, ["devices", "-l"], timeout=20)
    if proc.returncode != 0:
        raise RuntimeError(f"adb devices 执行失败:\n{proc.stdout}")
    devices = parse_connected_devices(proc.stdout)
    if serial:
        if serial not in devices:
            raise RuntimeError(f"未找到指定设备 serial={serial}。\nadb 输出:\n{proc.stdout}")
    elif not devices:
        raise RuntimeError(f"未检测到可用设备。\nadb 输出:\n{proc.stdout}")


def list_download_files(adb_prefix: Sequence[str], download_dir: str) -> List[str]:
    cmd = ["shell", "sh", "-c", f"ls -1 {download_dir} 2>/dev/null || true"]
    proc = adb(adb_prefix, cmd, timeout=20)
    files = []
    for line in proc.stdout.splitlines():
        name = line.strip()
        if not name:
            continue
        if name.startswith("No such file or directory"):
            continue
        files.append(name)
    return files


def dump_ui_xml(adb_prefix: Sequence[str], work_dir: Path) -> ET.Element:
    remote_path = "/sdcard/uidump_vdown_test.xml"
    local_path = work_dir / "uidump.xml"
    dump_proc = adb(adb_prefix, ["shell", "uiautomator", "dump", remote_path], timeout=30)
    if dump_proc.returncode != 0:
        raise RuntimeError(f"uiautomator dump 失败:\n{dump_proc.stdout}")
    pull_proc = adb(adb_prefix, ["pull", remote_path, str(local_path)], timeout=30)
    if pull_proc.returncode != 0:
        raise RuntimeError(f"uiautomator dump pull 失败:\n{pull_proc.stdout}")
    return ET.parse(local_path).getroot()


def parse_center_from_bounds(bounds: str) -> Optional[Tuple[int, int]]:
    match = BOUNDS_PATTERN.fullmatch(bounds.strip())
    if not match:
        return None
    x1, y1, x2, y2 = map(int, match.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_clickable_center(root: ET.Element, target_text: str) -> Optional[Tuple[int, int]]:
    parent_map = {}
    for parent in root.iter():
        for child in parent:
            parent_map[id(child)] = parent

    candidates = []
    for node in root.iter():
        text = (node.attrib.get("text") or "").strip()
        desc = (node.attrib.get("content-desc") or "").strip()
        if text == target_text or desc == target_text:
            candidates.append(node)

    for node in candidates:
        current = node
        while current is not None:
            clickable = (current.attrib.get("clickable") or "").lower() == "true"
            bounds = current.attrib.get("bounds") or ""
            if clickable and bounds:
                center = parse_center_from_bounds(bounds)
                if center:
                    return center
            current = parent_map.get(id(current))
    return None


def classify_log(log_text: str) -> Optional[str]:
    success_matches = list(LOG_SUCCESS_PATTERN.finditer(log_text))
    fail_matches = list(LOG_FAIL_PATTERN.finditer(log_text))
    if not success_matches and not fail_matches:
        return None
    if success_matches and not fail_matches:
        return "PASS"
    if fail_matches and not success_matches:
        return "FAIL"
    return "PASS" if success_matches[-1].start() > fail_matches[-1].start() else "FAIL"


def tail_log(log_text: str, max_lines: int = 60) -> str:
    lines = [line.rstrip() for line in log_text.splitlines() if line.strip()]
    if not lines:
        return ""
    return "\n".join(lines[-max_lines:])


def extract_error_summary(log_text: str) -> str:
    lines = [line.strip() for line in log_text.splitlines() if line.strip()]
    for line in reversed(lines):
        if "下载失败" in line or "download failed" in line.lower():
            return line
    return ""


def start_with_share_text(
    adb_prefix: Sequence[str],
    package_name: str,
    activity_name: str,
    text: str,
) -> None:
    adb(adb_prefix, ["shell", "am", "force-stop", package_name], timeout=20)
    args = [
        "shell",
        "am",
        "start",
        "-n",
        activity_name,
        "-a",
        "android.intent.action.SEND",
        "-t",
        "text/plain",
        "--es",
        "android.intent.extra.TEXT",
        text,
    ]
    proc = adb(adb_prefix, args, timeout=30)
    if proc.returncode != 0:
        raise RuntimeError(f"启动分享意图失败:\n{proc.stdout}")


def click_start_download(
    adb_prefix: Sequence[str],
    work_dir: Path,
    retries: int = 3,
) -> None:
    last_error = ""
    for _ in range(retries):
        root = dump_ui_xml(adb_prefix, work_dir)
        center = find_clickable_center(root, "开始下载")
        if center:
            x, y = center
            tap_proc = adb(adb_prefix, ["shell", "input", "tap", str(x), str(y)], timeout=10)
            if tap_proc.returncode == 0:
                return
            last_error = tap_proc.stdout
        time.sleep(1.0)
    raise RuntimeError(f"未能定位或点击“开始下载”按钮。{last_error}")


def run_single_case(
    index: int,
    raw_text: str,
    extracted_url: Optional[str],
    adb_prefix: Sequence[str],
    package_name: str,
    activity_name: str,
    download_dir: str,
    timeout_sec: int,
    poll_interval_sec: float,
    work_dir: Path,
) -> CaseResult:
    before_files = set(list_download_files(adb_prefix, download_dir))
    adb(adb_prefix, ["logcat", "-c"], timeout=20)

    start = time.time()
    try:
        start_with_share_text(adb_prefix, package_name, activity_name, raw_text)
        time.sleep(2.0)
        click_start_download(adb_prefix, work_dir)
    except Exception as exc:
        elapsed = time.time() - start
        return CaseResult(
            index=index,
            raw_input=raw_text,
            extracted_url=extracted_url,
            status="FAIL",
            elapsed_sec=round(elapsed, 2),
            new_files=[],
            error_summary=str(exc),
            log_tail="",
        )

    status: Optional[str] = None
    latest_log = ""
    pass_by_new_file = False
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        log_proc = adb(adb_prefix, ["logcat", "-d", "-s", "VDownDownload:V", "VDownDownloadUi:V"], timeout=20)
        latest_log = log_proc.stdout
        status = classify_log(latest_log)
        if status == "FAIL":
            break
        if status == "PASS":
            break

        # 日志为空或未命中时，兜底采用“目录新增文件即通过”判定。
        current_files = set(list_download_files(adb_prefix, download_dir))
        if current_files - before_files:
            status = "PASS"
            pass_by_new_file = True
            break
        time.sleep(poll_interval_sec)

    if status is None:
        status = "TIMEOUT"

    after_files = set(list_download_files(adb_prefix, download_dir))
    new_files = sorted(after_files - before_files)

    # 部分机型上日志先到达、文件稍后落盘：给目录检测一个短暂补偿窗口。
    if not new_files:
        settle_deadline = time.time() + min(8.0, max(2.0, poll_interval_sec * 4))
        while time.time() < settle_deadline:
            time.sleep(1.0)
            settled_files = set(list_download_files(adb_prefix, download_dir))
            settled_new = sorted(settled_files - before_files)
            if settled_new:
                after_files = settled_files
                new_files = settled_new
                pass_by_new_file = True
                break
    elapsed = time.time() - start

    error_summary = ""
    if status == "FAIL":
        error_summary = extract_error_summary(latest_log) or "日志未命中失败关键字，请查看 log_tail。"
    if status == "TIMEOUT" and new_files:
        status = "PASS"
        pass_by_new_file = True
    elif status == "TIMEOUT":
        error_summary = f"在 {timeout_sec}s 内未检测到成功/失败关键日志。"
    if pass_by_new_file and status == "PASS":
        error_summary = "目录检测到新增文件，按下载成功处理。"

    return CaseResult(
        index=index,
        raw_input=raw_text,
        extracted_url=extracted_url,
        status=status,
        elapsed_sec=round(elapsed, 2),
        new_files=new_files,
        error_summary=error_summary,
        log_tail=tail_log(latest_log),
    )


def render_markdown_report(
    results: List[CaseResult],
    report_path: Path,
    started_at: datetime,
    ended_at: datetime,
    url_file: Path,
) -> None:
    total = len(results)
    passed = sum(1 for r in results if r.status == "PASS")
    failed = sum(1 for r in results if r.status == "FAIL")
    timeout = sum(1 for r in results if r.status == "TIMEOUT")
    avg_sec = round(sum(r.elapsed_sec for r in results) / total, 2) if total else 0.0

    lines: List[str] = []
    lines.append("# 下载自动化测试报告")
    lines.append("")
    lines.append(f"- 开始时间: {started_at.isoformat(timespec='seconds')}")
    lines.append(f"- 结束时间: {ended_at.isoformat(timespec='seconds')}")
    lines.append(f"- 输入文件: `{url_file}`")
    lines.append(f"- 用例总数: {total}")
    lines.append(f"- 通过: {passed}")
    lines.append(f"- 失败: {failed}")
    lines.append(f"- 超时: {timeout}")
    lines.append(f"- 平均耗时(秒): {avg_sec}")
    lines.append("")
    lines.append("## 结果明细")
    lines.append("")
    lines.append("| # | 状态 | 耗时(s) | 提取URL | 新增文件 | 失败摘要 |")
    lines.append("|---|---|---:|---|---|---|")
    for item in results:
        url = item.extracted_url or "-"
        new_files = "<br>".join(item.new_files[:3]) if item.new_files else "-"
        summary = item.error_summary.replace("|", "\\|") if item.error_summary else "-"
        lines.append(
            f"| {item.index} | {item.status} | {item.elapsed_sec:.2f} | {url} | {new_files} | {summary} |"
        )

    lines.append("")
    lines.append("## 失败/超时日志摘录")
    lines.append("")
    for item in results:
        if item.status == "PASS":
            continue
        lines.append(f"### Case {item.index} - {item.status}")
        lines.append("")
        lines.append(f"- 原始输入: `{item.raw_input}`")
        lines.append(f"- 提取URL: `{item.extracted_url or '-'}`")
        if item.error_summary:
            lines.append(f"- 摘要: {item.error_summary}")
        lines.append("")
        lines.append("```text")
        lines.append(item.log_tail or "(无日志)")
        lines.append("```")
        lines.append("")

    report_path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    script_path = Path(__file__).resolve()
    work_dir = script_path.parent
    repo_root = work_dir.parents[1]
    default_url_file = repo_root / "url.txt"
    default_output_dir = work_dir / "reports"

    parser = argparse.ArgumentParser(description="V-Download 批量下载自动化测试工具")
    parser.add_argument("--url-file", type=Path, default=default_url_file, help="URL 输入文件路径")
    parser.add_argument("--output-dir", type=Path, default=default_output_dir, help="报告输出目录")
    parser.add_argument("--serial", type=str, default=None, help="adb 设备 serial")
    parser.add_argument("--package", type=str, default="com.vdown.app", help="应用包名")
    parser.add_argument("--activity", type=str, default="com.vdown.app/.MainActivity", help="应用 Activity")
    parser.add_argument("--download-dir", type=str, default="/sdcard/DCIM/v-down", help="手机下载目录")
    parser.add_argument("--timeout", type=int, default=240, help="单条用例超时秒数")
    parser.add_argument("--poll-interval", type=float, default=2.0, help="轮询日志间隔秒")
    parser.add_argument("--limit", type=int, default=0, help="仅执行前 N 条，0 表示全部")
    parser.add_argument("--dry-run", action="store_true", help="仅解析用例，不执行下载")
    args = parser.parse_args()

    if not args.url_file.exists():
        raise FileNotFoundError(f"url 文件不存在: {args.url_file}")

    raw_lines = parse_url_lines(args.url_file)
    if args.limit and args.limit > 0:
        raw_lines = raw_lines[: args.limit]
    if not raw_lines:
        raise RuntimeError("未读取到可执行用例，请检查 url 文件内容。")

    cases = [(idx + 1, text, extract_first_url(text)) for idx, text in enumerate(raw_lines)]
    print(f"[INFO] 读取用例: {len(cases)} 条")

    if args.dry_run:
        for idx, text, url in cases:
            print(f"[DRY] #{idx} url={url or '-'} raw={text}")
        return 0

    adb_prefix = build_adb_prefix(args.serial)
    ensure_adb_ready(adb_prefix, args.serial)

    started_at = datetime.now()
    run_dir = args.output_dir / f"run_{started_at.strftime('%Y%m%d_%H%M%S')}"
    run_dir.mkdir(parents=True, exist_ok=True)

    results: List[CaseResult] = []
    for idx, text, url in cases:
        print(f"[RUN] case#{idx} start")
        result = run_single_case(
            index=idx,
            raw_text=text,
            extracted_url=url,
            adb_prefix=adb_prefix,
            package_name=args.package,
            activity_name=args.activity,
            download_dir=args.download_dir,
            timeout_sec=args.timeout,
            poll_interval_sec=args.poll_interval,
            work_dir=run_dir,
        )
        results.append(result)
        print(f"[RUN] case#{idx} {result.status} elapsed={result.elapsed_sec}s new_files={len(result.new_files)}")

    ended_at = datetime.now()

    json_path = run_dir / "report.json"
    md_path = run_dir / "report.md"
    json_path.write_text(
        json.dumps(
            {
                "started_at": started_at.isoformat(timespec="seconds"),
                "ended_at": ended_at.isoformat(timespec="seconds"),
                "url_file": str(args.url_file),
                "total": len(results),
                "pass": sum(1 for r in results if r.status == "PASS"),
                "fail": sum(1 for r in results if r.status == "FAIL"),
                "timeout": sum(1 for r in results if r.status == "TIMEOUT"),
                "cases": [asdict(item) for item in results],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    render_markdown_report(results, md_path, started_at, ended_at, args.url_file)

    print(f"[DONE] JSON: {json_path}")
    print(f"[DONE] Markdown: {md_path}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[INTERRUPTED] 用户中断。")
        sys.exit(130)
    except Exception as exc:
        print(f"[ERROR] {exc}")
        sys.exit(1)
