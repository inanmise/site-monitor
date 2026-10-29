#!/usr/bin/env bash
# İmaj açılış testinin DENETİMLERİ (2026-10-02, onaylı öneri 28) — .github/workflows/docker-build.yml
# "image-boot-test" işi imajı boş bir PostgreSQL'e karşı prod profiliyle açar, /health UP olunca bunu çağırır.
# Yalnız okur (HTTP GET + günlük dosyası); uygulamaya ya da veritabanına dokunmaz.
#
# Kullanım:  bash scripts/image-boot-check.sh <base-url> <log-dosyası>
#   ör.      docker logs sm > boot.log 2>&1 && bash scripts/image-boot-check.sh http://localhost:8080 boot.log
#
# Denetimler (biri bile tutmazsa çıkış kodu 1, her biri ::error:: satırı):
#   1. /health → "status":"UP"
#   2. /api/branding → 200 (PUBLIC uç)       3. oturumsuz /api/me → 401 (AuthInterceptor kapısı)
#   4. /metrics → sitemonitor_schema_patch_failed = 0 ve _locked = 1 (yamalar advisory lock altında koştu)
#   5. günlük: "Şema yamaları:" özet satırı VAR, hiçbir yama/özet satırında "BAŞARISIZ" YOK
#   6. günlük: ERROR düzeyinde satır yok, "APPLICATION FAILED TO START" / ana iş parçacığı istisnası yok
#   7. (3. argüman = imajdan alınmış own-assets.txt / carried-assets.txt / carry-report.json dizini — 2026-10-23, çok pod R2b · MP-47)
#      bu derlemenin bir varlığı ve önceki sürümden TAŞINAN bir varlık 200 + "immutable"; taşınan liste bu derlemeyle
#      çakışmıyor ve sınırlı (≤ 5000); olmayan bir varlık düz metin 404 + no-store (SpaNotFoundAdvice); /boot-guard.js
#      200 + no-store (sürüm bağımsız açılış koruması hiç önbelleğe alınmaz).
#   8. (2026-10-29, k6 sürüm geçişi) günlük: ASIL k6 (/usr/bin/k6) ve ADAY k6 (/opt/k6-next/k6) yoklaması Dockerfile'daki
#      K6_VERSION / K6_NEXT_VERSION sürümünü söylüyor — yanlış ikili ya da eksik katman açılışta görünsün.
set -uo pipefail

BASE="${1:?kullanım: image-boot-check.sh <base-url> <log-dosyası> [varlık-listeleri-dizini]}"
LOG="${2:?kullanım: image-boot-check.sh <base-url> <log-dosyası> [varlık-listeleri-dizini]}"
LISTS="${3:-}"
fail=0
err() { echo "::error::$*"; fail=1; }

# ── 1-3: HTTP ──────────────────────────────────────────────────────────────────────────────
health="$(curl -s --max-time 10 "$BASE/health" || true)"
echo "/health → $health"
printf '%s' "$health" | grep -q '"status":"UP"' || err "/health UP değil: ${health:-<yanıt yok>}"

code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$BASE/api/branding" || true)"
echo "/api/branding → HTTP $code"
[ "$code" = "200" ] || err "/api/branding HTTP $code döndü (beklenen 200)"

code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$BASE/api/me" || true)"
echo "oturumsuz /api/me → HTTP $code"
[ "$code" = "401" ] || err "oturumsuz /api/me HTTP $code döndü (beklenen 401 — kimlik kapısı)"

# ── 4: şema yaması metrikleri (SchemaPatchMetrics) ─────────────────────────────────────────
metrics="$(curl -s --max-time 10 "$BASE/metrics" || true)"
gauge() { printf '%s\n' "$metrics" | awk -v n="$1" '$1 == n || index($1, n "{") == 1 { print $NF; exit }'; }
patch_failed="$(gauge sitemonitor_schema_patch_failed)"
patch_locked="$(gauge sitemonitor_schema_patch_locked)"
patch_applied="$(gauge sitemonitor_schema_patch_applied)"
patch_noop="$(gauge sitemonitor_schema_patch_noop)"
echo "şema yaması metrikleri: failed=${patch_failed:-?} applied=${patch_applied:-?} noop=${patch_noop:-?} locked=${patch_locked:-?}"
if [ -z "$patch_failed" ]; then
  err "/metrics'te sitemonitor_schema_patch_failed yok (SchemaPatchMetrics yayımlanmadı mı?)"
