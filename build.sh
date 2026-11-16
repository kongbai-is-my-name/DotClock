#!/bin/bash
# 构建 DotClock.app（无需 Xcode 工程，只需要命令行工具）。
# 用法：./build.sh        仅构建
#       ./build.sh run    构建并重新启动小组件
set -euo pipefail
cd "$(dirname "$0")"

APP="build/DotClock.app"
mkdir -p "$APP/Contents/MacOS"

# 部分版本的 Command Line Tools 残留了一份重复的 module.modulemap，会让所有 Swift 编译报
# “redefinition of module 'SwiftBridging'”。这里用 VFS overlay 把它屏蔽掉，不改动系统文件。
FLAGS=()
SWIFT_INC="/Library/Developer/CommandLineTools/usr/include/swift"
if [[ -f "$SWIFT_INC/module.modulemap" && -f "$SWIFT_INC/bridging.modulemap" ]]; then
    mkdir -p build/vfs
    : > build/vfs/empty.modulemap
    cat > build/vfs/overlay.yaml <<EOF
{ "version": 0, "roots": [ { "type": "directory", "name": "$SWIFT_INC", "contents": [
  { "type": "file", "name": "module.modulemap", "external-contents": "$PWD/build/vfs/empty.modulemap" } ] } ] }
EOF
    FLAGS+=(-vfsoverlay build/vfs/overlay.yaml)
fi

# 先删掉旧的可执行文件，编译失败时才不会误报“已构建”
rm -f "$APP/Contents/MacOS/DotClock"
swiftc -O -swift-version 5 ${FLAGS[@]+"${FLAGS[@]}"} Sources/*.swift -o "$APP/Contents/MacOS/DotClock" 2>&1 \
    | grep -v "deprecated" | grep -E "error|warning:" || true
[[ -x "$APP/Contents/MacOS/DotClock" ]] || { echo "构建失败"; exit 1; }

cat > "$APP/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>DotClock</string>
    <key>CFBundleDisplayName</key><string>点阵时钟</string>
    <key>CFBundleIdentifier</key><string>local.dotclock</string>
    <key>CFBundleExecutable</key><string>DotClock</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSMinimumSystemVersion</key><string>13.0</string>
    <key>LSUIElement</key><true/>
    <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
EOF

codesign --force --sign - "$APP" >/dev/null 2>&1
echo "已构建 $APP"

if [[ "${1:-}" == "run" ]]; then
    pkill -x DotClock 2>/dev/null || true
    open "$APP"
    echo "已启动，时间和当地天气已设为自动从网络同步（右键小组件可调整样式与设置）"
fi
