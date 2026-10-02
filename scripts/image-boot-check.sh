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
set -uo pipefail

BASE="${1:?kullanım: image-boot-check.sh <base-url> <log-dosyası>}"
LOG="${2:?kullanım: image-boot-check.sh <base-url> <log-dosyası>}"
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
  error_lines="$(printf '%s\n' "$clean" | grep -aE '^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3} +ERROR ' || true)"
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

if [ "$fail" -ne 0 ]; then
  echo "İmaj açılış denetimi BAŞARISIZ."
  exit 1
fi
echo "İmaj açılış denetimi: tamam."
