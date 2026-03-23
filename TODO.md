# 视频去重增强 TODO

## 目标
- 在现有 V1（速度微调 + 前后裁剪 + 重封装）基础上，提升去重强度与可控性。
- 保证每一项都可诊断、可回归、可复现。

## 阶段 A：当前可直接落地（本轮优先）

- [x] 新增去重方案清单文档（本文件）
- [x] 去重模板预设（轻度 / 均衡 / 强力）
- [x] 时间轴微扰（PTS 微扰）
- [x] 随机裁剪抖动（起始/结尾附加随机裁剪）
- [x] 封装扰动（轨道顺序扰动）
- [x] 诊断日志补充（输出最终生效参数）
- [x] 质量门禁（最小时长/输出完整性校验）
- [x] 哈希差异门禁（输出与原片 MD5 不一致）

## 阶段 B：需要“重编码引擎”支持（后续逐项推进）

- [ ] 画面微缩放 + 平移
- [ ] 镜像策略（可按模板启用）
- [ ] 亮度/对比度/饱和度微扰
- [ ] 音频增益/均衡微扰
- [ ] 轻量遮罩层（角标/边框扰动）

## 阶段 C：工程化能力

- [x] seed 可复现（支持手动输入或自动生成，并记录实际 seed）
- [ ] 模板版本号管理
- [ ] 一键 A/B 生成多版本
- [ ] 自动质量评分与自动回退
- [ ] 平台化参数（抖音/TikTok/小红书）配置

## 验收标准
- 生成文件可正常播放（音画同步，时长合理）
- 去重参数在诊断日志中可追溯
- 可用 `qa/dedup_validation` 一键输出重复度报告与图表
- 编译通过：`./gradlew :app:compileDebugKotlin`

-------------------


## 1. PTS 微扰（时间轴抖动）
这是不重编码实现去重的高级手段。通过微调每一帧的显示时间戳（Presentation Time Stamp），改变视频的瞬时码率曲线。

* **原理：** 给每个 `PTS` 加上一个极小的随机偏移 $s$（例如 $\pm 0.001$ 秒）。
* **FFmpeg 实现：**
    使用 `setpts` 滤镜（注意：滤镜通常需要触发重编码，但如果是简单的比特流转换可以尝试 `bsf`）。
    ```bash
    # 逻辑：让每一帧的时间戳在原基础上产生 0.1% 的随机缩放
    ffmpeg -i input.mp4 -vf "setpts=PTS*(1+random(0)*0.002-0.001)" -af "asetpts=PTS" output.mp4
    ```
* **诊断点：** 输出 `ffprobe -show_frames` 比较前后两份文件的 `pkt_pts_time` 序列。

## 2. 封装扰动（轨道与流元数据）
很多平台的去重算法会检查 MP4 容器内的 **Track ID** 和 **Handler Name**。

* **轨道顺序交换：** 如果视频有多个流（比如 1 路视频 + 2 路音频），改变它们的 Index 顺序。
* **元数据擦除与重写：**
    ```bash
    ffmpeg -i input.mp4 -c copy \
      -map_metadata -1 \
      -metadata title=" " \
      -metadata handler_name="libmp4" \
      -write_tmcd 0 \
      output.mp4
    ```
    > **技巧：** 修改 `-vtag`（例如从 `avc1` 改为 `h264`），这不影响播放，但会改变二进制特征。

## 3. 随机裁剪抖动（无重编码方案）
在不重编码的情况下，无法做到像素级的裁剪，但可以做到**时间维度的随机裁剪**。

* **实现建议：** 在你的模板预设（轻度/均衡/强力）中定义一个 `delta` 区间。
    * **轻度：** 前后随机切掉 $0.1 \sim 0.3$ 秒。
    * **强力：** 前后随机切掉 $1.0 \sim 2.0$ 秒。
* **工程化代码伪代码 (Kotlin)：**
    ```kotlin
    val startOffset = Random.nextDouble(0.1, 0.5)
    val durationReduction = Random.nextDouble(0.2, 0.8)
    // 最终命令：-ss $startOffset -t ${originalDuration - durationReduction}
    ```

## 4. 诊断日志与质量门禁 (Quality Gate)
为了保证“可回归、可复现”，建议你的日志系统记录以下 **“去重指纹”**：

| 字段 | 记录内容 | 目的 |
| :--- | :--- | :--- |
| **Seed** | `123456` | 用于根据相同种子复现随机抖动结果 |
| **PTS_Shift_Range** | `[-0.001, 0.001]` | 监控时间轴改动幅度 |
| **Duration_Delta** | `-0.45s` | 确保裁剪时长在安全范围内 |
| **MD5_Check** | `PASS` | 确保输出文件与原片 MD5 必须不同 |

