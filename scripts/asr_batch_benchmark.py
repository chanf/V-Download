#!/usr/bin/env python3
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

APP_PACKAGE = "com.vdown.app"
APP_ACTIVITY = "com.vdown.app/.MainActivity"
UIDUMP_DEV = "/sdcard/uidump_batch.xml"
UIDUMP_HOST = ".tmp_uidump_batch.xml"

CASES = [
    {"key": "zh", "lang_label": "中文（zh）", "video": "bench_zh.mp4", "expected_file": "video/asr_benchmark/expected_zh.txt"},
    {"key": "en", "lang_label": "英文（en）", "video": "bench_en.mp4", "expected_file": "video/asr_benchmark/expected_en.txt"},
    {"key": "pt", "lang_label": "葡语（pt）", "video": "bench_pt.mp4", "expected_file": "video/asr_benchmark/expected_pt.txt"},
    {"key": "es", "lang_label": "西语（es）", "video": "bench_es.mp4", "expected_file": "video/asr_benchmark/expected_es.txt"},
]

CASE_FILTER = {item.strip() for item in os.getenv("CASE_KEYS", "").split(",") if item.strip()}
if CASE_FILTER:
    CASES = [item for item in CASES if item["key"] in CASE_FILTER]


def run(cmd: str) -> str:
    proc = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    return proc.stdout


def adb(cmd: str) -> str:
    return run(f"adb {cmd}")


def wake_unlock():
    adb("shell input keyevent KEYCODE_WAKEUP >/dev/null 2>/dev/null")
    adb("shell wm dismiss-keyguard >/dev/null 2>/dev/null")


def parse_bounds(raw: str):
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw or "")
    if not match:
        return None
    return tuple(map(int, match.groups()))


def dump_root():
    adb(f"shell uiautomator dump {UIDUMP_DEV} >/dev/null 2>/dev/null")
    adb(f"pull {UIDUMP_DEV} {UIDUMP_HOST} >/dev/null 2>/dev/null")
    return ET.parse(UIDUMP_HOST).getroot()


def collect_nodes(root):
    return [(n, parse_bounds(n.attrib.get("bounds"))) for n in root.iter("node")]


def root_bounds(root):
    top = root.find(".//node")
    if top is None:
        return (0, 0, 1080, 2200)
    b = parse_bounds(top.attrib.get("bounds"))
    return b if b else (0, 0, 1080, 2200)


def tap_bounds(bounds):
    if not bounds:
        return
    x1, y1, x2, y2 = bounds
    x = (x1 + x2) // 2
    y = (y1 + y2) // 2
    adb(f"shell input tap {x} {y}")


def pick_clickable_container(nodes, target_bounds):
    if not target_bounds:
        return None
    tx1, ty1, tx2, ty2 = target_bounds
    candidates = []
    for node, bounds in nodes:
        if not bounds:
            continue
        x1, y1, x2, y2 = bounds
        if x1 <= tx1 and y1 <= ty1 and x2 >= tx2 and y2 >= ty2 and node.attrib.get("clickable") == "true":
            area = (x2 - x1) * (y2 - y1)
            candidates.append((area, bounds))
    candidates.sort(key=lambda item: item[0])
    if candidates:
        for _, bounds in candidates:
            if bounds != target_bounds:
                return bounds
        return candidates[0][1]
    return target_bounds


def tap_text(root, text, prefer_bottom=False, contains=False):
    nodes = collect_nodes(root)
    matches = []
    for node, bounds in nodes:
        node_text = node.attrib.get("text", "")
        ok = (text in node_text) if contains else (node_text == text)
        if ok and bounds:
            matches.append((node, bounds))
    if not matches:
        return False
    if prefer_bottom:
        matches.sort(key=lambda item: item[1][1], reverse=True)
    target = matches[0][1]
    tap_bounds(pick_clickable_container(nodes, target))
    return True


