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
#   8. (2026-10-29, k6 sürüm geçişi) günlük: etkin k6 (/usr/bin/k6) yoklaması "[K6] ✅ k6 bulundu — sürüm v<K6_VERSION> ("
#      diyor; Dockerfile'da K6_CANDIDATE_VERSION varsa (2026-11-16, k6 2.3 → 2.4 geçişi) ADAY yoklaması da
#      "[K6] aday k6 — sürüm v<K6_CANDIDATE_VERSION> (" diyor (aday yolu varsayılanı /opt/k6-candidate/k6, boş veritabanı);
#      hiçbir [K6] satırı "desteklenmiyor" ya da "k6 bulunamadı" demiyor — yanlış ikili ya da eksik katman görünsün. Bu adım
#      aynı zamanda okunamaz (0111) k6 ikililerinin ÇALIŞTIRILABİLDİĞİNİN kanıtıdır.
#   9. (2026-10-31, k6 inceleme A2 — işlem sertleştirmesi) günlük: "İşlem sertleştirmesi uygulandı" satırı VAR, "restricted
#      method" uyarısı YOK; konteyner içinde (SM_CONTAINER, varsayılan "sm"; docker yoksa ya da konteyner bulunamazsa bu
#      kısım uyarıyla atlanır) uygulama kullanıcısıyla `cat /proc/1/environ` (JVM dump-edilemez), `cat /usr/bin/k6` ve
#      (aday varsa) `cat /opt/k6-candidate/k6` (ikililer 0111) İZİN REDDİYLE başarısız olmalı, `<ikili> version` çalışmalı.
#      Her docker çağrısı SM_EXEC_TIMEOUT (varsayılan 30) sn ile sınırlı; zaman aşımı ve izin reddi dışındaki hata
#      (konteyner durmuş, daemon yok) "okunamıyor" sayılmaz — denetim yapılamadı hatasıdır (2026-11-17).
set -uo pipefail

BASE="${1:?kullanım: image-boot-check.sh <base-url> <log-dosyası> [varlık-listeleri-dizini]}"
LOG="${2:?kullanım: image-boot-check.sh <base-url> <log-dosyası> [varlık-listeleri-dizini]}"
LISTS="${3:-}"
fail=0
err() { echo "::error::$*"; fail=1; }

# ── 1-3: HTTP ──────────────────────────────────────────────────────────────────────────────
health="$(curl -s --max-time 10 "$BASE/health" || true)"
echo "/health → $health"
grep -q '"status":"UP"' <<<"$health" || err "/health UP değil: ${health:-<yanıt yok>}"

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

# ── 8: k6 ikilileri (2026-10-29; aday 2026-11-16) ─────────────────────────────────────────────
# Beklenen sürümler depodaki Dockerfile'dan okunur (iş akışı depoyu çeker, betik kök dizinden koşar).
k6_main="$(sed -n 's/^ARG K6_VERSION=//p' Dockerfile 2>/dev/null | head -1 | tr -d '[:space:]')"
k6_cand="$(sed -n 's/^ARG K6_CANDIDATE_VERSION=//p' Dockerfile 2>/dev/null | head -1 | tr -d '[:space:]')"
if [ -s "$LOG" ]; then
  k6log="$(sed -E 's/\x1B\[[0-9;]*[A-Za-z]//g' "$LOG" | grep -a '\[K6\]' || true)"
  if [ -n "$k6_main" ]; then
    re_main="$(printf '%s' "$k6_main" | sed 's/\./\\./g')"
    # Etkin ikilinin KENDİ satırı: aday satırı da "v… (" içerir — yalnız sürüm araması adayı etkin sanabilirdi.
    if grep -aEq "\[K6\] .*k6 bulundu .*v${re_main} \(" <<<"$k6log"; then
      echo "k6: etkin v${k6_main} bulundu"
    else
      err "günlükte etkin k6 v${k6_main} yoklaması yok (beklenen: '[K6] ✅ k6 bulundu — sürüm v${k6_main} (…)')"
      printf '%s\n' "$k6log" | head -5
    fi
  fi
  if [ -n "$k6_cand" ]; then
    re_cand="$(printf '%s' "$k6_cand" | sed 's/\./\\./g')"
    if grep -aEq "\[K6\] aday k6 .*v${re_cand} \(" <<<"$k6log"; then
      echo "k6: aday v${k6_cand} bulundu"
    else
      err "günlükte aday k6 v${k6_cand} yoklaması yok (beklenen: '[K6] aday k6 — sürüm v${k6_cand} (/opt/k6-candidate/k6)')"
      printf '%s\n' "$k6log" | head -5
    fi
  fi
  if grep -aEq "desteklenmiyor|k6 bulunamadı" <<<"$k6log"; then
    err "günlükte k6 'desteklenmiyor' / 'bulunamadı' diyor — senaryo izleme devre dışı kalır"
    printf '%s\n' "$k6log" | head -5
  fi
fi

