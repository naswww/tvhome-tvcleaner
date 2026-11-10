#!/usr/bin/env bash
# Build TVHome WITHOUT Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
# 依赖 JDK 17+ 与 Android SDK（platform + build-tools）。
# 请用 JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT 指定路径；
# 也可把工具链放在工程目录或上级目录的 toolchain/ 下。
# 需在 Git Bash / MSYS 环境运行（用到 cygpath 与 Windows 版 build-tools）。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export MSYS_NO_PATHCONV=1

VERNAME="${1:-1.0.0}"
VERCODE="${2:-1}"
OUTNAME="${3:-tvhome-v$VERNAME}"

W() { cygpath -m "$1"; }

# ---------------------------------------------------------------- JDK ----
JDK=""
for c in "${JAVA_HOME:-}" "${JDK_HOME:-}" "$ROOT/toolchain"/jdk-17* "$(dirname "$ROOT")/toolchain"/jdk-17*; do
  [ -n "$c" ] || continue
  if [ -x "$c/bin/javac.exe" ] || [ -x "$c/bin/javac" ]; then JDK="$c"; break; fi
done
[ -n "$JDK" ] || { echo "[ERROR] JDK not found. Set JAVA_HOME"; exit 1; }
bin() { if [ -x "$JDK/bin/$1.exe" ]; then echo "$JDK/bin/$1.exe"; else echo "$JDK/bin/$1"; fi; }
JAVA="$(bin java)"; JAVAC="$(bin javac)"; KEYTOOL="$(bin keytool)"; JAR="$(bin jar)"

# ---------------------------------------------------------------- SDK ----
SDK=""
for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
         "$ROOT/toolchain/android-sdk" "$(dirname "$ROOT")/toolchain/android-sdk"; do
  [ -n "$c" ] && [ -d "$c/platforms" ] && SDK="$c" && break
done
[ -n "$SDK" ] || { echo "[ERROR] Android SDK not found. Set ANDROID_HOME"; exit 1; }
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 | sed 's:/$::')"
PLAT="$(ls -d "$SDK"/platforms/android-*/ 2>/dev/null | sort -V | tail -1 | sed 's:/$::')"
ANDROID_JAR="$PLAT/android.jar"
AAPT2="$BT/aapt2.exe"; [ -x "$AAPT2" ] || AAPT2="$BT/aapt2"
ZIPALIGN="$BT/zipalign.exe"; [ -x "$ZIPALIGN" ] || ZIPALIGN="$BT/zipalign"
for f in "$ANDROID_JAR" "$AAPT2" "$ZIPALIGN" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar"; do
  [ -e "$f" ] || { echo "[ERROR] missing $f"; exit 1; }
done
echo "[env] JDK=$JDK"
echo "[env] SDK=$SDK  BT=$(basename "$BT")  PLAT=$(basename "$PLAT")"

OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

MANIFEST="$OUT/AndroidManifest.xml"
sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VERCODE\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERNAME\"/" \
    "$ROOT/AndroidManifest.xml" > "$MANIFEST"

echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir "$(W "$ROOT/res")" -o "$(W "$OUT/res.zip")"

echo "[2/6] aapt2 link"
"$AAPT2" link \
  -o "$(W "$OUT/base.apk")" \
  -I "$(W "$ANDROID_JAR")" \
  --manifest "$(W "$MANIFEST")" \
  -R "$(W "$OUT/res.zip")" \
  --java "$(W "$OUT/gen")" \
  --min-sdk-version 21 --target-sdk-version 28 \
  --version-code "$VERCODE" --version-name "$VERNAME" \
  --auto-add-overlay

echo "[3/6] javac"
find "$ROOT/src" "$OUT/gen" -name '*.java' | while read -r f; do W "$f"; done > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 --release 8 -nowarn \
  -classpath "$(W "$ANDROID_JAR")" \
  -d "$(W "$OUT/classes")" "@$(W "$OUT/sources.txt")"

echo "[4/6] d8 -> classes.dex"
find "$OUT/classes" -name '*.class' | while read -r f; do W "$f"; done > "$OUT/classes.txt"
"$JAVA" -cp "$(W "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
  --min-api 21 --lib "$(W "$ANDROID_JAR")" \
  --output "$(W "$OUT/dex")" \
  $(cat "$OUT/classes.txt")
"$JAR" --update --file "$(W "$OUT/base.apk")" -C "$(W "$OUT/dex")" classes.dex

echo "[5/6] zipalign"
"$ZIPALIGN" -f -p 4 "$(W "$OUT/base.apk")" "$(W "$OUT/aligned.apk")"

echo "[6/6] sign"
KS="$ROOT/.keystore/tvhome.keystore"
# 签名口令：绝不在仓库内硬编码。优先环境变量注入；否则本地随机生成并保存在
# .keystore/ 下（该目录已被 .gitignore 排除）。已有 keystore 时必须显式提供 KS_PASS。
KS_PASS="${KS_PASS:-}"
PASS_FILE="$ROOT/.keystore/keystore.pass"
mkdir -p "$(dirname "$KS")"
if [ -f "$KS" ] && [ -z "$KS_PASS" ] && [ -s "$PASS_FILE" ]; then
  KS_PASS="$(cat "$PASS_FILE")"
elif [ -f "$KS" ] && [ -z "$KS_PASS" ]; then
  echo "[!] keystore 已存在但未提供口令，请设置 KS_PASS 环境变量" >&2; exit 1
elif [ ! -f "$KS" ] && [ -z "$KS_PASS" ]; then
  KS_PASS="$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')"
  printf '%s' "$KS_PASS" > "$PASS_FILE"
fi
if [ ! -f "$KS" ]; then
  echo "[key] generating local signing keystore (random password, not committed)"
  "$KEYTOOL" -genkeypair -keystore "$(W "$KS")" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias tvhome -dname "CN=TVHome" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$JAVA" -jar "$(W "$BT/lib/apksigner.jar")" sign \
  --ks "$(W "$KS")" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$(W "$ROOT/$OUTNAME.apk")" "$(W "$OUT/aligned.apk")"

echo
echo "BUILD OK: $ROOT/$OUTNAME.apk  (v$VERNAME / $VERCODE)"
ls -lh "$ROOT/$OUTNAME.apk"
