#!/bin/bash
# 打包成可分发的 DMG：同时包含 Apple 芯片 (arm64) 和 Intel (x86_64) 版本，支持 macOS 13 及以上。
# 用法：./package.sh            产出 dist/DotClock-<版本>.dmg
#       VERSION=1.2 ./package.sh
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${VERSION:-1.0}"
NAME="DotClock"
DISPLAY="点阵时钟"
WORK="build/package"
APP="$WORK/$NAME.app"
DMG="dist/$NAME-$VERSION.dmg"

rm -rf "$WORK"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" dist

# 与 build.sh 相同：屏蔽 Command Line Tools 里重复的 module.modulemap
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

echo "▸ 编译 arm64 / x86_64…"
for ARCH in arm64 x86_64; do
    swiftc -O -swift-version 5 -target "$ARCH-apple-macos13.0" ${FLAGS[@]+"${FLAGS[@]}"} \
        Sources/*.swift -o "$WORK/$NAME-$ARCH" 2>&1 | grep -v "deprecated" | grep -E "error|warning:" || true
    [[ -x "$WORK/$NAME-$ARCH" ]] || { echo "✗ $ARCH 编译失败"; exit 1; }
done
lipo -create "$WORK/$NAME-arm64" "$WORK/$NAME-x86_64" -output "$APP/Contents/MacOS/$NAME"

echo "▸ 生成图标…"
ICONSET="$WORK/AppIcon.iconset"
mkdir -p "$ICONSET"
swiftc ${FLAGS[@]+"${FLAGS[@]}"} tools/make_icon.swift -o "$WORK/make_icon" 2>&1 | grep -E "error" || true
"$WORK/make_icon" "$WORK/icon-1024.png"
for S in 16 32 128 256 512; do
    sips -z $S $S "$WORK/icon-1024.png" --out "$ICONSET/icon_${S}x${S}.png" >/dev/null
    sips -z $((S * 2)) $((S * 2)) "$WORK/icon-1024.png" --out "$ICONSET/icon_${S}x${S}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"

cat > "$APP/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>$NAME</string>
    <key>CFBundleDisplayName</key><string>$DISPLAY</string>
    <key>CFBundleIdentifier</key><string>local.dotclock</string>
    <key>CFBundleExecutable</key><string>$NAME</string>
    <key>CFBundleIconFile</key><string>AppIcon</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>$VERSION</string>
    <key>CFBundleVersion</key><string>$VERSION</string>
    <key>LSMinimumSystemVersion</key><string>13.0</string>
    <key>LSUIElement</key><true/>
    <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
EOF

# 没有 Apple 开发者证书时用本地签名（ad-hoc）；有证书可设置 SIGN_ID="Developer ID Application: …"
codesign --force --deep --sign "${SIGN_ID:--}" "$APP"

echo "▸ 制作 DMG…"
STAGE="$WORK/dmg"
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/应用程序"
cat > "$STAGE/安装说明.txt" <<'EOF'
点阵时钟 安装说明
================

1. 把「DotClock」拖到「应用程序」文件夹。
2. 打开「应用程序」，双击 DotClock。

首次打开如果提示“无法验证开发者”或“已损坏”（因为本应用没有 Apple 开发者签名）：

  方法 A：打开「系统设置 → 隐私与安全性」，在页面底部找到 DotClock，点「仍要打开」。

  方法 B：打开「终端」，粘贴下面这行并回车，然后再双击打开：
      xattr -dr com.apple.quarantine /Applications/DotClock.app

之后就能正常使用了。

使用
----
· 小组件会出现在桌面右上角，按住中间可拖动位置。
· 拖动边缘调宽或调高，拖动四角等比缩放。
· 右键小组件：添加城市、主显示城市、时钟样式、字体、颜色、天气等设置。
· 右键 →「登录时启动」可开机自动运行。
EOF

rm -f "$DMG"
hdiutil create -volname "$DISPLAY" -srcfolder "$STAGE" -fs HFS+ -format UDZO -imagekey zlib-level=9 -ov "$DMG" >/dev/null
echo "✓ 已生成 ${DMG}（$(du -h "${DMG}" | cut -f1)）"