def tap_content_desc(root, content_desc, contains=False):
    nodes = collect_nodes(root)
    matches = []
    for node, bounds in nodes:
        desc = node.attrib.get("content-desc", "")
        ok = (content_desc in desc) if contains else (desc == content_desc)
        if ok and bounds:
            matches.append((node, bounds))
    if not matches:
        return False
    target = matches[0][1]
    tap_bounds(pick_clickable_container(nodes, target))
    return True


def get_all_texts(root):
    values = []
    for node, _ in collect_nodes(root):
        text = (node.attrib.get("text") or "").strip()
        if text:
            values.append(text)
    return values


def current_package(root):
    top = root.find(".//node")
    if top is None:
        return ""
    return top.attrib.get("package", "")


def swipe_to_top(root, times=5):
    x1, y1, x2, y2 = root_bounds(root)
    cx = (x1 + x2) // 2
    start_y = y1 + int((y2 - y1) * 0.35)
    end_y = y1 + int((y2 - y1) * 0.85)
    for _ in range(times):
        adb(f"shell input swipe {cx} {start_y} {cx} {end_y} 260")
        time.sleep(0.15)


def swipe_up_once(root):
    x1, y1, x2, y2 = root_bounds(root)
    cx = (x1 + x2) // 2
    start_y = y1 + int((y2 - y1) * 0.82)
    end_y = y1 + int((y2 - y1) * 0.36)
    adb(f"shell input swipe {cx} {start_y} {cx} {end_y} 260")


def swipe_down_once(root):
    x1, y1, x2, y2 = root_bounds(root)
    cx = (x1 + x2) // 2
    start_y = y1 + int((y2 - y1) * 0.36)
    end_y = y1 + int((y2 - y1) * 0.82)
    adb(f"shell input swipe {cx} {start_y} {cx} {end_y} 260")


def is_copy_tab_texts(texts):
    if "视频文案导出" in texts:
        return True
    if any(item.startswith("识别语言：") for item in texts):
        return True
    return False


def focus_copy_tab_top():
    for _ in range(4):
        root = dump_root()
        texts = get_all_texts(root)
        if is_copy_tab_texts(texts):
            swipe_to_top(root, times=6)
            time.sleep(0.3)
            return dump_root()

        if not tap_text(root, "文案提取", prefer_bottom=True):
            x1, y1, x2, y2 = root_bounds(root)
            width = x2 - x1
            height = y2 - y1
            # BottomNavigation 文案提取通常位于中间 tab，使用几何位置兜底点击。
            fallback_x = x1 + int(width * 0.5)
            fallback_y = y2 - int(height * 0.03)
            adb(f"shell input tap {fallback_x} {fallback_y}")
        time.sleep(0.8)

    root = dump_root()
    swipe_to_top(root, times=3)
    return dump_root()


def ensure_in_app_copy_tab():
    root = dump_root()
    for _ in range(6):
        pkg = current_package(root)
        if pkg == APP_PACKAGE:
            break
        if pkg.startswith("com.google.android.documentsui") or pkg.startswith("com.android.documentsui"):
            adb("shell input keyevent KEYCODE_BACK")
            time.sleep(0.5)
        elif pkg.startswith("com.android.permissioncontroller"):
            tap_text(root, "允许") or tap_text(root, "Allow")
            time.sleep(0.5)
        adb(f"shell am start -n {APP_ACTIVITY} >/dev/null")
        time.sleep(0.8)
        root = dump_root()
    return focus_copy_tab_top()


