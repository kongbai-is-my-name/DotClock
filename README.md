# DotClock · 点阵时钟

**DotClock（点阵时钟）** 是一款使用纯原生 Swift / SwiftUI 编写的轻量级 macOS 桌面时钟与天气小组件。它融合了 Nothing 风格的极简点阵（Dot-Matrix）视觉设计与 Material You（CAM16 / HCT）色彩系统，贴合在桌面图层之上，无需安装庞大的 Xcode 工程，仅需 macOS 命令行工具即可编译与打包。

---

## 功能特性

- **点阵视觉与多样化表盘**
  - **4 种时钟布局**：经典（大时分 + 小秒）、单行（时:分:秒）、堆叠（双行大字）、表盘（点阵模拟指针）。
  - **多种数字与文字风格**：支持圆点点阵、方块像素、空心圆点、七段数码管及 SF Rounded / SF Mono / DIN / Futura 等多种字体；内置 12×12 中文实时点阵栅格化引擎，中文城市名与日期同样支持像素点阵渲染。
  - **进度可视化**：内置 60 点秒针进度条与 24 点“今日进度”百分比指示条。

- **全自动网络校时与智能天气（零本地文件读取）**
  - **自动网络授时**：启动时、每 15 分钟、系统休眠唤醒及本机时钟变更时，自动通过多源授时接口校准标准时间并补偿单程网络延迟（`RTT / 2`），即使本机系统时间或时区不准也能显示精准时间。
  - **智能网络定位与实时天气**：无需申请系统定位权限、不读取任何本地隐私文件夹。自动通过公网 IP 识别当前所在城市与时区（兼容代理分流环境，国内直连源优先 + 国际源自动回退），并接入 [Open-Meteo](https://open-meteo.com/)（免密钥）展示实时气温、最高/最低温及 12fps 低功耗动态点阵天气背景（雨、雪、雷电、云雾、星光/阳光闪烁）。

- **Material You 动态主题引擎**
  - 内置完整的 CAM16 与 HCT 色彩空间求解器，自动生成深色 / 浅色两套和谐的 TonalSpot 配色方案（跟随系统外观或手动锁定深浅色）。
  - 内置 6 款精选主题色（Nothing 红、海蓝、森绿、薰紫、琥珀、樱粉），并支持通过调色板自由定制主题色与数字颜色。

- **原生桌面级交互与低功耗优化**
  - **贴合桌面**：常驻于桌面图标层之上、普通应用窗口之下，支持跨桌面空间（Spaces）显示，不占用 Dock 栏图标。
  - **自由缩放与拉伸**：按住中间拖动位置；拖动左/右边缘单独拉宽，拖动上/下边缘单独拉高，拖动四角进行 50%–300% 等比无级缩放。
  - **遮挡自动休眠**：利用 `NSWindow.occlusionState` 检测窗口可见性，当小组件被全屏应用或普通窗口完全遮挡时自动暂停天气动画，降低 CPU 与电量消耗。

- **多时区世界时钟**
  - 内置常用城市库并支持回车联网地理编码搜索全球任意城市，直观展示相对主城市的跨天状态（昨天 / 今天 / 明天）与时差。

---

## 环境要求

- **操作系统**：macOS 13.0 (Ventura) 及以上版本（原生支持 Apple Silicon `arm64` 与 Intel `x86_64`）
- **编译工具**：Xcode Command Line Tools（无需安装完整版 Xcode）

若尚未安装命令行编译工具，可在终端执行以下命令安装：

```bash
xcode-select --install
```

---

## 构建与运行指南

克隆或进入项目根目录后，所有构建与打包均通过内置脚本一键完成：

### 1. 本地编译与启动（日常开发）

仅编译生成应用包（产物位于 `build/DotClock.app`）：

```bash
./build.sh
```

编译并立即重启桌面上的点阵时钟小组件：

```bash
./build.sh run
```

> **说明**：部分 macOS 版本的 Command Line Tools 存在重复的 `module.modulemap` 导致 Swift 报 `redefinition of module 'SwiftBridging'` 错误，`build.sh` 与 `package.sh` 已内置 VFS Overlay 自动屏蔽该问题，无需修改任何系统文件。

### 2. 打包可分发的通用版 DMG 安装镜像

使用 `package.sh` 可交叉编译 `arm64` 与 `x86_64` 双架构通用二进制（Universal Binary），自动调用 `tools/make_icon.swift` 生成高清 `.icns` 应用图标，并封装为 `.dmg` 安装镜像：

```bash
# 默认生成 dist/DotClock-1.0.dmg
./package.sh

# 指定版本号打包（例如生成 dist/DotClock-1.1.dmg）
VERSION=1.1 ./package.sh
```

- **代码签名**：默认使用本地临时签名（ad-hoc `-`）。若拥有 Apple 开发者证书，可通过环境变量传入签名身份：
  ```bash
  SIGN_ID="Developer ID Application: Your Name (TEAMID)" VERSION=1.1 ./package.sh
  ```

### 3. 安装与首次打开说明

1. 双击打开 `dist/` 目录下的 `.dmg` 文件，将 **DotClock** 拖入 **应用程序（`/Applications`）** 文件夹。
2. 若使用默认无开发者证书的本地构建包，在其他 Mac 上首次打开若提示“无法验证开发者”或“已损坏”，任选以下一种方式放行即可：
   - **方式 A**：打开「系统设置 → 隐私与安全性」，在底部找到 `DotClock` 点击「仍要打开」。
   - **方式 B**：在终端执行以下命令移除隔离属性后再打开：
     ```bash
     xattr -dr com.apple.quarantine /Applications/DotClock.app
     ```

---

## 命令行调试与快照工具

编译后的可执行文件内置了命令行自检与离屏预览渲染模式，便于快速调试网络状态与 UI 样式：

### 网络校时、定位与天气联调自检

```bash
# 测试自动网络时间同步 + IP 自动定位城市 + 实时天气拉取
./build/DotClock.app/Contents/MacOS/DotClock --weather-test

# 测试指定城市与时区的坐标查询与天气拉取
./build/DotClock.app/Contents/MacOS/DotClock --weather-test 东京 Asia/Tokyo
```

### 离屏渲染预览截图（Snapshot）

无需启动常驻窗口即可直接导出高分屏 PNG 预览图：

```bash
./build/DotClock.app/Contents/MacOS/DotClock --snapshot preview.png \
  --style classic \
  --digits round \
  --font dot \
  --seed 1b6ef3 \
  --weather 2 \
  --zoom 1.2
```

支持的可选参数：
- `--light`：使用浅色模式渲染（默认深色）
- `--12h`：使用 12 小时制（默认 24 小时制）
- `--style <classic|inline|stacked|dial>`：指定表盘布局
- `--digits <round|square|ring|segment|rounded|mono|din|futura|didot|phosphate>`：指定数字样式
- `--font <dot|system|mono|字体PostScript名>`：指定文字字体
- `--seed <6位十六进制色值>`：指定主题种子颜色
- `--color <primary|accent|mono|6位十六进制色值>`：指定数字颜色
- `--weather <WMO天气代码>`：指定预览天气状态（如 `0` 晴、`2` 多云、`61` 雨、`71` 雪、`95` 雷雨）
- `--zoom <倍数>` / `--wide <宽度倍数>` / `--tall <额外高度>`：指定尺寸比例
- `--no-animation`：隐藏天气背景粒子层

---

## 使用与交互说明

- **移动位置**：鼠标按住小组件中心区域即可自由拖拽，位置会自动保存。
- **调整大小**：
  - 拖动 **左 / 右边缘**：单独调整卡片宽度；
  - 拖动 **上 / 下边缘**：单独调整卡片高度；
  - 拖动 **四个边角**：等比例无级缩放整体大小（也可在右键菜单「大小」中选择预设百分比或「恢复默认比例」）。
- **右键菜单**：在小组件任意位置点击鼠标右键即可呼出完整设置菜单（主显示城市/自动网络获取、立即同步网络时间与天气、添加/移除世界时钟、时钟样式、数字样式与颜色、文字字体、主题配色、深浅外观、网络自动校时开关、天气动画开关、华氏度/24小时制切换、登录时启动等）。

---

## 项目目录结构

```text
.
├── Sources/
│   ├── main.swift              # 应用入口、无边框桌面窗口、边缘缩放手势、右键菜单与 CLI 工具
│   ├── Store.swift             # 全局状态持久化（UserDefaults）、定时同步调度与城市数据库
│   ├── NetworkSync.swift       # 多源网络标准时间校准（RTT 补偿）与公网 IP 智能城市/时区定位
│   ├── Weather.swift           # Open-Meteo 地理编码与实时天气接口、WMO 天气映射与点阵天气图标
│   ├── WeatherAnimation.swift  # 点阵网格粒子天气动画（雨、雪、雷电、云朵、雾气、闪烁星光）
│   ├── Views.swift             # 主表盘布局（经典/单行/堆叠/指针）、世界时钟列表与城市搜索面板
│   ├── DotMatrix.swift         # 5×7 点阵字库、CoreText 中文 12×12 点阵栅格化器与进度条绘制
│   ├── Styles.swift            # 数字样式、七段数码管绘制器、系统字体适配与 SwiftUI 环境键
│   └── Monet.swift             # CAM16 色彩外貌模型、HCT 色彩空间求解与 TonalSpot 主题生成
├── tools/
│   └── make_icon.swift         # 打包阶段自动绘制 1024×1024 应用图标的独立脚本
├── build.sh                    # 快速编译与本地运行脚本
└── package.sh                  # 双架构通用二进制编译、图标生成与 DMG 镜像打包脚本
```
