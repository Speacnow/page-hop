# PageFlow 设计文档

这份文档写的是**为什么这么做**，不是怎么用。使用说明见 [README.md](README.md)。

- [一、产品定位](#一产品定位)
- [二、架构总览](#二架构总览)
- [三、关键实现](#三关键实现)
- [四、踩过的坑](#四踩过的坑)
- [五、数据与持久化](#五数据与持久化)
- [六、调试与工程化](#六调试与工程化)
- [七、已知取舍](#七已知取舍)

---

## 一、产品定位

**一个只读扫描件的阅读器。**

起点很具体：一本 318 页的考试扫描件，用 ReadEra 读不下去——白边巨大、底色发灰、翻到第 137 页再想回第 14 页就得靠回忆。ReadEra 又不开源，改不了。

所以定下三条硬约束：

1. **只做 PDF**，不碰 EPUB / 漫画 / 网盘。功能面越窄，扫描件这条路能挖得越深。
2. **不改源文件**。批注是叠加层，导出时才烧成新 PDF——源文件永远干净。
3. **不引入阅读模式的概念**（单栏 / 双栏 / 滚动方向……）。一页一张图，竖向滚动，把"找页"和"看清"做到位。

---

## 二、架构总览

```
MainActivity            书库：最近打开、选文件、崩溃日志
   └── ReaderActivity   阅读器（约 2600 行，全部交互都在这里）
          ├── PageAdapter / PageView     一页一张位图，自己画（Binder 里不给 ImageView）
          ├── ZoomFrame                  手势：缩放 / 平移，夹在 RecyclerView 外层
          ├── RenderEngine               所有渲染串行在这一个线程
          │      ├── loadPage(doc, page, width, crop, analyze, pad)
          │      └── thumb(doc, page, width)
          ├── CropDetector / CropMath    找正文框 → 按留白外扩
          ├── ColorModes                 主题 = 一个 ColorMatrix，绘制期生效
          ├── PageHistory                前进 / 后退 的栈
          └── Store                      filesDir/books/*.json
```

**一个刻意的选择：全部用传统 View，不用 Compose。**
`PageView` 要自己控制 `onDraw`（位图 + 裁剪 + 笔迹 + 主题滤镜 + 占位纸色），还要精确控制触摸分发（长按框选、单指写字、双指缩放三者互斥）。这层用 View 体系表达最直接，Compose 的自定义绘制与手势互操作反而更绕。

### 渲染只有一条线程

pdfium 的文档对象不是线程安全的，而且大页渲染本身就是几百毫秒级的重活。所以：

- `RenderEngine.dispatcher` 是单线程；所有 `doc.render()` 都 `withContext` 到这里
- 位图出来后走 `LruCache`（页 96MB / 缩略图 24MB）
- RecyclerView 快速滑动时，被回收的 ViewHolder 会 `job.cancel()`；渲染开始前再检查一次"我要的页号是否还是当前页号"

这样做的代价是**峰值只有一个页在渲染**，好处是不会因为并发把内存打爆，也不会出现"两个线程同时用同一个 doc"的崩溃。

---

## 三、关键实现

### 3.1 自动裁边：检测和留白是两件事

旧版把留白（左右 1.2%、上下 0.8%）写死在检测里，等于把正文外的一切都切掉，正文贴着屏幕边——看起来就是"裁过头"。

现在拆成两步：

1. `CropDetector` 只负责找**正文框**（纯几何：按行/列的墨迹浓度找连续区域，返回 0–1 的归一化矩形）
2. `CropMath.expand` 按留白比例外扩，并夹在"单边最多裁 18%"之内，避免误判把内容裁没

留白默认 2%，可调 0–10%。检测结果按页缓存进 `BookState.pageCrops`，所以同一本书第二次打开不用重新分析。

> 顺带一个数据迁移教训：老版本存的是"已加留白的框"，新版本存"正文框"，两者的语义差一个 pad。所以 `Store` 里带 `cropVer`，读到 v1 数据就整体丢弃重算——**宁可重算一次，也不要让两套语义混在一起**。

### 3.2 颜色模式：不做位图处理，做绘制期滤镜

七种模式全部是 `ColorMatrix` + `ColorMatrixColorFilter`：

```kotlin
object ColorModes {
    @Volatile var active: ColorModeKey = ColorModeKey.NORMAL
    fun filter(mode: ColorModeKey): ColorFilter?   // 预建好，绘制时直接取
}
```

`PageView.onDraw` 时读 `ColorModes.active`，把 filter 挂到 paint 上。这样：

- 切换主题**不重新解码、不重新检测**，内存里只有一份位图
- 滚出屏幕、被缓存复用的 View 不会残留旧主题（因为不是绑定时写死的）
- 扫描件（整页是一张图）同样生效

护眼米黄的矩阵是**每通道独立线性映射**：`out = a * in + b`，让白色落到 `#F4E8D0`、黑色抬到 `#2D2D2D`，对比度从 13:1 压到 5:1。旧版用的是交叉混色矩阵，白色算出来是 `(255,255,245)`——名字叫米黄，看着还是纯白。

顶栏/底栏的配色同理，但前景色**不手写**，按背景亮度算：

```kotlin
fun contentFor(bg: Int): Int {
    val lum = (0.2126f * Color.red(bg) + 0.7152f * Color.green(bg) + 0.0722f * Color.blue(bg)) / 255f
    return if (lum > 0.6f) 0xFF141414.toInt() else 0xFFFFFFFF.toInt()
}
```

存一份手写的前景色，就一定会有一处漏改（目录图标、置灰的前进箭头都栽在这上面）。

### 3.3 手势分层：RecyclerView 滚动 + ZoomFrame 缩放

- `RecyclerView` 负责竖向滚动（它的 fling、回收、吸附都成熟，不要自己写）
- `ZoomFrame` 包在外面，`onInterceptTouchEvent` 里决定这一串事件归谁：双指 → 自己（缩放/平移）；单指且 `scale ≈ 1` → 放给列表
- `PageView` 只做两件事：画，以及长按拖动框选

关键规则：
- **1.15x 以下不劫持单指拖动**。否则捏合松手停在 1.05x 时，看着是原尺寸、实际 `scale > 1`，单指拖动被当成平移，而余量只有几十像素 → 用户感觉"画面卡死了"。
- 撞到缩放边界就把基准挪过去（`span0 = 当前间距, baseScale = 边界值`），否则超过 3x 后往回捏会出现一段死区，手感像"卡在 3x 不动"。
- 已放大时把捏合门槛从 10% 降到 3%，不然放大状态下缩回去要挪一大段。
- **批注模式下单指是笔**，翻页只剩两条路：双指上下滑，或者**从屏幕左右 44dp 的边缘起手**（`PageView.isEdgeStart`）——这一笔只当滚动、不落墨。横屏时左右边缘本来就空着，写着写着想翻页不用退出批注模式。

### 3.4 缩略图地图：一屏看完一本书

全屏网格，一个格子 = 一页。三件事值得说：

1. **双指缩放改列数（2–10）**：不是缩放整个网格，而是重算 `spanCount` 后重排。格子高度按"屏宽 / 列数 × 页面比例"算，保证整页都在格子里。
2. **长按预览大图**：长按 380ms 后锁滚动、网格模糊、半透明大图盖上去；手指在格子上滑就换页，松手回落并只把最后一页标红。大图按屏宽 1:1 渲染，所以是真的清晰，不是把缩略图放大。
3. **网格不要用 padding**：`paddingTop` 配合 `clipToPadding`，会让 `RecyclerView` 在每次重排时拿"padding 外那一行"当锚点，锚点坐标不变、位置每次往前挪一行——表现就是"点一下滑条，地图一路往下滑回第 1 页"。改成 `layout_margin` 就稳了。

### 3.5 前进 / 后退：只记"有意图的跳转"

`PageHistory` 维护两条东西：

- `stack` + `index`：一条有当前位置的跳转链，`back()` 往回走、`forward()` 撤销后退，新跳转会截断右侧的 forward 路径
- `recent`：最近 6 个跳转点的去重列表（纯访问记录，分支后旧点也留着），长按箭头时显示

难点不在数据结构，在**什么算一次跳转**：

- 输页码、点目录、点书签、点缩略图、拖进度条松手 → 是跳转，但**要在这页停满 1 秒**才记账（`armJumpCommit`），否则连点几下会把历史塞满
- 逐页滑、连滚三屏 → 不算
- 记账时记的是**出发页**：`commit(from, to)`，这样 `back()` 才能回到"你从哪儿跳过来的"

1 秒这个阈值是反复调出来的：0.5 秒太敏感（点错立刻退回也能进栈），3 秒又让快速查阅完全不留痕。

### 3.6 目录面板：页签管内容，选项条管画法

结构是"两行固定头 + 一个可滚列表"：

```
[书签] [目录] [缩略图]                    ✕    ← 页签，默认书签
[页码][缩略图]              [按添加][按最近]     ← 只有书签页签出现的选项条
──────────────────────────────────────────
                列表（只有这里会滚）
```

- 页签决定**看什么**，选项条决定**怎么看**，职责不重叠
- 选项条在 `RecyclerView` 外面，所以永远顶在列表上方——这是"浮动固定"最省事的实现，不需要 `ItemDecoration` 或 `CoordinatorLayout`
- 目录页签没有选项：文档大纲的顺序是文档自己的事，不该被我们重排

同一个 `PageRowAdapter` 两用：`onlyPages == null` 就是整本书（缩略图页签），`showPages(list)` 就是子集（书签页签切到缩略图画法时）。

### 3.7 拖动进度条不闪：先铺"纸"，再上清晰图

拖进度条时每 90ms 跳一页，每跳一次都会取消上一次的全分辨率渲染。旧版的表现是白 → 黑 → 白地闪：页面位图还没到，`PageView.onDraw` 直接 return，露出的是窗口深色背景。

两处修改：

1. 没有位图时按**当前主题**铺一层"纸"——不是写死白色，而是把白色也过一遍当前主题的 `ColorMatrix`，于是白纸主题=白、夜间=黑、米黄=米黄，永远不会露出背景色
2. 拖动期间 `adapter.quick = true`，只渲染 360px 的低分辨率占位（十几毫秒就有内容），松手后再重绑成清晰图

### 3.8 音量键翻页

设置里可开，**默认关闭**——不是每个人都愿意让音量键失去原本的用途。

规则：音量上 = 上一页、音量下 = 下一页，走 `goToPage()`（滚动 + 刷新指示器 + 落盘），**不写历史**：翻页和"跳转"是两回事，逐页翻不该把前进/后退塞满。

两个边界：

- 目录面板 / 缩略图地图 / 历史弹窗开着时不接管，否则按音量键会把面板背后的正文滚走
- 命中就 `return true` 把事件吃掉，所以不会顺带调音量；按住连发就是连翻

---

## 四、踩过的坑

### 批注模式下捏不动（v0.3.3）

`PageView` 按下时 `requestDisallowInterceptTouchEvent(true)` 独占手势（写字时不能让列表把事件抢走），这个独占会持续到抬手，于是第二根手指落下时 `ACTION_POINTER_DOWN` 根本传不到 `ZoomFrame`，双指基准 `span0` 一直是 0，后续每帧 MOVE 都被挡掉。

修法三层：第二根手指落下时主动把独占还回去；多指期间忽略子 View 的独占请求；兜底用第一次双指 MOVE 重新起算基准。

### 缩放时页面一闪一闪（v0.3.5）

不是白屏，也不是掉帧，是**页高算错了**：绑定时用 PDF 原始页比例（1.414），实际显示的是裁边后的正文框（约 1.30），差 10%。每次重绑先撑高再缩回，看起来就是闪。

统一成 `PageMetrics.displayAspect(原始比例, 裁边框比例)` 之后稳定。用探针量过：1x→3x→1x 反复三次，`computeVerticalScrollOffset` 全程 526034 不变。

### 装不上、点不到、看不见

- vivo 的「安全守护」弹窗会拦住 `adb install`：安装脚本在后台 job 里推包，主线程轮询 UI 树，按 `bounds` 点「继续安装」和风险勾选框；树读不出来时按屏幕比例盲点两套坐标
- **锁屏状态下点不到那个弹窗**，安装会以 `INSTALL_FAILED_ABORTED` 失败——所以部署前先解锁
- 无限调试的序列号带空格（`adb-XXXX (2)._adb-tls-connect._tcp`），`-split '\s+'` 会把它截断，要用 `-replace '\s+device\s*$',''`

---

## 五、数据与持久化

- 全部落在 `filesDir/books/<md5(uri)>.json`，不上数据库
- `BookState`：页码、颜色模式、裁边开关与留白、每页裁边框、高亮、笔迹、书签、文字批注
- 笔迹用 Ramer–Douglas–Peucker 抽稀后存成 `"x,y;x,y;…"`，坐标是**页面比例**，换分辨率不失真
- 写盘先写 `.tmp` 再 rename（`atomicWrite`），避免写一半断电把状态写坏
- 进度在 `onPause` 就落盘：等到 `onStop` 时窗口已经收缩，列表会发出一次"回到第 0 项"的滚动事件，存下来就成了第 1 页

---

## 六、调试与工程化

### 真机无线调试（不用 Android Studio）

Android 11 的「无线调试」+ adb 就够：编译、安装、启动、截图、点屏幕、读日志、推文件全能做。本机把这套流程做成了机器级工具（`%USERPROFILE%\AndroidDev`，已进 PATH）：

```powershell
adb-wifi      # mDNS 自动发现并连接；首次用 -Pair ip:端口 -Code 六位码
apk-deploy    # 任意 Gradle 安卓项目：编译 → 装最新 debug APK → 读出包名/Activity 并拉起
```

一轮改动到看到效果约 20 秒（增量编译）+ 5~10 秒（安装启动）。

### 发布流程

仓库里**只保留一个安装包**：`PageFlow-latest.apk`。历史版本不进 Git（`.gitignore` 里 `*.apk` + `!PageFlow-latest.apk`），
这样每次发版只是覆盖同一个文件，链接永远有效，仓库也不会越滚越大。

```
# 1. 改版本号 app/build.gradle.kts（versionCode / versionName）
# 2. 编译安装到真机验证
gradle assembleDebug
apk-deploy
# 3. 更新安装包 + 提交
cp app/build/outputs/apk/debug/app-debug.apk PageFlow-latest.apk
git add -A && git commit -m "vX.Y.Z：一句话说明"
git push
```

### 手势探针

`adb shell input` 只能单指，`/dev/input/event4` 被 SELinux 挡着。所以 App 内置 `GestureProbe`，把合成的 `MotionEvent` 灌进 `ZoomFrame.dispatchTouchEvent`，走和真手指完全相同的分发链路：

```
adb shell am start -n com.pageflow.reader/.ReaderActivity \
  -d 'content://...' --es probe 'pen+pinch:400:1400+pinch:1400:300+drag2:-800+log'
adb logcat -s PFProbe:*
```

`+` 分隔指令（分号会被设备 shell 吃掉）：`pinch:起始间距:结束间距` / `drag:纵向位移` / `drag2:纵向位移` / `pen`（开关批注）/ `log`（打印倍率、首个可见页、滚动偏移、白屏帧计数）。只有显式传 `probe` extra 才跑。

### 测试

`app/src/test` 里的单元测试覆盖纯逻辑：裁边检测与留白规则、页高比例、笔迹抽稀、JSON 存取、历史栈。

### 构建

```
gradle assembleDebug           # APK -> app/build/outputs/apk/debug/
gradle :app:testDebugUnitTest  # 单元测试
```

AGP 8.13.2 / Kotlin 2.2.20 / Gradle 8.14.5 / JDK 21 / compileSdk 36 / minSdk 26。

PDF 引擎是 pdfium（`io.legere:pdfiumandroid:1.0.34`，BSD）。**注意 2.x 起的 AAR 不再带 `.so`，不要升级。**

`abiFilters` 同时带 `arm64-v8a` 和 `x86_64`：前者是真机，后者是模拟器（pdfium 的 `.so` 按架构分，缺了会在模拟器里 `UnsatisfiedLinkError`）。

---

## 七、已知取舍

| 取舍 | 理由 |
|---|---|
| 只有竖向连续滚动 | 左右翻页在扫描件上容易误触，且和"缩略图跳转"是两套心智模型 |
| 放大状态下不翻页 | 放大后按的翻页键会落在被放大的内容上，误操作概率高，所以刻意关掉 |
| 手写模式下单指永远是笔 | "既写字又滚动"没法两全。试过"快速甩动=滚动"的启发式，会误伤快速长笔画，放弃 |
| 批注只存 App 内，不回写 PDF | 源文件永远干净；需要带走时用「导出带批注的 PDF」 |
| 不做书库管理 | 一次读一本，最近打开列表够用了 |
| 没有 OCR | 扫描件没有文字层，选字/搜索必须先上 OCR，属于 v1.0 的活 |