def ensure_language(lang_label: str):
    root = ensure_in_app_copy_tab()
    if not tap_text(root, "语言"):
        snapshot = " | ".join(get_all_texts(root)[:18])
        raise RuntimeError(f"找不到“语言”按钮。可见文本={snapshot}")
    time.sleep(0.8)
    root = dump_root()
    if not tap_text(root, lang_label):
        adb("shell input keyevent KEYCODE_BACK")
        time.sleep(0.4)
        root = ensure_in_app_copy_tab()
        if not tap_text(root, "语言"):
            snapshot = " | ".join(get_all_texts(root)[:18])
            raise RuntimeError(f"重试后仍找不到“语言”按钮。可见文本={snapshot}")
        time.sleep(0.8)
        root = dump_root()
        if not tap_text(root, lang_label):
            snapshot = " | ".join(get_all_texts(root)[:18])
            raise RuntimeError(f"语言下拉中未找到选项：{lang_label}。可见文本={snapshot}")
    time.sleep(0.9)
    root = ensure_in_app_copy_tab()
    line = next((t for t in get_all_texts(root) if t.startswith("识别语言：")), "")
    return line


def select_video(filename: str):
    root = ensure_in_app_copy_tab()
    if not tap_text(root, "选择视频"):
        raise RuntimeError("找不到“选择视频”按钮")
    time.sleep(1.0)

    entered_video_scope = False
    last_preview = ""
    stagnant_rounds = 0
    for attempt in range(1, 28):
        wake_unlock()
        root = dump_root()
        pkg = current_package(root)
        texts = get_all_texts(root)
        if attempt <= 8 or attempt % 5 == 0:
            preview = " | ".join(texts[:8])
            print(f"[PICK] attempt={attempt} pkg={pkg} preview={preview}", flush=True)

        if pkg == APP_PACKAGE:
            root = ensure_in_app_copy_tab()
            texts = get_all_texts(root)
            current_line = next((t for t in texts if t.startswith("当前视频：")), "")
            if filename in current_line:
                return current_line
            if tap_text(root, "选择视频"):
                time.sleep(0.8)
                continue

        if pkg.startswith("com.google.android.documentsui") or pkg.startswith("com.android.documentsui"):
            nodes = collect_nodes(root)
            target = None
            for node, bounds in nodes:
                node_text = node.attrib.get("text", "")
                desc = node.attrib.get("content-desc", "")
                if (node_text == filename or filename in node_text or filename in desc) and bounds:
                    target = bounds
                    break
            if target:
                print(f"[PICK] matched filename={filename} attempt={attempt}", flush=True)
                tap_bounds(pick_clickable_container(nodes, target))
                time.sleep(1.2)
            else:
                in_folder_listing = any(item.endswith(".mp4") for item in texts)
                has_vdown_entry = "v-down" in texts

                if (not in_folder_listing) and has_vdown_entry and tap_text(root, "v-down"):
                    entered_video_scope = True
                    time.sleep(0.9)
                    continue
                if (not in_folder_listing) and "Download" in texts and tap_text(root, "Download"):
                    entered_video_scope = True
                    time.sleep(0.9)
                    continue

                # 仅在首轮导航到“视频”来源，后续保持在同一列表内滑动查找。
                if attempt == 1 and not entered_video_scope:
                    opened = tap_content_desc(root, "显示根目录")
                    if opened:
                        time.sleep(0.6)
                        root = dump_root()
                    if tap_text(root, "视频"):
                        entered_video_scope = True
                        time.sleep(0.9)
                        continue

                preview_signature = " | ".join(texts[:10])
                if preview_signature == last_preview:
                    stagnant_rounds += 1
                else:
                    stagnant_rounds = 0
                last_preview = preview_signature

                if stagnant_rounds >= 2:
                    swipe_down_once(root)
                else:
                    swipe_up_once(root)
                time.sleep(0.7)
            continue

        if pkg.startswith("com.android.permissioncontroller"):
            if tap_text(root, "允许") or tap_text(root, "Allow"):
                time.sleep(0.8)
                continue

        adb(f"shell am start -n {APP_ACTIVITY} >/dev/null")
        time.sleep(1)

    raise RuntimeError(f"文件选择器未能选中 {filename}")


