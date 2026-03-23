# V-Download 回归测试报告（新手机重跑）

- 报告日期：2026-03-19（CST）
- 报告编号：`TR-20260319-HWPLR-RERUN`
- 项目目录：`/Users/feng/Work/V-Download`
- 执行方式：ADB 真机自动化 + 日志校验 + 文件落盘校验

## 1. 测试目标

在新接入手机（华为 `PLR-AL50`）上，完整重跑上一轮核心回归项，验证以下能力是否稳定：

1. 分享文案中的 URL 自动提取
2. 抖音 note/图文链接快速失败并给出明确提示
3. 抖音 video 链接可正常解析、下载并保存到相册目录
4. 下载目录不产生 0 字节脏文件

## 2. 测试范围

本次包含：

1. 设备连接与应用安装验证
2. Vosk 模型推送与目录可见性验证
3. 下载核心链路功能回归（提链、失败分流、成功下载）

本次不包含：

1. 全量 `url.txt` 批量回归
2. 文案提取准确率回归
3. LLM 重构功能回归

## 3. 测试环境

### 3.1 主机环境

1. 系统：macOS Darwin 25.2.0 (arm64)
2. ADB：`1.0.41`（platform-tools `36.0.0-13206524`）

### 3.2 手机环境

1. 设备型号：`PLR-AL50`
2. Android 版本：`12`
3. SDK：`31`
4. ADB 状态：`device`（已授权）

### 3.3 应用版本

1. 包名：`com.vdown.app`
2. `versionName=0.1.14`
3. `versionCode=15`

## 4. 前置准备与结果

### 4.1 APK 安装

- 命令：`adb install -r app/build/outputs/apk/release/app-release.apk`
- 结果：`Success`

### 4.2 Vosk 模型推送

受华为系统限制，`/sdcard/Android/data/com.vdown.app/files/...` 路径通过 ADB 直接写入会报 `Operation not permitted`。  
本次采用可写公共目录：`/sdcard/vosk-models`。

已推送模型与大小：

1. `/sdcard/vosk-models/vosk-model-small-cn-0.22`（65M）
2. `/sdcard/vosk-models/vosk-model-small-en-us-0.15`（68M）
3. `/sdcard/vosk-models/vosk-model-small-es-0.42`（58M）
4. `/sdcard/vosk-models/vosk-model-small-pt-0.3`（51M）

## 5. 用例执行与结果

| 用例ID | 用例名称 | 预期结果 | 实际结果 | 结论 |
|---|---|---|---|---|
| TC-01 | 混合文案提链 | 从分享文本中提取 URL 到输入框 | 文本 `foo https://v.douyin.com/PPLTFfBxpes/ bar` 成功提取为纯 URL | 通过 |
| TC-02 | 抖音 note 链接失败分流 | 快速识别为图文/音频并明确失败提示 | 日志出现 `detected note/audio-only`，UI 错误文案为“图文/音频内容（非视频）” | 通过 |
| TC-03 | 抖音 video 链接下载 | 解析直链并完成下载落盘 | `HxX4F9AKp9E` 成功下载，日志出现 `download success` 与 `VDownDownloadUi download success` | 通过 |
| TC-04 | 脏文件校验 | 下载目录不出现 0 字节文件 | `find ... -size 0` 返回为空 | 通过 |

## 6. 关键证据（日志摘录）

### 6.1 TC-02：note 链接失败分流

测试链接：`https://v.douyin.com/PPLTFfBxpes/`

```text
03-19 22:03:01.394 I/VDownDownload: douyin resolve fetch page=https://v.douyin.com/PPLTFfBxpes/
03-19 22:03:02.701 I/VDownDownload: douyin resolve fetched page bytes=116340 from=https://v.douyin.com/PPLTFfBxpes/
03-19 22:03:02.714 I/VDownDownload: douyin resolve detected note/audio-only page=https://v.douyin.com/PPLTFfBxpes/
03-19 22:03:02.716 E/VDownDownloadUi: download failed ... 下载失败：该抖音链接是图文/音频内容（非视频），当前仅支持视频下载。
```

### 6.2 TC-03：video 链接下载成功

测试链接：`https://v.douyin.com/HxX4F9AKp9E/`

```text
03-19 22:10:02.969 I/VDownDownload: douyin resolve parsed direct=https://aweme.snssdk.com/aweme/v1/play/?video_id=...
03-19 22:10:04.131 I/VDownDownload: open connection ... mime=video/mp4 contentLength=297980180
03-19 22:10:16.420 I/VDownDownload: download success bytes=297980180 mime=video/mp4 source=https://v.douyin.com/HxX4F9AKp9E/ ...
03-19 22:10:16.422 I/VDownDownloadUi: download success url=https://v.douyin.com/HxX4F9AKp9E/ output=content://media/external/video/media/4931 bytes=297980180 ...
```

## 7. 产物校验

下载目录：`/sdcard/Movies/v-down`

关键文件：

1. `HxX4F9AKp9E.mp4`（297980180 字节）
2. `HxX4F9AKp9E (1).mp4`（297980180 字节）

0 字节文件检查：

```text
adb shell find /sdcard/Movies/v-down -type f -size 0 -print
```

结果：无输出（即无 0 字节异常文件）。

## 8. 结论

本次在新手机（华为 `PLR-AL50`）上的核心回归项全部通过，下载主流程可用，错误分流与提示可用，文件落盘可用。

## 9. 风险与说明

1. 华为设备对 `Android/data` 的 ADB 直写限制较严格，模型推送应优先使用公共目录（例如 `/sdcard/vosk-models`）。
2. 自动化抓 UI dump 时，部分动态提示可能受滚动位置影响，关键结论以 `VDownDownload` / `VDownDownloadUi` 日志与落盘文件为准。

## 10. 后续建议

1. 下一步执行 `url.txt` 全量回归，输出“URL -> 成功/失败原因”矩阵。
2. 将本报告中的关键日志模式固化为脚本化回归规则，降低人工复检成本。
