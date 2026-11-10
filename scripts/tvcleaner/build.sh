#!/usr/bin/env bash
# Build TV Memory Cleaner WITHOUT Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
# Run in Git Bash on Windows:   bash scripts/build.sh [versionName] [versionCode] [outName]
# Prerequisites: JDK 17+, Android SDK platform + build-tools, python, openssl.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export MSYS_NO_PATHCONV=1

VERNAME="${1:-1.0.0}"
VERCODE="${2:-1}"
OUTNAME="${3:-tvcleaner-v$VERNAME}"

W() { cygpath -m "$1"; }

# ---------------------------------------------------------------- JDK ----
# 工具链位置一律由环境变量或工程内相对路径给出，不写死任何机器专属绝对路径。
# 请用 JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT 指定；也可放在 toolchain/ 下。
JDK=""
if [ -n "${JAVA_HOME:-}" ]; then
  if [ -x "${JAVA_HOME}/bin/javac.exe" ] || [ -x "${JAVA_HOME}/bin/javac" ]; then JDK="${JAVA_HOME}"; fi
fi
if [ -z "$JDK" ]; then
  for c in "$HOME/.gradle/jdks"/* "$ROOT/toolchain"/jdk-17* "$(dirname "$ROOT")/toolchain"/jdk-17*; do
    [ -d "$c/bin" ] || continue
    if [ -x "$c/bin/javac.exe" ] || [ -x "$c/bin/javac" ]; then JDK="$c"; break; fi
  done
fi
[ -n "$JDK" ] || { echo "[ERROR] JDK not found. Set JAVA_HOME"; exit 1; }
bin() { if [ -x "$JDK/bin/$1.exe" ]; then echo "$JDK/bin/$1.exe"; else echo "$JDK/bin/$1"; fi; }
JAVA="$(bin java)"; JAVAC="$(bin javac)"; KEYTOOL="$(bin keytool)"; JAR="$(bin jar)"
echo "[env] JDK      = $JDK"

# ---------------------------------------------------------------- SDK ----
SDK=""
for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
         "$ROOT/toolchain/android-sdk" "$(dirname "$ROOT")/toolchain/android-sdk"; do
  [ -n "$c" ] && [ -d "$c/platforms" ] && SDK="$c" && break
done
[ -n "$SDK" ] || { echo "[ERROR] Android SDK not found. Set ANDROID_HOME"; exit 1; }
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 | sed 's:/$::')"
MAJOR="$(basename "$BT" | cut -d. -f1)"
if [ -f "$SDK/platforms/android-$MAJOR/android.jar" ]; then
  PLAT="$SDK/platforms/android-$MAJOR"
else
  PLAT="$(ls -d "$SDK"/platforms/android-*/ 2>/dev/null | sort -V | tail -1 | sed 's:/$::')"
fi
ANDROID_JAR="$PLAT/android.jar"
[ -f "$ANDROID_JAR" ] || { echo "[ERROR] missing $ANDROID_JAR"; exit 1; }
AAPT2="$BT/aapt2.exe"; [ -x "$AAPT2" ] || AAPT2="$BT/aapt2"
ZIPALIGN="$BT/zipalign.exe"; [ -x "$ZIPALIGN" ] || ZIPALIGN="$BT/zipalign"
[ -x "$AAPT2" ] || { echo "[ERROR] missing aapt2 in $BT"; exit 1; }
[ -x "$ZIPALIGN" ] || { echo "[ERROR] missing zipalign in $BT"; exit 1; }
[ -f "$BT/lib/d8.jar" ] || { echo "[ERROR] missing $BT/lib/d8.jar"; exit 1; }
[ -f "$BT/lib/apksigner.jar" ] || { echo "[ERROR] missing $BT/lib/apksigner.jar"; exit 1; }
echo "[env] SDK      = $SDK"
echo "[env] buildtools = $BT"
echo "[env] platform = $PLAT"

# ------------------------------------------------------------ adb key ----
# adbkey / adbkey.pub are embedded and already trusted by this TV.
# NEVER regenerate them: a new key would pop the "Allow USB debugging?" dialog.
if [ ! -s "$ROOT/assets/adbkey" ] || [ ! -s "$ROOT/assets/adbkey.pub" ]; then
  echo "[ERROR] embedded adb key missing in assets/"; exit 1
fi

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
  -A "$(W "$ROOT/assets")" \
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
# Persistent keystore: never regenerate, otherwise updates cannot overwrite.
KS="$ROOT/.keystore/tvcleaner.keystore"
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
    -alias tvcleaner -dname "CN=TVCleaner" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$JAVA" -jar "$(W "$BT/lib/apksigner.jar")" sign \
  --ks "$(W "$KS")" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$(W "$ROOT/$OUTNAME.apk")" "$(W "$OUT/aligned.apk")"

echo
echo "BUILD OK: $ROOT/$OUTNAME.apk  (v$VERNAME / code $VERCODE)"
echo -n "signer SHA256: "
"$KEYTOOL" -printcert -jarfile "$(W "$ROOT/$OUTNAME.apk")" 2>/dev/null \
  | grep -i "SHA256:" | head -1 | sed 's/^[[:space:]]*//'
ls -lh "$ROOT/$OUTNAME.apk"