---

## 5. 补充：二进制级“混淆” (Bitstream Filter)
在阶段 A 中，你可以加入 `noise` 比特流过滤器。它能在不解码的情况下，在码流层注入极微小的随机错误（通常播放器会自动纠错），但这会直接改变文件的 MD5 和哈希特征。

```bash
# 仅仅改变极小比例的比特位（千万分之一），通常不影响画质
ffmpeg -i input.mp4 -c copy -bsf:v noise=amount=-1 output.mp4
```

---

## 1. 核心逻辑：`VideoDedupEngine` (Kotlin 伪代码)

为了实现你要求的“可回归、可复现”，建议引入 `Seed` 机制。

```kotlin
class DedupTask(
    val inputPath: String,
    val outputPath: String,
    val seed: Long = System.currentTimeMillis() // 核心：可复现的种子
) {
    private val random = Random(seed)

    fun generateCommand(): List<String> {
        // 1. 随机偏移量计算 (阶段 A：前后裁剪)
        val startTrim = random.nextDouble(0.1, 0.5) // 随机切掉前 0.1~0.5s
        val endTrim = random.nextDouble(0.1, 0.3)   // 随机切掉后 0.1~0.3s
        
        // 2. 封装扰动参数
        val handlerName = "MediaHandler_${random.nextInt(1000, 9999)}"
        
        return listOf(
            "ffmpeg", "-y",
            "-ss", "%.3f".format(startTrim), // 起始裁剪
            "-i", inputPath,
            "-c", "copy",                    // 保持编码，仅重封装
            "-map_metadata", "-1",           // 擦除原始元数据
            "-metadata", "handler_name=$handlerName", // 写入伪造句柄
            "-metadata", "comment=${seed.toString(16)}", // 植入诊断 ID
            "-bsf:v", "noise=amount=-1",     // 二进制比特流微扰 (极微小)
            "-movflags", "faststart",        // 重新排布 MOOV atom
            outputPath
        )
    }
}
```

---

## 2. 重点技术细节说明

### A. PTS 微扰（无重编码 hack）
如果你想在 `copy` 模式下改变时间戳，标准的 `-vf setpts` 是行不通的（因为它需要解码）。
**替代方案：** 利用 FFmpeg 的 `-output_ts_offset`。
* **作用：** 给输出文件的整个时间轴加一个偏移。
* **命令：** `-output_ts_offset 0.0001`。这会导致全局 PTS 整体移动，虽然肉眼无感，但文件的二进制特征、索引表（stbl）会发生偏移。

### B. Bitstream Filter (BSF) 噪声
这是阶段 A 的“杀手锏”。
* **命令：** `-bsf:v noise=amount=-1`
* **效果：** 它会在视频码流中随机翻转极个别比特位。
* **风险：** `amount=-1` 是最轻微的。如果值过大（如 > 100），会导致画面花屏。在 `copy` 模式下，这是改变视频 MD5 最快且成本最低的方式。

### C. 轨道顺序扰动 (Map Shuffle)
如果视频包含多条流（视频、音频、字幕），手动调整它们的映射顺序：
* **默认：** `-map 0:v -map 0:a`
* **扰动：** `-map 0:a -map 0:v`（部分播放器/平台解析时会改变文件读取逻辑）。

---

## 3. 诊断日志（Diagnostic Log）示例
为了满足你“可追溯”的要求，每次执行后应生成一个 `.json` 或打印如下日志：

```text
[DEDUP_DIAGNOSTIC]
Task_ID: DUP_20260323_A01
Source_MD5: e99a...
Target_MD5: 7821... (Changed: YES)
Seed: 8847291
Applied_Params:
  - Start_Trim: 0.241s
  - End_Trim: 0.112s
  - Handler_Fake: MediaHandler_8842
  - BSF_Noise: -1
  - PTS_Offset: 0.0001s
Status: SUCCESS
```

---

## 4. 质量门禁 (Quality Gate) 建议

在 `qa/dedup_validation` 脚本中，你可以加入以下逻辑：

1.  **时长校验：** `abs(output_duration - input_duration) < 2.0s`。防止裁剪过度导致视频变短。
2.  **黑屏/静音检测：** 使用 `ffmpeg -f lavfi -i "movie=out.mp4,blackdetect=d=2" -f null -` 检查是否有超过 2 秒的黑屏。
3.  **相似度基准：** 使用 `ssim` 或 `psnr` 滤镜。
    * **预期：** 阶段 A 的 SSIM 应该接近 `1.0`（因为没重编码），如果数值波动巨大，说明封装逻辑损坏了帧索引。
