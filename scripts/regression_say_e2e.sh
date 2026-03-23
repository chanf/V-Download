#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

EXPECTED_TEXT="${EXPECTED_TEXT:-这是文案提取准确性回归测试。第一句：今天北京天气不错。第二句：我们正在验证语音转文字结果。第三句：一二三四五六七八九十。}"
SAY_VOICE="${SAY_VOICE:-Ting-Ting}"
APP_PACKAGE="${APP_PACKAGE:-com.vdown.app}"
APP_ACTIVITY="${APP_ACTIVITY:-com.vdown.app/.MainActivity}"
WORK_DIR="${WORK_DIR:-video}"
AIFF_FILE="$WORK_DIR/say_test.aiff"
WAV_FILE="$WORK_DIR/say_test_16k.wav"
MP4_FILE="$WORK_DIR/say_test.mp4"
DEVICE_MP4_PATH="/sdcard/DCIM/v-down/say_test.mp4"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "缺少命令: $1"
    exit 1
  fi
}

for cmd in say ffmpeg adb python3; do
  require_cmd "$cmd"
done

mkdir -p "$WORK_DIR"

echo "[1/5] 生成 say 测试音频..."
say -v "$SAY_VOICE" "$EXPECTED_TEXT" -o "$AIFF_FILE"

echo "[2/5] 转换 WAV 并生成测试视频..."
ffmpeg -y -i "$AIFF_FILE" -ar 16000 -ac 1 -c:a pcm_s16le "$WAV_FILE" >/dev/null 2>&1
ffmpeg -y -f lavfi -i color=c=black:s=720x1280:d=13.5 -i "$WAV_FILE" -shortest -c:v libx264 -pix_fmt yuv420p -c:a aac -b:a 128k "$MP4_FILE" >/dev/null 2>&1

echo "[3/5] 推送视频到手机..."
adb wait-for-device
adb push "$MP4_FILE" "$DEVICE_MP4_PATH" >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$DEVICE_MP4_PATH" >/dev/null || true

echo "[4/5] 自动执行 App 端到端文案提取并比对..."
EXPECTED_TEXT="$EXPECTED_TEXT" APP_PACKAGE="$APP_PACKAGE" APP_ACTIVITY="$APP_ACTIVITY" python3 - <<'PY'
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

expected = os.environ["EXPECTED_TEXT"]
app_package = os.environ["APP_PACKAGE"]
app_activity = os.environ["APP_ACTIVITY"]
uidump_host = ".tmp_uidump_regression.xml"
uidump_dev = "/sdcard/uidump_regression.xml"


def run(cmd: str) -> str:
    return subprocess.run(
        cmd,
        shell=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    ).stdout


def adb(cmd: str) -> str:
    return run(f"adb {cmd}")


def parse_bounds(raw: str):
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw or "")
    if not match:
        return None
    return tuple(map(int, match.groups()))


def wake_unlock():
    adb("shell input keyevent KEYCODE_WAKEUP >/dev/null 2>/dev/null")
    adb("shell wm dismiss-keyguard >/dev/null 2>/dev/null")
    adb("shell input swipe 500 1800 500 400 120 >/dev/null 2>/dev/null")


def dump_root():
    adb(f"shell uiautomator dump {uidump_dev} >/dev/null 2>/dev/null")
    adb(f"pull {uidump_dev} {uidump_host} >/dev/null 2>/dev/null")
    return ET.parse(uidump_host).getroot()


def collect_nodes(root):
    result = []
    for node in root.iter("node"):
        result.append((node, parse_bounds(node.attrib.get("bounds"))))
    return result


def root_bounds(root):
    top = root.find(".//node")
    if top is None:
        return (0, 0, 1080, 1920)
    bb = parse_bounds(top.attrib.get("bounds"))
    return bb if bb else (0, 0, 1080, 1920)


def tap_bounds(bounds):
    if not bounds:
        return
    x1, y1, x2, y2 = bounds
    cx = (x1 + x2) // 2
    cy = (y1 + y2) // 2
    adb(f"shell input tap {cx} {cy}")


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
    for _, bounds in candidates:
        if bounds != target_bounds:
            return bounds
    return candidates[0][1] if candidates else target_bounds


def find_text_nodes(nodes, text):
    return [(node, bounds) for node, bounds in nodes if node.attrib.get("text") == text]


def swipe_to_top(root, times=4):
    x1, y1, x2, y2 = root_bounds(root)
    cx = (x1 + x2) // 2
    start_y = y1 + int((y2 - y1) * 0.4)
    end_y = y1 + int((y2 - y1) * 0.8)
    for _ in range(times):
        adb(f"shell input swipe {cx} {start_y} {cx} {end_y} 250")


def swipe_up_once(root):
    x1, y1, x2, y2 = root_bounds(root)
    cx = (x1 + x2) // 2
    start_y = y1 + int((y2 - y1) * 0.78)
    end_y = y1 + int((y2 - y1) * 0.42)
    adb(f"shell input swipe {cx} {start_y} {cx} {end_y} 250")


def focus_copy_tab_top(swipes=4):
    root = dump_root()
    if not tap_text(root, "文案提取", prefer_bottom=True):
        x1, y1, x2, y2 = root_bounds(root)
        adb(f"shell input tap {(x1 + x2) // 2} {y2 - 20}")
    time.sleep(0.5)
    root = dump_root()
    swipe_to_top(root, times=swipes)
    time.sleep(0.3)
    return dump_root()


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


