# 77625 Playground · Android 客户端

把 `https://ly.77625.xyz/#/playground` 的对话能力封装成一个可独立安装的 Android 应用：
**功能全部沿用原站点，界面按聊天软件重做。**

聊天页做成一屏锁定的聊天形态：消息区独立滚动、输入框常驻底部、会话列表收进左侧抽屉、返回键逐层退栈。

---

## 1. 为什么是「壳 + 皮肤」而不是原生重写

| 事实 | 结论 |
| --- | --- |
| 站点是 hash 路由单页应用，`#/playground` 由 `playground.js` 在客户端渲染 | 页面结构只能在运行时拿到，静态复刻必然失真 |
| 登录态是站点自己的 Cookie（`/api/auth/me` 未带 Cookie 返回 401） | 会话只能由 WebView 的 Cookie 罐维持 |
| 需求明确：不得接入/替换/新增 API，不得自定义登录 | 一旦原生重写聊天界面，就必须自己调接口、自己存凭证，直接违约 |
| 站点 CSP 为 `frame-ancestors 'none'` + `X-Frame-Options: DENY` | 不能 iframe 嵌套，只能用 WebView 顶层加载 |

所以工程的做法是：**让真实站点在 WebView 里跑，再注入一层只改布局与视觉的皮肤**。
所有请求、鉴权跳转、SSE 流式输出、会话管理仍然是站点原本的实现，功能天然 100% 保留。

---

## 2. 需求对照

| 需求 | 落地方式 |
| --- | --- |
| 独立安装运行 | 单 Activity 工程，无第三方依赖（含 AndroidX），`minSdk 24` |
| 保留原页面全部功能与交互 | 页面本体未被改写；仅在 `.chat-head` 前置两个图标按钮，其余控件原样保留（可横向滑动） |
| 清晰的消息列表 | `#chat-scroll` 占满剩余高度并独立滚动，气泡最大宽度 88%、圆角 18、间距 8，消息少时贴底起排 |
| 输入框 | 圆角 22 的输入胶囊，16px 字号，`enterkeyhint="send"`，最大高度 34vh 后内部滚动 |
| 发送操作 | 沿用站点自己的发送按钮与提交流程，不拦截、不代发 |
| 移动端适配 | 聊天页锁定一屏高度（`position: fixed` + 内部滚动），无整页滚动、无横向溢出；`adjustResize` 跟随软键盘 |
| 流畅导航 | 站点侧边栏改为图标按钮唤出；会话列表收进左侧抽屉（84vw）带遮罩与动画；返回键逐层退栈 |
| 使用站点自身登录 | 未登录时站点自己跳 `#/login`，壳不做任何登录 UI、不读密码框 |
| 保持会话状态 | `CookieManager` 开启并 `flush()` 落盘，杀进程后仍是登录态 |
| 处理加载 | 原生启动遮罩 + 首屏就绪回调，最长 9s 兜底，不会卡死 |
| 登录失效 | 站点自行跳登录页；壳检测到「曾在控制台 → 回到登录页」时提示「登录状态已失效」 |
| 网络异常 | 主框架加载失败/5xx 显示原生错误页：重新加载 / 用浏览器打开 |
| 返回操作 | 会话抽屉 → 站点侧边栏 → SPA 历史 → 连按两次退出 |
| 不新增/替换 API | 壳内没有任何网络代码，`assets/skin/chat.js` 也不发起任何请求 |

---

## 3. 目录结构

```
ly-playground-android/
├── .github/workflows/
│   └── build-apk.yml                # GitHub Actions：云端编译 + 校验 + 发布 Release
├── settings.gradle                  # 仓库与模块声明
├── build.gradle                     # AGP 8.5.2（apply false）
├── gradle.properties                # 关掉 AndroidX，避免多余依赖
├── gradle/wrapper/
│   └── gradle-wrapper.properties    # Gradle 8.7
└── app/
    ├── build.gradle                 # compileSdk 34 / minSdk 24 / targetSdk 34
    ├── proguard-rules.pro           # 保留 @JavascriptInterface
    └── src/main/
        ├── AndroidManifest.xml      # 单 Activity，adjustResize，无敏感权限
        ├── assets/skin/
        │   ├── chat.css             # 聊天皮肤（只做布局/视觉）
        │   └── chat.js              # 路由感知 / 贴底滚动 / 抽屉 / 返回键
        ├── java/xyz/ly77625/playground/
        │   └── MainActivity.java    # WebView 配置、Cookie、错误页、返回键、文件选择
        └── res/
            ├── layout/activity_main.xml
            ├── values/{strings,colors,themes}.xml
            ├── drawable/            # 按钮底、离线图、自适应图标的前景与背景
            └── mipmap-anydpi{,-v26}/# 矢量启动图标（无位图资源，仓库零二进制）
```

---

## 4. 本地构建

环境：**JDK 17**、Android SDK **Platform 34**、Build-Tools 34.x。

### 方式一：Android Studio

