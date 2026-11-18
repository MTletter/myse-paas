#!/bin/bash
# ============================================================
#  mySE 代码包发布（沙盒）— 双击本文件即可运行
#  依赖：python3（自动探测）、JDK 8（自动探测）
#  底层：tools/deploy.py（编译校验 → 打包 → 刷 token → 上传 → 安装）
# ============================================================
cd "$(dirname "$0")" || exit 1

PY=""
for p in "$HOME/.workbuddy/binaries/python/versions/3.13.12/bin/python3" \
         "/usr/bin/python3" "$(command -v python3 2>/dev/null)"; do
  [ -n "$p" ] && [ -x "$p" ] && { PY="$p"; break; }
done
if [ -z "$PY" ]; then
  echo "❌ 找不到 python3"; read -r -p "按回车退出…"; exit 1
fi

echo "请选择要发布的代码包（可多选，逗号分隔；输入 all 发布全部；输入 q 退出）"
echo
echo "  1) myse          主包 other.xsy.myse（SAP 对接，全量打包）"
echo "  2) general       通用包 other.myse.general"
echo "  3) home          首页包 other.myse.home"
echo "  4) messagecenter 消息中心包 other.myse.messagecenter"
echo "  5) invoice       形式发票包 other.myse.invoice"
echo "  6) coordinator   协调员包 other.myse.coordinator"
echo
read -r -p "输入编号，如 1 或 1,3,4： " SEL
SEL="$(echo "$SEL" | tr -d ' ')"

case "$SEL" in
  ""|q|Q) echo "已退出"; exit 0 ;;
  all|ALL) PKGS="myse general home messagecenter invoice coordinator" ;;
  *)
    PKGS=""
    for n in $(echo "$SEL" | tr ',' ' '); do
      case "$n" in
        1) PKGS="$PKGS myse" ;;
        2) PKGS="$PKGS general" ;;
        3) PKGS="$PKGS home" ;;
        4) PKGS="$PKGS messagecenter" ;;
        5) PKGS="$PKGS invoice" ;;
        6) PKGS="$PKGS coordinator" ;;
        *) echo "忽略无效编号: $n" ;;
      esac
    done
    ;;
esac

if [ -z "$PKGS" ]; then
  echo "没选到任何包，已退出"; read -r -p "按回车退出…"; exit 0
fi

echo
echo "即将发布: $PKGS"
read -r -p "确认继续? [y/N] " ANS
case "$ANS" in
  y|Y|yes|YES) ;;
  *) echo "已取消"; exit 0 ;;
esac

echo
"$PY" tools/deploy.py $PKGS --yes
CODE=$?
echo
if [ $CODE -eq 0 ]; then
  echo "✅ 发布完成"
else
  echo "❌ 发布失败（退出码 $CODE），日志在 target/deploy-logs/"
fi
echo
read -r -p "按回车关闭窗口…"
exit $CODE
