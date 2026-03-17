# Android 多平台视频下载器：产品与技术栈设计（V1）

## 1. 产品定位

### 1.1 产品目标
构建一个 Android APK，提供“授权内容下载”的统一体验：
- 支持通过系统分享、粘贴或手动输入 URL 创建下载任务
- 支持任务队列、断点续传、失败重试、批量管理
- 支持本地文件整理（按平台/作者/时间）

### 1.2 核心原则（必须）
- 仅下载用户拥有合法授权的内容
- 不提供绕过 DRM、加密签名、付费墙、登录风控的能力
- 默认以平台官方开放能力/API 为准
- 对高风险平台能力设置“灰度开关 + 审核策略”

### 1.3 目标用户
- 内容运营/剪辑用户：需要管理已授权素材
- 普通用户：希望离线观看自己可下载内容
- 自媒体团队：需要稳定下载队列与分类归档

## 2. 平台能力与合规边界

> 说明：下面是产品设计边界，不是法律意见。上线前需做法务审查与市场合规评估。

### 2.1 平台接入策略（V1）
- TikTok：优先官方/公开可下载能力，禁止逆向协议抓取
- 抖音：优先开放平台能力，禁止破解签名与私有接口
- Instagram：优先官方内容访问能力，禁止未授权抓取
- bilibili：优先开放平台或公开许可内容能力
- YouTube：严格遵守 YouTube API 与服务条款；V1 不做违规离线下载实现

### 2.2 功能分级
- L0（必做）：链接识别、任务下载、断点续传、本地管理
- L1（增强）：批量导入、清晰度选择、元数据命名模板
- L2（后续）：账号体系、跨设备同步、创作者工作流

## 3. 产品方案（PRD 简版）

### 3.1 关键用户流程
1. 用户从目标平台“分享”到本 App，或在 App 内粘贴/手动输入 URL
2. App 识别平台并校验“是否可下载 + 是否合规”
3. 用户选择清晰度/格式（若可选）
4. 进入下载队列（支持并发、暂停、恢复）
5. 下载完成后写入系统相册 `v-down` 目录并展示详情

### 3.2 核心页面
- 首页：最近任务、快速粘贴、手动输入框、剪贴板监听提示
- 任务页：进行中/已完成/失败，支持批量操作
- 文件页：按平台、时间、作者、标签筛选
- 详情页：封面、时长、分辨率、来源链接、导出
- 设置页：并发数、网络策略、命名规则、隐私与合规说明（成品目录固定为 `v-down`）

### 3.3 MVP 功能清单
- 入口能力：支持系统分享接收 URL、粘贴 URL、手动输入 URL
- 链接解析：识别 5 平台短链/长链
- 下载能力：HTTP(S) 下载、断点续传、失败重试、速度限制
- 任务系统：并发下载、优先级、前后台一致
- 存储管理：MediaStore 入库到系统相册 `v-down` 目录、清理缓存、重复文件检测
- 质量保障：崩溃恢复、下载状态持久化、日志追踪
- 合规网关：域名白名单、平台策略开关、可观测审计日志

### 3.4 非功能需求
- 兼容性：Android 8.0+
- 稳定性：异常退出后任务可恢复
- 性能：100 个任务队列下 UI 无明显卡顿
- 安全：本地敏感信息加密，日志脱敏

## 4. 技术栈设计

### 4.1 客户端技术选型
- 语言：Kotlin
- 架构：Clean Architecture + MVI
- UI：Jetpack Compose
- 异步：Kotlin Coroutines + Flow
- 网络：OkHttp + Retrofit + Kotlinx Serialization
- 后台任务：WorkManager（可延迟/可约束任务）
- 长时下载：Foreground Service（持续任务）
- 数据库：Room
- 依赖注入：Hilt
- 媒体处理：Media3（播放/元信息读取）+ FFmpeg（可选，后处理）
- 崩溃与分析：Firebase Crashlytics / Sentry（二选一）

### 4.2 逻辑架构（文字图）

```text
[UI/Compose]
   -> [Presentation: ViewModel + MVI State]
      -> [Domain: UseCases]
         -> [Data Layer]
            -> [Platform Adapter Router]
               -> [Adapter: tiktok/douyin/instagram/bilibili/youtube]
            -> [Download Engine]
               -> [WorkManager Scheduler]
               -> [Foreground Service Runner]
            -> [Local DB(Room) + FileStore(MediaStore)]
```

