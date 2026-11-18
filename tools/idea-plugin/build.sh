#!/usr/bin/env bash
# 构建 mySE Deployer IDEA 插件 → 生成可安装的 zip
#
# 关键点（踩过的坑）：
#   1. 必须用 IDEA 自带的 JBR 编译（IDEA 2024.1 运行时是 Java 21，class 版本 65），
#      本机 JDK 1.8 编译出来平台加载不了。
#   2. classpath 用 IDEA 原生路径 + 正斜杠 + 通配符：-cp "$IDEA_DIR/lib/*"
#      （全量拼 jar 会超命令行长度限制）
#   3. Windows(Git Bash) 下传给 javac/jar 的路径必须是 Windows 路径（/d/... 这种 MSYS 路径
#      javac 不认），所以用 cygpath -w 转换；macOS/Linux 直接用原生路径即可。
#   4. 插件 zip 结构：<插件名>/lib/<插件名>.jar，plugin.xml 放在 jar 内的 META-INF 下。
#
# 用法：./build.sh            （自动探测 IDEA 目录）
#       IDEA_DIR=/path/to/idea ./build.sh
set -e

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$PLUGIN_DIR/out"
PLUGIN_NAME="myse-deployer"

# ---------------------------------------------------------------- 平台判定
case "$OSTYPE" in
  msys*|cygwin*|win32*)
    IS_GITBASH=1
    ;;
  darwin*)
    IS_GITBASH=""
    IS_MAC=1
    ;;
  *)
    IS_GITBASH=""
    ;;
esac

# ---------------------------------------------------------------- IDEA 目录探测
detect_idea_dir() {
  if [ -n "$IDEA_DIR" ]; then
    echo "$IDEA_DIR"
    return
  fi
  if [ -n "$IS_GITBASH" ]; then
    for d in "C:/Program Files/JetBrains/IntelliJ IDEA"*; do
      [ -d "$d" ] && { echo "$d"; return; }
    done
  else
    for d in /Applications/*IntelliJ*.app/Contents /Applications/*IDEA*.app/Contents \
             "$HOME"/Applications/*IntelliJ*.app/Contents "$HOME"/Applications/*IDEA*.app/Contents; do
      [ -d "$d" ] && { echo "$d"; return; }
    done
  fi
  echo ""
}

IDEA_DIR="$(detect_idea_dir)"
if [ -z "$IDEA_DIR" ]; then
  echo "[ERROR] 未找到 IDEA 安装目录，请用 IDEA_DIR=/path/to/idea 指定"
  echo "        macOS 形如: IDEA_DIR='/Applications/IntelliJ IDEA.app/Contents'"
  exit 1
fi

# ---------------------------------------------------------------- 工具路径
# JBR：Windows 在 jbr/bin，macOS 在 jbr/Contents/Home
if [ -x "$IDEA_DIR/jbr/Contents/Home/bin/javac" ]; then
  JAVAC="$IDEA_DIR/jbr/Contents/Home/bin/javac"
elif [ -x "$IDEA_DIR/jbr/bin/javac" ]; then
  JAVAC="$IDEA_DIR/jbr/bin/javac"
else
  echo "[ERROR] 未找到 IDEA 自带 JBR 的 javac: $IDEA_DIR/jbr"
  exit 1
fi

# jar 工具：JBR 里没有（精简版），用系统 JDK 的 jar —— 只做 zip 打包，与 class 版本无关
if [ -n "$JAR_TOOL" ]; then
  JAR="$JAR_TOOL"
elif [ -n "$IS_GITBASH" ]; then
  JAR="C:/Program Files/Java/jdk1.8.0_261/bin/jar.exe"
elif [ -x "/usr/bin/jar" ]; then
  JAR="/usr/bin/jar"
else
  JAR="$(command -v jar || true)"
fi
if [ -z "$JAR" ] || [ ! -x "$JAR" ]; then
  echo "[ERROR] 未找到 jar 工具，可用 JAR_TOOL=/path/to/jar 指定"
  exit 1
fi

# 路径转换：仅 Git Bash 需要（MSYS 路径 javac 不认）
if [ -n "$IS_GITBASH" ]; then
  PLUGIN_DIR_WIN="$(cygpath -w "$PLUGIN_DIR")"
  OUT_WIN="$(cygpath -w "$OUT")"
  IDEA_LIB="$IDEA_DIR/lib/*"
else
  PLUGIN_DIR_WIN="$PLUGIN_DIR"
  OUT_WIN="$OUT"
  IDEA_LIB="$IDEA_DIR/lib/*"
fi

echo "[0/4] IDEA=$IDEA_DIR"
echo "     javac=$JAVAC"
echo "     jar  =$JAR"

echo "[1/4] 清理"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/jar/META-INF"

echo "[2/4] 编译"
# 源文件清单写绝对路径（javac 认正斜杠；Windows Git Bash 下需先 cygpath 转 Windows 路径）
if [ -n "$IS_GITBASH" ]; then
  (cd "$PLUGIN_DIR/src" && find . -name "*.java" | sed 's|^\./||' | while read -r f; do
    cygpath -w "$PLUGIN_DIR/src/$f"
  done > "$OUT/sources.txt")
else
  (cd "$PLUGIN_DIR/src" && find . -name "*.java" | sed 's|^\./||' | while read -r f; do
    echo "$PLUGIN_DIR/src/$f"
  done > "$OUT/sources.txt")
fi

"$JAVAC" -encoding UTF-8 -nowarn --release 21 -cp "$IDEA_LIB" -d "$OUT/classes" "@$OUT/sources.txt"

echo "[3/4] 打包 jar"
(cd "$OUT/classes" && "$JAR" cf "$OUT/$PLUGIN_NAME.jar" .)
# ⚠️ IDEA 2020.3+ 要求插件描述文件在 jar 内的 META-INF/plugin.xml，
#    放在插件目录根下不会被识别（菜单不出现）。这里用 jar uf 塞进 jar。
# 注意 jar 里存的是相对当前目录的路径，所以要 cd 到 resources 目录再写 META-INF/plugin.xml
(cd "$PLUGIN_DIR/resources" && "$JAR" uf "$OUT/$PLUGIN_NAME.jar" META-INF/plugin.xml)
cp "$OUT/$PLUGIN_NAME.jar" "$OUT/jar/"

echo "[4/4] 生成插件 zip（结构：$PLUGIN_NAME/lib/$PLUGIN_NAME.jar）"
rm -rf "$OUT/dist"
mkdir -p "$OUT/dist/$PLUGIN_NAME/lib"
cp "$OUT/$PLUGIN_NAME.jar" "$OUT/dist/$PLUGIN_NAME/lib/"
# 用 jar 的 -M（不生成 MANIFEST）打包，避免 zip 根目录多出 META-INF/ 被 IDEA 当成另一个插件
(cd "$OUT/dist" && "$JAR" cfM "../$PLUGIN_NAME.zip" "$PLUGIN_NAME")

ZIP_PATH="$OUT/$PLUGIN_NAME.zip"
echo
echo "✅ 插件包已生成: $ZIP_PATH"
echo "   安装方式：IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk... → 选这个 zip"
if [ -n "$IS_GITBASH" ]; then
  echo "   或直接复制到: $USERPROFILE/AppData/Roaming/JetBrains/IntelliJIdea2024.1/plugins/myse-deployer/"
else
  echo "   或直接复制到: $HOME/Library/Application Support/JetBrains/IntelliJIdea*/plugins/myse-deployer/"
fi
