#!/usr/bin/env bash
# 发布前自检 / Go-No-Go 门禁（可随仓复跑：bash scripts/verify-release.sh）
#
# 四层控制，按重要性排序：
#   ① 精确白名单构建（本目录逐项拷贝生成；git add 只列确切文件名，禁用 -A/./通配）
#   ② 后缀/目录黑名单零命中 —— 最高危都是 KB 级小文件（keystore / adbkey / privacy.env）
#   ③ 隐私正则零命中 —— -E 正则层独立执行（-F 固定串层不能表达 IP 正则，不可互相替代）；
#      URL 与通用 loopback 用 sed「就地掩码 token」，绝不整行丢弃，避免同行敏感词被静默放行。
#      注意：grep 的 -I 会静默跳过含敏感串的二进制，故二进制核验由 ② 后缀黑名单 +
#      ④ 体积兜底独立承担，不能因为 grep 没报就认定二进制干净。
#   ④ 体积阈值仅兜底（拦不住任何小文件，不为主控制）
# 另含：硬编码口令断言、脚本语法、git 历史对象名扫描、提交身份、正向内容验收。
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
SELF="scripts/verify-release.sh"
FAIL=0
ok(){ printf '  [OK]   %s\n' "$1"; }
bad(){ printf '  [FAIL] %s\n' "$1"; FAIL=1; }

echo "=== ① 顶层白名单 ==="
ALLOWED='^(LICENSE|NOTICE\.md|README\.md|README\.en\.md|SHA256SUMS\.txt|\.gitignore|docs|src|scripts)$'
for f in * .gitignore; do
  [ -e "$f" ] || continue
  echo "$f" | grep -qE "$ALLOWED" || bad "顶层非白名单条目: $f"
done
[ "$FAIL" -eq 0 ] && ok "顶层仅含预期条目"

echo "=== ② 密钥 / 签名产物 / 二进制 / 取证目录黑名单 ==="
BLACK='(\.keystore|keystore\.pass|/adbkey(\.pub)?|\.apk|\.apks|\.idsig|\.dex|\.class|\.jar|\.pem|\.key|privacy\.env|\.raw|\.png|\.jpg|\.zip|\.log|bugreport|\.tombs)$|(^|/)(build|backup|backup_[^/]*|crash-[^/]*|tvdiag|\.workbuddy-ai|deliverables|toolchain|release|legacy)/'
B=$(find . -path ./.git -prune -o -print | grep -E "$BLACK" | grep -v '^\./\.gitignore$' || true)
if [ -n "$B" ]; then bad "黑名单命中:"; echo "$B" | sed 's/^/         /'; else ok "零命中（含 .keystore / adbkey / *.apk / build/ / 取证目录）"; fi

echo "=== ③ 隐私正则（4 段 0-255 严格版；URL / loopback 就地掩码）==="
O='(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])'
IP_RE="(10|127)[.]$O[.]$O[.]$O|(172[.](1[6-9]|2[0-9]|3[01])|192[.]168)[.]$O[.]$O"
REGEX="$IP_RE|8F:65|SHA1[[:space:]]+[0-9A-Fa-f]{2}:|([0-9A-Fa-f]{2}:){19}[0-9A-Fa-f]{2}|([0-9A-Fa-f]{2}-){5}[0-9A-Fa-f]{2}|([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}|[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){15,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_|AKIA[0-9A-Z]{16}|^[[:space:]]*-----BEGIN [A-Z ]*PRIVATE KEY-----[[:space:]]*$|Gamer|HOMEGAME|HISENSE_appdevid|HISENSE_guid|wifi_static_ip|net[.]report[.]gwaddr|yktuner@tv|pass:android|O=Local|PPR2[.]|NOVA[0-9]|RTL8192|VIDAA_TV|VIDDA_TV|naswww|[A-Za-z]:[\\/](Users|workspace|tools)|/[a-z]/(tools|workspace|Programs|Users)|/(Users|home)/[A-Za-z]|tasks? ?#[0-9]|crash-1809|\.workbuddy-ai|批[0-9]|2026-11-0[0-9]"
SCAN_TEXT=$(find . -path ./.git -prune -o -type f ! -name 'SHA256SUMS.txt' \( -name '*.md' -o -name '*.java' -o -name '*.xml' -o -name '*.sh' -o -name '*.py' -o -name '*.txt' \) -print |
while read -r f; do
  [ "$f" = "./$SELF" ] && continue        # 检查器自身含词表字面量
  sed -E \
    -e 's#https://(github[.]com|raw[.]githubusercontent[.]com)/naswww[^[:space:]]*#<REPO_URL>#g' \
    -e 's/127[.]0[.]0[.]1/<LOOPBACK>/g' "$f" \
    | grep -nE "$REGEX" | sed "s#^#$f:#"
done)
if [ -n "$SCAN_TEXT" ]; then bad "正则命中:"; printf '%s\n' "$SCAN_TEXT" | head -40 | sed 's/^/         /'; else ok "零命中（含裸 IP / :5555 / 内网段 / MAC / SHA1 / 固定词表）"; fi