### 4.3 模块拆分
- `app`: UI 与导航
- `core-common`: 日志、结果封装、错误码、工具
- `core-network`: HTTP 客户端、拦截器、限流
- `core-storage`: Room、MediaStore、文件读写
- `feature-task`: 下载任务域模型与状态机
- `feature-adapter-*`: 各平台适配器模块
- `feature-settings`: 配置与策略
- `compliance-gateway`: 合规策略与开关中心

### 4.4 关键设计点
- 任务状态机：`Queued -> Validating -> Downloading -> Merging -> Completed/Failed`
- 幂等任务：同一链接+清晰度生成稳定 `taskHash`，避免重复下载
- 断点续传：基于 `Range` 与 `ETag/Last-Modified` 校验
- 网络策略：仅 Wi-Fi、低电量暂停、漫游限制
- 存储策略：成品视频统一写入 `Movies/v-down`（MediaStore `RELATIVE_PATH`），确保在相册可见
- 可观测性：统一事件埋点（解析耗时、重试次数、失败原因）

### 4.5 合规网关设计
- 规则引擎：按平台、地区、版本控制能力开关
- 能力白名单：仅允许审核通过的解析策略启用
- 审计日志：记录任务创建来源、规则命中、拦截原因
- 风险降级：平台策略变更时可远程关闭对应适配器

## 5. 数据模型（简化）

### 5.1 DownloadTask
- `id: String`
- `sourceUrl: String`
- `sourceEntry: enum(share|paste|manual_input)`
- `platform: enum`
- `status: enum`
- `progress: Int`
- `filePath: String?`
- `relativePath: String`（默认 `Movies/v-down`）
- `quality: String?`
- `createdAt: Long`
- `updatedAt: Long`
- `errorCode: String?`

### 5.2 PlatformPolicy
- `platform: enum`
- `enabled: Boolean`
- `regionAllowList: List<String>`
- `strategyVersion: String`
- `updatedAt: Long`

## 6. V1 里程碑（8 周）

### M1（第 1-2 周）
- 完成架构脚手架、任务模型、基础下载引擎
- 完成首页/任务页 UI 骨架

### M2（第 3-4 周）
- 接入 2 个低风险平台适配器（灰度）
- 完成断点续传、失败重试、通知栏控制

### M3（第 5-6 周）
- 接入合规网关、策略下发、审计日志
- 完成文件管理页、命名模板、批量操作

### M4（第 7-8 周）
- 稳定性优化、性能压测、灰度发布与回收机制
- 输出上线文档（隐私政策、用户协议、风控说明）

## 7. 主要风险与应对

### 7.1 平台规则变更风险（高）
- 应对：适配器插件化 + 远程开关 + 审计日志

### 7.2 商店审核风险（高）
- 应对：明确“仅授权内容下载”，保留证据链与禁用策略

### 7.3 后台任务被系统限制（中）
- 应对：WorkManager + 前台服务配合，关键任务可恢复

### 7.4 大文件失败率高（中）
- 应对：分段下载、断点续传、动态超时与重试退避

## 8. 下一步建议

1. 先确定 V1 平台优先级（建议从 2 个平台灰度）
2. 明确“可下载授权内容”判定策略（产品 + 法务共同定义）
3. 输出接口契约草案（Adapter SPI 与任务状态机事件）
4. 进入 Android 项目初始化与模块脚手架搭建

---

## 参考依据（官方文档）
- Android 前台服务（Foreground services）  
  https://developer.android.com/develop/background-work/services/foreground-services
- Android 后台任务：WorkManager  
  https://developer.android.com/topic/libraries/architecture/workmanager
- Android 长时任务与后台限制（含用户发起数据传输任务）  
  https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running  
  https://developer.android.com/develop/background-work/background-tasks/bg-work-restrictions#user-initiated-data-transfer-jobs
- Android 存储最佳实践（Scoped Storage / MediaStore）  
  https://developer.android.com/training/data-storage/use-cases
- Google Play All files access policy  
  https://support.google.com/googleplay/android-developer/answer/10467955
- YouTube API Services Developer Policies  
  https://developers.google.com/youtube/terms/developer-policies