def detect_export_status(texts):
    for line in texts:
        if "文案导出完成" in line:
            return line, "success"
        if "文案导出失败" in line or "导出文案失败" in line:
            return line, "failure"

    diagnostics = next((line for line in texts if "【文案提取诊断日志】" in line), "")
    if diagnostics:
        if "阶段 = 文案导出成功" in diagnostics:
            return "文案导出完成（来自诊断日志）", "success"
        if "阶段 = 文案导出失败" in diagnostics:
            return "文案导出失败（来自诊断日志）", "failure"

    return None, None


def extract_transcript(texts):
    blockers = {
        "AI重构文案",
        "AI重构结果（双击复制）",
        "文案提取诊断日志（双击复制）",
        "分享文案 URL 提取",
        "URL 提取结果",
    }
    for idx, line in enumerate(texts):
        if "导出文案（双击复制）" not in line:
            continue
        for j in range(idx + 1, min(idx + 36, len(texts))):
            candidate = texts[j].strip()
            if not candidate:
                continue
            if candidate in blockers:
                break
            if candidate.startswith("提示："):
                continue
            return candidate
    return ""


def read_diagnostics(texts):
    return next((line for line in texts if "【文案提取诊断日志】" in line), "")


def run_case(case):
    result = {
        "key": case["key"],
        "lang_label": case["lang_label"],
        "video": case["video"],
        "expected": Path(case["expected_file"]).read_text(encoding="utf-8").strip(),
        "status": "unknown",
        "status_line": "",
        "actual": "",
        "diagnostics": "",
        "error": "",
    }

    try:
        wake_unlock()
        result["lang_line"] = ensure_language(case["lang_label"])
        result["selected_video_line"] = select_video(case["video"])

        root = ensure_in_app_copy_tab()
        if not tap_text(root, "导出"):
            raise RuntimeError("找不到“导出”按钮")

        status = None
        status_kind = None
        texts = []
        for i in range(1, 121):
            time.sleep(2)
            wake_unlock()
            root = ensure_in_app_copy_tab() if i % 4 == 1 else dump_root()
            if current_package(root) != APP_PACKAGE:
                root = ensure_in_app_copy_tab()
            texts = get_all_texts(root)
            status, status_kind = detect_export_status(texts)
            if i % 10 == 0:
                print(f"[TRACE] {case['key']} poll={i:03d} status={status or 'N/A'}", flush=True)
            if status:
                break

        if not status:
            raise RuntimeError("导出轮询超时（240秒）")

        result["status"] = status_kind or "unknown"
        result["status_line"] = status

        root = ensure_in_app_copy_tab()
        texts = get_all_texts(root)
        transcript = extract_transcript(texts)
        if not transcript:
            for _ in range(6):
                swipe_up_once(root)
                time.sleep(0.4)
                root = dump_root()
                texts = get_all_texts(root)
                transcript = extract_transcript(texts)
                if transcript:
                    break

        result["actual"] = transcript
        result["diagnostics"] = read_diagnostics(texts)

    except Exception as exc:
        result["status"] = "failure"
        result["error"] = str(exc)

    return result


def main():
    wake_unlock()
    adb(f"shell am force-stop {APP_PACKAGE}")
    adb(f"shell am start -n {APP_ACTIVITY} >/dev/null")
    time.sleep(1.2)

    results = []
    for case in CASES:
        print(f"[CASE] start {case['key']} {case['lang_label']} {case['video']}", flush=True)
        item = run_case(case)
        results.append(item)
        print(
            f"[CASE] done {case['key']} status={item.get('status')} line={item.get('status_line', '')} err={item.get('error', '')}",
            flush=True,
        )

    out_path = Path("video/asr_benchmark/results_raw.json")
    out_path.write_text(
        json.dumps({"generated_at": int(time.time() * 1000), "results": results}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"[DONE] wrote {out_path}", flush=True)


if __name__ == "__main__":
    main()