elif ! awk -v v="$patch_failed" 'BEGIN { exit !(v + 0 == 0) }'; then
  err "sitemonitor_schema_patch_failed = $patch_failed — boş PostgreSQL'de başarısız şema yaması var (günlükte '⚠ Şema yaması BAŞARISIZ')"
fi
if [ -n "$patch_locked" ] && ! awk -v v="$patch_locked" 'BEGIN { exit !(v + 0 == 1) }'; then
  err "sitemonitor_schema_patch_locked = $patch_locked — yamalar PostgreSQL advisory lock altında koşmadı"
fi

# ── 5-6: açılış günlüğü ────────────────────────────────────────────────────────────────────
if [ ! -s "$LOG" ]; then
  err "günlük dosyası boş ya da yok: $LOG"
else
  clean="$(sed -E 's/\x1B\[[0-9;]*[A-Za-z]//g' "$LOG")"   # ANSI renk kodları (TTY'siz zaten olmamalı)
  summary="$(printf '%s\n' "$clean" | grep -a 'Şema yamaları:' || true)"
  if [ -z "$summary" ]; then
    err "günlükte 'Şema yamaları:' özet satırı yok — applySchemaPatches koşmadı mı?"
  else
    echo "özet: $(printf '%s\n' "$summary" | tail -1)"
  fi
  # SchemaPatchRunner: tek yama "⚠ Şema yaması BAŞARISIZ (açılış sürüyor): …", özet "⚠ Şema yamaları: … N BAŞARISIZ …"
  patch_fail_lines="$(printf '%s\n' "$clean" | grep -aE 'Şema yamaları: .*BAŞARISIZ|Şema yaması BAŞARISIZ' || true)"
  if [ -n "$patch_fail_lines" ]; then
    err "şema yaması BAŞARISIZ:"
    printf '%s\n' "$patch_fail_lines"
  fi
  # Düz metin satırı ya da (prod profili 2026-10-11'den beri) JSON satırı: ECS "log.level" / {"log":{"level"…}}, logstash "level"
  error_lines="$(printf '%s\n' "$clean" | grep -aE '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3} +ERROR |^\{.*("log\.level"|"level"|"log":\{"level"):"ERROR"' || true)"
  if [ -n "$error_lines" ]; then
    err "açılışta ERROR düzeyinde günlük ($(printf '%s\n' "$error_lines" | wc -l) satır):"
    printf '%s\n' "$error_lines" | head -50
  fi
  fatal_lines="$(printf '%s\n' "$clean" | grep -aE 'APPLICATION FAILED TO START|Exception in thread "main"' || true)"
  if [ -n "$fatal_lines" ]; then
    err "açılış istisnası:"
    printf '%s\n' "$fatal_lines"
  fi
fi