echo "=== ③b 硬编码口令断言（只查字面值，参数名豁免）==="
PW_RE='pass:android|-storepass[[:space:]]+android|-keypass[[:space:]]+android|--ks-pass[[:space:]]+pass:android'
P=$(grep -rInE "$PW_RE" . --exclude-dir=.git --exclude=verify-release.sh || true)
if [ -n "$P" ]; then bad "发现硬编码签名口令:"; printf '%s\n' "$P" | sed 's/^/         /'; else ok "零命中；口令均来自 KS_PASS 环境变量或本地随机生成"; fi

echo "=== ③c 通用 loopback 逐文件断言（仅允许 .java）==="
LOOP=$(find . -path ./.git -prune -o -type f -print | while read -r f; do
  [ "$f" = "./$SELF" ] && continue
  case "$f" in *.java) continue;; esac
  grep -HnF '127.0.0.1' "$f" 2>/dev/null || true
done)
if [ -n "$LOOP" ]; then bad "非 .java 文件出现 127.0.0.1:"; printf '%s\n' "$LOOP" | sed 's/^/         /'; else ok "仅 .java 源码含通用 loopback 常量"; fi

echo "=== ④ 体积兜底（>1MB，非主控制）==="
L=$(find . -path ./.git -prune -o -type f -size +1M -print || true)
if [ -n "$L" ]; then bad "超大文件:"; echo "$L" | sed 's/^/         /'; else ok "无 >1MB 文件"; fi

echo "=== ⑤ Shell 脚本语法 ==="
SYN=0
while read -r f; do bash -n "$f" 2>/dev/null || { bad "语法错误: $f"; SYN=1; }; done < <(find scripts -name '*.sh' -type f)
[ "$SYN" -eq 0 ] && ok "全部脚本 bash -n 通过"

echo "=== ⑥ 正向内容验收 ==="
for f in README.md README.en.md LICENSE NOTICE.md SHA256SUMS.txt docs/05-dexopt-aot.md scripts/verify-release.sh; do
  [ -s "$f" ] || bad "缺失或空文件: $f"
done
grep -qF '<电视IP>' README.md && grep -qF '<pkg>' README.md || bad "占位符缺失"
grep -qF 'CleanYourTV' README.md && grep -qF 'CleanYourTV' README.en.md && grep -qF 'CleanYourTV' NOTICE.md || bad "CleanYourTV 来源声明缺失"
grep -qF 'VIDAA' README.md && grep -qF 'VIDAA' README.en.md || bad "VIDAA 适配声明缺失"
grep -qF 'WorkBuddy' README.md && grep -qF 'DeepSeek' README.md && grep -qF 'HY4' README.md || bad "WorkBuddy/DeepSeek/HY4 开发声明缺失"
grep -qF 'WorkBuddy' README.en.md && grep -qF 'DeepSeek' README.en.md && grep -qF 'HY4' README.en.md || bad "英文 README 开发声明缺失"
grep -qE '禁止.*[-]m speed' NOTICE.md && grep -qE '禁止.*[-]m speed' docs/05-dexopt-aot.md || bad "禁止 -m speed 警示缺失"
for s in 'A/B/A' 'BOOT_COUNT=407' '不可量化' '推测' '自我修正'; do
  grep -rqF "$s" docs/ || bad "诚实声明缺失: $s"
done
head -1 README.en.md | grep -qF '[中文](README.md)' || bad "英文 README 缺少语言切换首行"
grep -qiE '^## .*(position|constraint|quick|disclaimer|README)' README.en.md || bad "英文 README 章节锚点缺失"
[ -s LICENSE ] || bad "LICENSE 为空"
ok "文件 / 占位符 / 三点声明 / 警示 / 诚实声明 / 语言切换 / 许可"

echo "=== ⑦ git 历史对象名扫描（补内容正则盲点）==="
if [ -d .git ] && git rev-parse --verify HEAD >/dev/null 2>&1; then
  H=$(git rev-list --objects --all | grep -iE 'keystore|adbkey|privacy\.env|\.apk$|\.idsig$|\.raw$|bugreport|crash-' || true)
  S=$(git log --all --oneline -S'Gamer' -S'yktuner@tv' -S'pass:android' -S'192.168.' || true)
  if [ -n "$H$S" ]; then bad "历史含敏感对象/内容:"; printf '%s\n%s\n' "$H" "$S" | sed 's/^/         /'; else ok "历史干净"; fi
else printf '  [SKIP] 尚无提交（首次提交前正常）\n'; fi

echo "=== ⑧ 提交身份 ==="
if [ ! -d .git ]; then printf '  [SKIP] 尚未初始化 git，身份在 init 时设置\n';
else
  U=$(git config user.name 2>/dev/null || true); E=$(git config user.email 2>/dev/null || true)
  if echo "$U$E" | grep -qiE 'Gamer|HOMEGAME'; then bad "身份含真实用户名: $U <$E>"
  elif [ -z "$U" ]; then printf '  [WARN] 未设置 user.name（本地状态，非发布内容）\n'
  else ok "$U <$E>"; fi
fi

echo
if [ "$FAIL" -eq 0 ]; then echo "🟢 GO  —— 全部门禁通过，可推送"; else echo "🔴 NO-GO —— 存在未修复项，禁止推送"; fi
exit "$FAIL"