# ── 9: işlem sertleştirmesi (2026-10-31, k6 inceleme A2) ──────────────────────────────────
# DİKKAT: `set -o pipefail` altında `printf … | grep -q` büyük girdide ilk eşleşmede çıkar, printf SIGPIPE alır ve boru
# hattı BAŞARISIZ sayılır (20.178.0 imaj testi: satır vardı, denetim "yok" dedi; ters yönde 'restricted method'
# uyarısını gizlerdi). Bu yüzden bu betikte `grep -q` girdisini here-string (`<<<`) ile alır.
if [ -s "$LOG" ]; then
  clean="$(sed -E 's/\x1B\[[0-9;]*[A-Za-z]//g' "$LOG")"
  if grep -aq "İşlem sertleştirmesi uygulandı" <<<"$clean"; then
    echo "sertleştirme: JVM dump-edilemez (günlük)"
  else
    err "günlükte 'İşlem sertleştirmesi uygulandı' satırı yok (prctl uygulanamadı ya da kapalı)"
    printf '%s\n' "$clean" | grep -a "İşlem sertleştirmesi" | head -3
  fi
  if grep -aqi "restricted method" <<<"$clean"; then
    err "günlükte 'restricted method' uyarısı var — ENTRYPOINT'te --enable-native-access=ALL-UNNAMED eksik"
    printf '%s\n' "$clean" | grep -ai "restricted method" | head -3
  fi
fi
CTR="${SM_CONTAINER:-sm}"
# Her docker çağrısı zaman aşımıyla sınırlı (2026-11-17, inceleme): takılan bir `docker exec` işi saatlerce tutmasın, ve
# zaman aşımı ya da docker'ın kendi hatası (konteyner durmuş, daemon yok) "okunamıyor = sertleştirme çalışıyor" diye
# GEÇMESİN — okunamazlık yalnız gerçek bir izin reddiyle (Permission denied / Operation not permitted) kanıtlanır.
EXEC_TIMEOUT="${SM_EXEC_TIMEOUT:-30}"
DEXEC_RC=0
DEXEC_ERR=""
# dexec <komut…>: konteynerde sınırlı süreli çalıştırır; DEXEC_RC = çıkış kodu, DEXEC_ERR = stderr (ilk 300 karakter).
dexec() {
  DEXEC_ERR="$(timeout -k 5 "$EXEC_TIMEOUT" docker exec "$CTR" "$@" 2>&1 >/dev/null)"
  DEXEC_RC=$?
  DEXEC_ERR="$(printf '%s' "$DEXEC_ERR" | tr '\r\n' '  ' | cut -c1-300)"
}
# timeout(1): 124 = süre doldu (TERM), 137 = -k sonrası KILL.
dexec_timed_out() { [ "$DEXEC_RC" -eq 124 ] || [ "$DEXEC_RC" -eq 137 ]; }
# expect_unreadable <yol> <okunabiliyorsa hata metni>
expect_unreadable() {
  dexec cat "$1"
  if dexec_timed_out; then
    err "konteynerde 'cat $1' ${EXEC_TIMEOUT} sn'de bitmedi (docker exec zaman aşımı) — izin denetimi YAPILAMADI"
  elif [ "$DEXEC_RC" -eq 0 ]; then
    err "$2"
  elif grep -qiE 'permission denied|operation not permitted' <<<"$DEXEC_ERR"; then
    echo "sertleştirme: $1 okunamıyor (izin reddi)"
  else
    err "konteynerde 'cat $1' izin reddi dışında bir hatayla bitti (çıkış $DEXEC_RC: ${DEXEC_ERR:-<stderr boş>}) — denetim YAPILAMADI"
  fi
}
# expect_runs <ikili>
expect_runs() {
  dexec "$1" version
  if dexec_timed_out; then
    err "konteynerde '$1 version' ${EXEC_TIMEOUT} sn'de bitmedi (docker exec zaman aşımı)"
  elif [ "$DEXEC_RC" -ne 0 ]; then
    err "konteynerde $1 version çalışmıyor (çıkış $DEXEC_RC: ${DEXEC_ERR:-<stderr boş>}) — 0111 ikili çalıştırılamadı"
  fi
}
if command -v docker >/dev/null 2>&1; then
  timeout -k 5 "$EXEC_TIMEOUT" docker inspect "$CTR" >/dev/null 2>&1
  inspect_rc=$?
else
  inspect_rc=127
fi
if [ "$inspect_rc" -eq 124 ] || [ "$inspect_rc" -eq 137 ]; then
  err "docker inspect $CTR ${EXEC_TIMEOUT} sn'de bitmedi (zaman aşımı) — /proc ve ikili izin denetimleri YAPILAMADI"
elif [ "$inspect_rc" -eq 0 ]; then
  expect_unreadable /proc/1/environ \
    "konteynerde uygulama kullanıcısı /proc/1/environ'u OKUYABİLİYOR — JVM dump-edilebilir (sertleştirme etkisiz)"
  expect_unreadable /usr/bin/k6 \
    "konteynerde /usr/bin/k6 OKUNABİLİYOR — 0111 izni yok (kardeş k6 süreçlerinin ortamı birbirine açık)"
  expect_runs /usr/bin/k6
  # Aday ikili (2026-11-16): uyumluluk taraması aynı betikleri aynı gizli değerlerle koşar — AYNI koruma.
  if [ -n "${k6_cand:-}" ]; then
    expect_unreadable /opt/k6-candidate/k6 \
      "konteynerde /opt/k6-candidate/k6 OKUNABİLİYOR — 0111 izni yok (aday k6 süreçlerinin ortamı birbirine açık)"
    expect_runs /opt/k6-candidate/k6
  fi
else
  echo "::warning::konteyner '$CTR' bulunamadı ya da docker yok — /proc ve ikili izin denetimleri atlandı"
fi

if [ "$fail" -ne 0 ]; then
  echo "İmaj açılış denetimi BAŞARISIZ."
  exit 1
fi
echo "İmaj açılış denetimi: tamam."