# ── 7: sayfa dosyaları — önceki sürümden taşınanlar (MP-47) ───────────────────────────────
# headers <yol> → "HTTP-kodu|Content-Type|Cache-Control" (küçük harfli başlık adları)
headers() {
  curl -s -o /dev/null -D - --max-time 10 "$BASE$1" | tr -d '\r' | awk '
    NR == 1 { code = $2 }
    tolower($0) ~ /^content-type:/ { sub(/^[^:]*: */, ""); ct = $0 }
    tolower($0) ~ /^cache-control:/ { sub(/^[^:]*: */, ""); cc = $0 }
    END { print code "|" ct "|" cc }'
}
if [ -n "$LISTS" ]; then
  own_list="$LISTS/own-assets.txt"
  carried_list="$LISTS/carried-assets.txt"
  if [ ! -s "$own_list" ]; then
    err "imajda /app/frontend/own-assets.txt yok ya da boş — sonraki sürüm bu sürümün sayfa dosyalarını TAŞIYAMAZ (Dockerfile asset-carry)"
  else
    first_own="$(grep -m1 . "$own_list")"
    h="$(headers "/assets/$first_own")"
    echo "bu derlemenin varlığı /assets/$first_own → $h"
    case "$h" in 200\|*\|*immutable*) ;; *) err "bu derlemenin varlığı 200 + immutable değil: /assets/$first_own → $h" ;; esac
  fi
  if [ ! -f "$carried_list" ]; then
    err "imajda /app/frontend/carried-assets.txt yok (Dockerfile asset-carry çalışmadı mı?)"
  elif [ ! -s "$carried_list" ]; then
    echo "::notice::önceki sürümlerden taşınan sayfa dosyası yok — arayüz paketi son sürümlerden beri değişmemiş, ilk derleme ya da önceki imaj okunamadı (carry-report.json: $(cat "$LISTS/carry-report.json" 2>/dev/null || echo yok)) — hata değil"
  else
    carried="$(grep -c . "$carried_list")"
    echo "önceki sürümden taşınan sayfa dosyası: $carried"
    [ "$carried" -le 5000 ] || err "taşınan dosya sayısı $carried > 5000 — yalnız son birkaç sürümün varlıkları taşınmalı (sınır)"
    overlap="$(grep -cxF -f "$own_list" "$carried_list" || true)"
    [ "${overlap:-0}" = "0" ] || err "$overlap taşınan ad bu derlemede de var (taşıma yeni derlemenin adlarını atlamalı)"
    for name in "$(grep -m1 . "$carried_list")" "$(grep . "$carried_list" | tail -1)"; do
      h="$(headers "/assets/$name")"
      echo "önceki sürümün varlığı /assets/$name → $h"
      case "$h" in 200\|*\|*immutable*) ;; *) err "önceki sürümden taşınan varlık sunulmuyor (200 + immutable beklenir): /assets/$name → $h" ;; esac
    done
  fi
  h="$(headers "/assets/boot-check-missing-$RANDOM$RANDOM.js")"
  echo "olmayan varlık → $h"
  case "$h" in 404\|text/plain*\|*no-store*) ;; *) err "olmayan /assets/*.js düz metin 404 + no-store değil (SpaNotFoundAdvice — immutable bir 404 yıllarca beyaz ekran demek): $h" ;; esac
  h="$(headers "/boot-guard.js")"
  echo "/boot-guard.js → $h"
  case "$h" in 200\|*javascript*\|*no-store*) ;; *) err "/boot-guard.js 200 + javascript + no-store değil (açılış koruması): $h" ;; esac
fi

# ── 8: k6 ikilileri (2026-10-29) ───────────────────────────────────────────────────────────
# Beklenen sürümler depodaki Dockerfile'dan okunur (iş akışı depoyu çeker, betik kök dizinden koşar).
k6_main="$(sed -n 's/^ARG K6_VERSION=//p' Dockerfile 2>/dev/null | head -1 | tr -d '[:space:]')"
k6_next="$(sed -n 's/^ARG K6_NEXT_VERSION=//p' Dockerfile 2>/dev/null | head -1 | tr -d '[:space:]')"
if [ -s "$LOG" ]; then
  k6log="$(sed -E 's/\x1B\[[0-9;]*[A-Za-z]//g' "$LOG" | grep -a '\[K6\]' || true)"
  if [ -n "$k6_main" ]; then
    re_main="$(printf '%s' "$k6_main" | sed 's/\./\\./g')"
    if printf '%s\n' "$k6log" | grep -aEq "\[K6\] .*v${re_main} \("; then
      echo "asıl k6: v${k6_main} bulundu"
    else
      err "günlükte asıl k6 v${k6_main} yoklaması yok (beklenen: '[K6] ✅ k6 bulundu — sürüm v${k6_main}')"
      printf '%s\n' "$k6log" | head -5
    fi
  fi
  if [ -n "$k6_next" ]; then
    re_next="$(printf '%s' "$k6_next" | sed 's/\./\\./g')"
    if printf '%s\n' "$k6log" | grep -aEq "\[K6\] aday k6 .*v${re_next} \(/opt/k6-next/k6\)"; then
      echo "aday k6: v${k6_next} bulundu (/opt/k6-next/k6)"
    else
      err "günlükte aday k6 v${k6_next} yoklaması yok (beklenen: '[K6] aday k6 — sürüm v${k6_next} (/opt/k6-next/k6)')"
      printf '%s\n' "$k6log" | head -5
    fi
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "İmaj açılış denetimi BAŞARISIZ."
  exit 1
fi
echo "İmaj açılış denetimi: tamam."