1. `File → Open` 选择 `ly-playground-android`；
2. 首次同步会自动补全 `gradle-wrapper.jar` 并下载 Gradle 8.7 / AGP 8.5.2；
3. `Run ▶` 装到手机，或 `Build → Build Bundle(s)/APK(s) → Build APK(s)`。

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 方式二：命令行

```bash
# 若目录下还没有 gradle-wrapper.jar，先生成一次
gradle wrapper --gradle-version 8.7

./gradlew assembleDebug
# 正式包（默认未签名，需自备 keystore）
./gradlew assembleRelease
```

### 发布签名（可选）

在 `app/build.gradle` 的 `android { }` 中补充：

```groovy
signingConfigs {
    release {
        storeFile file("../keystore.jks")
        storePassword System.getenv("KS_PASS")
        keyAlias "playground"
        keyPassword System.getenv("KEY_PASS")
    }
}
buildTypes { release { signingConfig signingConfigs.release } }
```

---

## 5. 云端构建（GitHub Actions）

`.github/workflows/build-apk.yml` 提供一条完整流水线，**不需要本地装任何 Android 工具链**：

| 步骤 | 说明 |
| --- | --- |
| Checkout / JDK 17 / Android SDK / Gradle 8.7 | 用 `android-actions/setup-android` + `gradle/actions/setup-gradle` |
| `gradle assembleDebug` | 仓库不带 `gradle-wrapper.jar`，直接调用 setup-gradle 提供的 gradle |
| Verify APK | 打印 `aapt2 dump badging`、APK 体积、SHA256，并校验 `classes.dex`、`resources.arsc`、`assets/skin/*` 是否都在包里 |
| Upload artifact | Actions 页面可下载（保留 30 天） |
| Publish release | 推到 `latest-build` 这个固定 tag 的 Release，手机打开即下 |

触发方式：推到 `main` / 手动 `Run workflow` / 提 PR（PR 只跑构建，不发布）。

安装包固定地址（把 `OWNER` 换成实际账号）：

```
https://github.com/OWNER/ly-playground-android/releases/download/latest-build/app-debug.apk
```

> Release 里的是 **debug 包**，用调试密钥签名，适合自装自测；要上架或长期分发请按 §4 配 `signingConfigs` 出正式包。

---

## 6. 首次使用

1. 打开应用，直接进入 `#/playground`；
2. 未登录时站点会跳到自己的登录页 —— 用站点账号（或 0z 统一登录）登录，壳不介入；
3. 若账号尚未绑定 QQ，会停在站点的绑定页，按页面指引操作；
4. 登录成功后若落到控制台，壳会自动把一次导航送回对话页（仅首次）；
5. 之后冷启动直接进入对话，登录态由 Cookie 保持。

---

## 7. 交互细节

**顶部一行**：`☰ 菜单`、`≡ 会话列表`、标题、模型/分组选择、以及站点原有的全部按钮（横向可滑动，不会挤掉功能）。

> 聊天页隐藏了站点自带的移动端顶栏（与聊天头部信息重复），菜单入口移到聊天头部；余额、账号、QQ 群等仍在侧边栏与控制台里，功能没有缺失。

**返回键**

```
会话抽屉打开 → 关闭
站点侧边栏打开 → 关闭
当前不在 #/playground → history.back()（SPA 内后退，不重载）
否则 → 2 秒内再按一次退出
```

**文件与外链**

- 页面里的 `<input type="file">` 会拉起系统文件选择器（支持多选），选完直接回填给页面；
- `target="_blank"` 的链接、下载链接交给系统浏览器/下载器；
- `qq.77625.xyz` 跳系统浏览器；其余 http(s) 留在应用内（0z 登录要跨域回跳，必须在同一 WebView）。

**键盘**：`adjustResize` + 输入区参与 flex 布局，键盘弹出时输入框始终贴在键盘上方，消息区自动让位。

---

## 8. 边界与降级

- **站点改版**：皮肤依赖 `.chat-box / .chat-head / #chat-scroll / .chat-input / .conv-item / .pg-list`。若某天结构变了，JS 找不到 `.chat-box` 就停止装饰，聊天页回落为站点原生样式 —— 功能不受影响，只是不再像聊天软件。
- **登录失效**：完全交给站点跳转，壳只补一句提示，不尝试刷新或重登。
- **网络异常**：只在主框架失败时接管，子资源失败不打扰用户。
- **不做的事**：不注入任何网络请求、不读取/上传 Cookie、不收集设备信息、无第三方 SDK、无广告、无推送。

---

## 9. 已知限制

- 本地开发机没有 JDK 与 Android SDK，工程改由 GitHub Actions 编译；本地要出包请按 §4 装 JDK 17 + SDK 34。
- 无消息推送、无离线缓存：断网时只能看到错误页与重试。
- 横屏与平板沿用站点自身的响应式规则（`.pg-layout` 在窄屏隐藏会话列，由抽屉替代）。
- 未处理摄像头/麦克风授权（文本对话不涉及）；若站点将来加入语音，需在 `AndroidManifest.xml` 增加权限并在 `onPermissionRequest` 中申请。
