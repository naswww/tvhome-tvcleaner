#!/usr/bin/env bash
# Generate the ADB auth key pair (PKCS#8 private key + adb-format public key).
# Derived from the AndroidTvMouse project (GPL-3.0).
#   bash scripts/genkey.sh [output_dir] [name]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/assets}"
NAME="${2:-android-tv-client}"
PY="${PYTHON:-python}"

mkdir -p "$OUT"
if [ -s "$OUT/adbkey" ] && [ -s "$OUT/adbkey.pub" ]; then
  # Do not just trust "both files exist" - a half-finished earlier run can
  # leave a private key from one generation and a .pub from another.
  EXPECTED="$("$PY" "$(cygpath -m "$ROOT/scripts/adbkey_pub.py")" "$OUT/adbkey" "$NAME" 2>/dev/null || true)"
  ACTUAL="$(cat "$OUT/adbkey.pub")"
  if [ -n "$EXPECTED" ] && [ "$EXPECTED" = "$ACTUAL" ]; then
    echo "[key] pair already exists and matches: $OUT/adbkey"
    exit 0
  fi
  echo "[key] existing key pair does not match - regenerating"
fi

command -v openssl >/dev/null 2>&1 || { echo "openssl missing"; exit 1; }

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$OUT/adbkey" 2>/dev/null
# python.exe is a Windows binary: give it a Windows path for the SCRIPT.
# The private key keeps a POSIX path because adbkey_pub.py passes it to the
# MSYS openssl binary, which understands POSIX paths.
"$PY" "$(cygpath -m "$ROOT/scripts/adbkey_pub.py")" "$OUT/adbkey" "$NAME" > "$OUT/adbkey.pub"
echo "[key] generated: $OUT/adbkey (+ .pub, name=$NAME)"
echo "[key] first launch may show 'Allow USB debugging?' on the TV - tick 'Always allow'."
