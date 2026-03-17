# V-Download

一个面向 Android 的视频下载工具，支持从分享文本或手动输入中提取链接，解析视频源并下载到系统相册 `Movies/v-down`。

## 项目角色
- 产品经理：`feng`（`chanf@me.com`）
- 程序员：`Codex`（基于 GPT-5 的编码代理工具）

## 项目目标
- 统一链接入口：支持系统分享和手动粘贴
- 支持导入 `cookies.txt`（Netscape 格式）提升受登录态影响链接的可下载成功率
- 下载文件统一保存到相册目录 `v-down`
- 提供清晰错误提示，降低定位问题成本

## 当前功能（已实现）

### 1. URL 输入与提取
- 支持从 `ACTION_SEND` 分享文本接收链接
- 支持手动输入或粘贴链接
- 支持从“混合文案”中提取 URL（例如抖音/小红书分享口令文本）
- 自动清理链接尾部标点
- 针对主流视频站短链自动 `http -> https`，避免 Android 明文流量拦截

### 2. 下载能力
- 下载进度实时展示
- 下载完成后保存到系统相册 `Movies/v-down`
- Android 10+ 走 MediaStore（Scoped Storage）
- Android 9 及以下走外部存储目录写入（需写权限）
- 下载失败会删除未完成文件，避免相册残留脏数据

### 3. Cookies 导入与状态展示
- 支持导入 Chrome 导出的 Netscape `cookies.txt`
- 识别并筛选视频站点相关 Cookie
- 过期 Cookie 自动跳过
- 按视频源展示“可用/过期”统计
- 已存储有效 Cookie 可跨多次导入保留（不会因新文件缺失而丢失历史有效项）

### 4. 错误反馈体验
- 统一中文报错
- 遇到 `text/html` 伪视频响应会提示“可能是网页/鉴权页/风控页”
- 底部错误卡片支持双击复制到剪贴板，方便你发我定位

## 视频源支持矩阵（当前状态）

### 已实现页面解析并可尝试下载
- TikTok
- 抖音
- Instagram
- YouTube
- 小红书
- X（Twitter）

### 部分支持
- bilibili：当前主要支持 Cookie 识别与相关域名处理；复杂页面直链解析仍在完善中

说明：不同平台经常调整风控与页面结构，实际成功率会受链接公开性、地区网络、Cookie 时效影响。

## 关键限制与边界
- 仅用于下载你有权限保存的内容
- 不提供 DRM/付费内容绕过能力
- 若提示需要 Cookie，请先导入最新导出的 `cookies.txt`

## 使用说明

### 下载视频
1. 在 App 内粘贴链接，或从其他 App 分享到 V-Download
2. 点击“加入待下载列表”
3. 点击“开始下载”
4. 成功后在系统相册 `v-down` 查看

### 导入 Cookies
1. 在 Chrome 使用 `Get cookies.txt LOCALLY` 导出
2. 打开 V-Download，点击“选择并导入 cookies.txt”
3. 查看导入结果中的视频源可用状态

## 权限说明
- `INTERNET`：网络请求
- `WRITE_EXTERNAL_STORAGE`（仅 Android 9 及以下）：写入本地视频文件

## 技术栈
- 语言：Kotlin
- UI：Jetpack Compose + Material 3
- 并发：Kotlin Coroutines
- 本地存储：Room
- 构建：Gradle Kotlin DSL + KSP
- 最低系统：Android 8.0（API 26）

## 项目结构
```text
app/
  src/main/java/com/vdown/app/
    MainActivity.kt
    download/VideoDownloadRepository.kt
    cookie/
      AppDatabase.kt
      CookieDao.kt
      CookieEntity.kt
      CookieImportRepository.kt
      NetscapeCookieParser.kt
      VideoCookieSources.kt
    ui/
      VDownloadApp.kt
      CookieImportViewModel.kt
```

## 构建与打包

### 常用命令
- Debug 编译：`./gradlew :app:compileDebugKotlin`
- 单元测试：`./gradlew :app:testDebugUnitTest`
- Release 打包：`./gradlew :app:assembleRelease`

### Release 签名
通过项目根目录 `keystore.properties` 提供：
- `KEY_ALIAS`
- `KEY_PASSWORD`
- `STORE_FILE`
- `STORE_PASSWORD`

## 质量检查（提交前建议）
- 快速检查：`compileDebugKotlin + 核心单测`
- 网络相关长测建议单独运行，避免阻塞常规提交

## 版本约定
- 默认每次打包仅升级 `versionName` 第三段（patch）
- 同时递增 `versionCode` 以保证可覆盖安装

