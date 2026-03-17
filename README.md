# V-Download

Android 视频下载器（开发中），目标是提供统一的链接导入、任务下载与本地保存体验，支持多平台可授权内容下载。

## 1. 产品设计

### 1.1 产品目标
- 支持通过系统分享、粘贴或手动输入 URL 创建下载任务
- 支持导入 `cookies.txt`（Netscape 格式）用于需要登录态的请求
- 下载完成后保存到系统相册 `Movies/v-down`

### 1.2 目标平台
- TikTok
- YouTube（严格遵守平台政策，不做违规离线能力）
- bilibili
- 抖音
- Instagram
- 小红书

### 1.3 核心流程
1. 用户从外部 App 分享链接到 V-Download，或在 App 内手动输入 URL
2. App 识别链接并入队任务
3. 用户可在设置页导入 `cookies.txt`
4. 下载器执行任务，完成后写入相册 `v-down`

### 1.4 当前原型已实现
- URL 入口：系统分享 + 手动输入 + 队列管理
- 实际下载：HTTP 下载、进度反馈、保存到相册 `Movies/v-down`
- Cookies 导入：文件选择、解析、入库、导入结果反馈
- 视频站点筛选：导入后自动筛选 TikTok/YouTube/bilibili/抖音/Instagram/小红书 相关 cookies
- 可用性标记：按视频源标注可用/过期，并展示累计可用 cookies 状态
- 启动权限：App 启动时申请文件写入权限（Android 9 及以下）
- 过期提示：导入时若检测到过期 Cookie，会明确提示并自动跳过

### 1.5 合规边界
- 仅允许下载具备合法授权的内容
- 不提供绕过 DRM、加密签名、付费墙、风控的能力
- 优先使用平台官方能力与公开许可能力

## 2. 技术栈设计

### 2.1 客户端技术栈
- 语言：Kotlin
- UI：Jetpack Compose + Material 3
- 架构：Clean Architecture（逐步演进）
- 状态管理：ViewModel + 单向状态流（MVI 风格）
- 并发：Kotlin Coroutines
- 本地数据库：Room
- 构建：Gradle Kotlin DSL

### 2.2 架构分层（当前）
- `ui/`：页面、状态展示、用户交互
- `cookie/`：Cookie 解析、导入、入库、过期清理
- `MainActivity`：系统分享入口、启动权限申请

后续将扩展为：
- `download-engine`：任务调度、断点续传、失败重试
- `platform-adapter-*`：平台解析适配器
- `storage`：MediaStore 写入 `Movies/v-down`

### 2.3 Cookies 设计
- 导入格式：Netscape `cookies.txt`（支持 `#HttpOnly_`）
- 解析字段：domain / includeSubDomains / path / secure / expires / name / value
- 视频源筛选：仅处理 TikTok/YouTube/bilibili/抖音/Instagram/小红书 相关域名
- 入库策略：仅存储可用项，`domain + path + name` 唯一键覆盖更新
- 历史保留：新文件缺失的历史有效 cookies 不会被删除
- 过期策略：导入时跳过过期项，按视频源标记并提示用户重新导出

### 2.4 权限策略
- Android 10+：使用 Scoped Storage / MediaStore，无需旧写权限
- Android 9 及以下：启动时申请 `WRITE_EXTERNAL_STORAGE`

## 3. 项目结构

```text
app/
  src/main/java/com/vdown/app/
    MainActivity.kt
    cookie/
      AppDatabase.kt
      CookieDao.kt
      CookieEntity.kt
      CookieImportRepository.kt
      CookieModels.kt
      NetscapeCookieParser.kt
    ui/
      CookieImportViewModel.kt
      VDownloadApp.kt
      theme/
  src/main/AndroidManifest.xml
  src/test/java/com/vdown/app/cookie/

docs/
  product-tech-design.md
```

## 4. 本地开发

### 4.1 环境要求
- JDK 17+
- Android SDK（已安装对应 Build Tools / Platform）

### 4.2 构建命令
- Debug 包：`./gradlew assembleDebug`
- Release 包：`./gradlew assembleRelease`
- 单元测试：`./gradlew testDebugUnitTest`

### 4.3 Release 签名
项目通过根目录 `keystore.properties` 读取签名配置（该文件已被 `.gitignore` 忽略）。

需要字段：
- `KEY_ALIAS`
- `KEY_PASSWORD`
- `STORE_FILE`
- `STORE_PASSWORD`

## 5. 里程碑

### M1（已完成）
- 项目脚手架（Compose + Room）
- URL 导入入口与基础下载链路
- Cookies 导入与过期提示

### M2（进行中）
- 下载任务增强（重试、断点续传）
- 平台级解析与可下载链接提取

### M3（待开始）
- 多平台适配器与端到端联调
- 合规网关与策略开关