def extract_transcript_text(texts, expected_text):
    if expected_text in texts:
        return expected_text

    section_ends = {
        "AI重构文案",
        "AI重构结果（双击复制）",
        "文案提取诊断日志（双击复制）",
        "分享文案 URL 提取",
        "URL 提取结果"
    }
    for idx, line in enumerate(texts):
        if "导出文案（双击复制）" not in line:
            continue
        for j in range(idx + 1, min(idx + 24, len(texts))):
            candidate = texts[j].strip()
            if not candidate:
                continue
            if candidate in section_ends:
                break
            if candidate.startswith("提示："):
                continue
            return candidate
    return ""


def print_ui_snapshot(title, texts):
    print(title)
    for idx, line in enumerate(texts[:30], 1):
        cleaned = line.replace("\n", " | ").strip()
        if cleaned:
            print(f"[UI-{idx:02d}] {cleaned}")


def tap_text(root, text, prefer_bottom=False):
    nodes = collect_nodes(root)
    matches = find_text_nodes(nodes, text)
    if not matches:
        return False
    if prefer_bottom:
        matches.sort(key=lambda item: (item[1][1] if item[1] else -1), reverse=True)
    target_bounds = matches[0][1]
    tap_bounds(pick_clickable_container(nodes, target_bounds))
    return True


def get_all_texts(root):
    return [node.attrib.get("text", "") for node, _ in collect_nodes(root)]


def current_package(root):
    top = root.find(".//node")
    if top is None:
        return ""
    return top.attrib.get("package", "")


def select_say_test_in_picker(root):
    nodes = collect_nodes(root)
    target_bounds = None
    for node, bounds in nodes:
        if node.attrib.get("text") == "say_test.mp4" and bounds:
            target_bounds = bounds
            break
    if target_bounds is None:
        for node, bounds in nodes:
            if "say_test.mp4" in node.attrib.get("content-desc", "") and bounds:
                target_bounds = bounds
                break
    if target_bounds is None:
        return False
    tap_bounds(pick_clickable_container(nodes, target_bounds))
    return True


def fail(message):
    print(f"[FAIL] {message}")
    sys.exit(1)


wake_unlock()
adb(f"shell am force-stop {app_package}")
adb(f"shell am start -n {app_activity} >/dev/null")
time.sleep(1)
root = focus_copy_tab_top(swipes=6)

if "视频文案导出" not in get_all_texts(root):
    fail("无法进入“文案提取”页")

texts = get_all_texts(root)
current_line = next((line for line in texts if line.startswith("当前视频：")), "")
print(f"[INFO] CURRENT_BEFORE={current_line}")

if "say_test.mp4" not in current_line:
    if not tap_text(root, "重新选择视频"):
        fail("找不到“重新选择视频”按钮")
    time.sleep(1)
    root = dump_root()
    if not select_say_test_in_picker(root):
        fail("文件选择器中未找到 say_test.mp4")
    for attempt in range(1, 7):
        time.sleep(1)
        wake_unlock()
        root = dump_root()
        texts = get_all_texts(root)
        current_line = next((line for line in texts if line.startswith("当前视频：")), "")
        if "say_test.mp4" in current_line:
            break

        package = current_package(root)
        if package.startswith("com.google.android.documentsui"):
            if attempt <= 2:
                select_say_test_in_picker(root)
            else:
                adb("shell input keyevent KEYCODE_BACK")
        elif package != app_package:
            adb(f"shell am start -n {app_activity} >/dev/null")
        else:
            root = focus_copy_tab_top(swipes=4)

    root = focus_copy_tab_top(swipes=4)
    texts = get_all_texts(root)
    current_line = next((line for line in texts if line.startswith("当前视频：")), "")
    print(f"[INFO] CURRENT_AFTER={current_line}")
    if "say_test.mp4" not in current_line:
        fail(f"回到 App 后未选中 say_test.mp4（package={current_package(root)}）")

if not tap_text(root, "导出文案"):
    fail("找不到“导出文案”按钮")

status = None
done_sec = None
status_kind = None
last_texts = []
for i in range(1, 61):
    time.sleep(2)
    wake_unlock()
    if i % 3 == 1:
        root = focus_copy_tab_top(swipes=4)
    else:
        root = dump_root()
    package = current_package(root)
    if package and package != app_package:
        adb(f"shell am start -n {app_activity} >/dev/null")
        time.sleep(1)
        root = focus_copy_tab_top(swipes=4)
    texts = get_all_texts(root)
    last_texts = texts
    status, status_kind = detect_export_status(texts)
    if i % 5 == 0:
        current_line = next((line for line in texts if line.startswith("当前视频：")), "")
        print(f"[TRACE] poll={i:02d} status={(status or 'N/A')} video={(current_line or 'N/A')}")
    if status:
        done_sec = i * 2
        break

if status is None:
    print_ui_snapshot("[ERROR] 导出轮询超时，最近可见 UI 文本：", last_texts)
    fail("导出未在 120 秒内结束")

root = focus_copy_tab_top(swipes=5)
texts = get_all_texts(root)
transcript = extract_transcript_text(texts, expected)
if not transcript:
    for _ in range(4):
        swipe_up_once(root)
        time.sleep(0.4)
        root = dump_root()
        texts = get_all_texts(root)
        transcript = extract_transcript_text(texts, expected)
        if transcript:
            break

if not transcript:
    print_ui_snapshot("[ERROR] 未提取到导出文案，最近可见 UI 文本：", texts)
    fail("未提取到导出文案文本")

print(f"[INFO] DONE_AT_SEC={done_sec}")
print(f"[INFO] STATUS={status}")
print(f"[INFO] EXPECTED={expected}")
print(f"[INFO] ACTUAL={transcript}")

if status_kind == "failure" or "失败" in status:
    fail("导出状态为失败")
if transcript != expected:
    fail("文案与期望不一致")

print("[PASS] 文案与标点完全一致")
PY

echo "[5/5] 回归测试通过"
