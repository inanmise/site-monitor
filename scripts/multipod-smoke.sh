#!/usr/bin/env bash
# Çok pod duman testi (2026-10-21, çok pod sürüm 1 — PLAN §2.6 madde 7 / §2.7).
#
# İki kip:
#
#   run (varsayılan) — Docker ile iki uygulama kapsayıcısı (hostname pod-a / pod-b, prod profili, JDBC oturum deposu,
#   Helm'deki gibi salt-okunur kök dosya sistemi) + tek kullanımlık bir PostgreSQL açar ve şunları denetler:
#     1. tarama liderliği kirasını TEK pod tutar (DB satırı + her pod'un kendi görüşü: held_by_me toplamı 1);
#     2. A'da açılan oturum B'de geçerli (aynı SESSION çerezi → /api/me 200, oturum PostgreSQL'de);
#     3. SMOKE_SWEEP_SECONDS boyunca (varsayılan 600) taslak hedeflere (PostgreSQL portu, takipçi pod'un /health'i)
#        karşı izleme taramaları koşar; lider bu sürede el değiştirmez;
#     4. lidere SIGTERM → takipçi kirayı ≤ SMOKE_HANDOVER_MAX_S (40) saniyede alır; ölen pod SMOKE_SHUTDOWN_MAX_S
#        (110 = Helm grace 120 − preStop 10) içinde kapanır; yeni lider taramaya devam eder;
#     5. (çok pod Sürüm 3-1) kalıcı değerlendirme durumu: ilk liderde ve HER devirden sonra yeni liderde Sistem Sağlığı
#        data.eval_state — kayıt yazıyor (active), kümenin son doğrulaması bu pod'un kuşağında "ok" ve 0 uyuşmazlık
#        (devirden hemen sonraki "unknown / resync | no_generation" turları beklenir, SMOKE_EVAL_WAIT_S içinde yeniden
#        denenir; "mismatch" hemen hata), devirden sonra this_pod.inherited önceki kuşağı sayıyor. Doğrulama aralığı
#        kısaltılır (SMOKE_EVAL_VERIFY_MS → SITE_MONITOR_EVAL_STATE_VERIFY_MS). Ardından (SMOKE_FLIP_BACK=1) eski lider
#        yeniden açılır ve şimdiki lidere SIGTERM → kira geri döner, aynı denetim yeni kuşakta tekrarlanır;
#     5b. (çok pod Sürüm 3-2, yalnız SMOKE_EVAL_READ=1) devirde okuma: ilk doğrulamadan sonra
#        site.monitor.eval-state.read-enabled Genel Ayarlar uç noktasıyla açılır; iki pod ayarı görene ve lider
#        "devir hazırlığı"nı (this_pod.handover_ready) bildirene dek beklenir. Her SIGTERM devrinde eski liderin günlüğünde
#        "Devir öncesi kalıcı durum boşaltıldı ve işaretlendi", yeni liderde data.eval_state.load.outcome = "full"
#        (önceki kuşaktan) ve eşitlemeden sonra doğrulama ok; sonra (SMOKE_EVAL_KILL=1) lidere SIGKILL → kira süresi
#        dolunca devralan pod "observations" yükler (devir işareti yok), doğrulaması yine ok; öldürülen pod yeniden açılır;
#     5c. (çok pod Sürüm 3-3, yalnız SMOKE_DISTRIBUTED=1) dağıtık kontroller: taslak izlemelere ~30 sn süren on yavaş port
#        izlemesi eklenir (PostgreSQL'in ek ağ adlarına BANNER, teyitsiz), ana anahtar site.monitor.checks.distributed.enabled
#        açılır (tüm türler). ≤ SMOKE_DIST_WORKERS_WAIT_S (300) içinde iki pod'un işçisi canlı ve takipçi iş tamamlamış
#        (monitor_check_workers.completed > 0; liderinki 0 ise uyarı — lider payı küçük kuyrukta 0 kalabilir); takipçiye
#        SIGKILL → sahiplendiği işler kira dolunca (≤ SMOKE_DIST_RERUN_MAX_S, kira 150 sn) başka pod'a geçer, ölen pod
#        ölümünden sonra iş bitirmez; takipçi yeniden açılır ve yine iş tamamlar. Her SIGTERM devrinde devirden önce biten
#        DONE satırları yeni liderin ilk turlarında boşaltılır (≤ SMOKE_DIST_DRAIN_MAX_S). Sonda anahtar kapatılır →
#        SMOKE_DIST_OFF_GRACE_S (12) sonra iş kimlik dizisi artmaz (yeni iş yok) ve klasik taramalar satır yazar; dedektörün
#        dönem çifti denetimi (bölüm 9b) bu koşuda her durumda sayılır (period_strict=1);
#     6. scripts/multipod-dup-check.sql → DUP_TOTAL = 0 ve STUCK_TOTAL = 0 (çift kontrol / bildirim / push / açık
#        alarm / ONGOING kesinti yok, sonuçsuz QUEUED_RETRY yok).
#   Kapsayıcılar ve ağ sonunda silinir (SMOKE_KEEP=1 bırakır); günlükler SMOKE_WORKDIR'e yazılır.
#   E-posta kapalıdır, envanter boştur, alıcı yoktur: dışarıya hiçbir bildirim gitmez.
#
#   dup-check — yalnız çift-iş dedektörünü verilen veritabanına karşı koşar (staging'de 24-48 saat boyunca günlük,
#   prod'da 2 pod'a geçişten sonra bir hafta). psql + standart PG* ortam değişkenleri (PGHOST, PGPORT, PGDATABASE,
#   PGUSER, PGPASSWORD). Salt okur. DUP_TOTAL ya da STUCK_TOTAL > 0 ise çıkış kodu 1 (satırları tek tek inceleyin —
#   yanlış pozitifler SQL dosyasının başında anlatılır).
#
# Kullanım:
#   SMOKE_IMAGE=ghcr.io/<owner>/site-monitor:v20.165.0 bash scripts/multipod-smoke.sh
#   SMOKE_IMAGE=site-monitor:local SMOKE_SWEEP_SECONDS=120 bash scripts/multipod-smoke.sh      # kısa koşu
#   PGHOST=… PGDATABASE=… PGUSER=… PGPASSWORD=… bash scripts/multipod-smoke.sh dup-check
#
# Ortam (run): SMOKE_IMAGE (zorunlu), SMOKE_PG_IMAGE, SMOKE_PORT_A / SMOKE_PORT_B (18080 / 18081, yalnız 127.0.0.1),
#   SMOKE_SWEEP_SECONDS (600), SMOKE_HANDOVER_MAX_S (40), SMOKE_SHUTDOWN_MAX_S (110), SMOKE_POST_HANDOVER_S (90),
#   SMOKE_MEMORY (1g), SMOKE_CPUS (2), SMOKE_WORKDIR, SMOKE_KEEP (0), SMOKE_STRICT_LOGS (0: günlükteki ERROR satırları
#   uyarıdır; 1: hata), SMOKE_EVAL_STATE (auto: data.eval_state yoksa — R3-1 öncesi imaj — uyarı + adımlar atlanır;
#   1: yoksa hata; 0: atla), SMOKE_EVAL_VERIFY_MS (60000; pod'lara doğrulama aralığı), SMOKE_EVAL_WAIT_S (4 × aralık + 60),
#   SMOKE_FLIP_BACK (1: geri devir de denenir; 0: tek devir), SMOKE_EVAL_READ (0; 1: çok pod Sürüm 3-2 devirde okuma
#   denetimleri — 3-2 imajı ister), SMOKE_EVAL_KILL (1: SMOKE_EVAL_READ=1 iken SIGKILL adımı da koşar; 0: atla),
#   SMOKE_KILL_TAKEOVER_MAX_S (300: SIGKILL'den sonra kiranın el değiştirmesi için en uzun bekleme — kira 180 sn + yenileyici),
#   SMOKE_DISTRIBUTED (0; 1: çok pod Sürüm 3-3 dağıtık kontrol denetimleri — 3-3 imajı ister), SMOKE_DIST_WORKERS_WAIT_S (300),
#   SMOKE_DIST_RERUN_MAX_S (180: SIGKILL'den sonra ölen işçinin işlerinin başka pod'a geçmesi), SMOKE_DIST_DRAIN_MAX_S (120:
#   devirden sonra eski DONE satırlarının boşalması), SMOKE_DIST_OFF_GRACE_S (12: kapatmadan sonra yeni iş beklenmeyen an),
#   SMOKE_DIST_OFF_WATCH_S (60: kapatmadan sonra iş dizisinin izlendiği süre).
# Gerekenler: docker, curl, bash. Yerel makinede koşulabilir (Git Bash dahil); prod/staging kümesine dokunmaz. JSON
# alanları tek kullanımlık PostgreSQL kapsayıcısının json işleçleriyle okunur (jq gerekmez).
# `set -e` bilerek YOK: denetimler hataları `err` ile toplayıp sonda tek karar verir (bir denetimin düşmesi ötekileri durdurmaz).
set -uo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'   # Git Bash: docker argümanlarındaki /var/log vb. Windows yoluna çevrilmesin

MODE="${1:-run}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DUP_SQL="$ROOT/scripts/multipod-dup-check.sql"
fail=0
err()  { echo "::error::$*"; fail=1; }
warn() { echo "::warning::$*"; }
ok()   { echo "  ok: $*"; }
step() { echo; echo "── $*"; }

# DUP_TOTAL / STUCK_TOTAL'ı dedektör çıktısından okur ve değerlendirir. $1 = çıktı dosyası
judge_dup_output() {
  local out="$1" dup stuck
  dup="$(sed -n 's/^DUP_TOTAL=\([0-9][0-9]*\).*/\1/p' "$out" | tail -1)"
  stuck="$(sed -n 's/^STUCK_TOTAL=\([0-9][0-9]*\).*/\1/p' "$out" | tail -1)"
  if grep -q 'ERROR:' "$out"; then
    err "dedektör sorgularından biri hata verdi (sonuç eksik):"
    grep -B2 -A3 'ERROR:' "$out" | head -30
  fi
  if [ -z "$dup" ] || [ -z "$stuck" ]; then
    err "dedektör özeti okunamadı (DUP_TOTAL / STUCK_TOTAL satırı yok) — $out"
    return
  fi
  [ "$dup" = "0" ]   && ok "çift iş adayı yok (DUP_TOTAL=0)"   || err "DUP_TOTAL=$dup — ayrıntı: $out"
  [ "$stuck" = "0" ] && ok "sonuçsuz bildirim yok (STUCK_TOTAL=0)" || err "STUCK_TOTAL=$stuck — ayrıntı: $out"
}

# ══ dup-check kipi ════════════════════════════════════════════════════════════════════════════
if [ "$MODE" = "dup-check" ]; then
  command -v psql >/dev/null 2>&1 || { echo "::error::psql bulunamadı"; exit 1; }
  out="${SMOKE_WORKDIR:-.}/multipod-dup-check-$(date -u +%Y%m%dT%H%M%SZ).txt"
  echo "Çift-iş dedektörü: ${PGHOST:-<PGHOST>}/${PGDATABASE:-<PGDATABASE>} → $out"
  psql -X -v ON_ERROR_STOP=0 < "$DUP_SQL" > "$out" 2>&1   # stdin: Git Bash yol çevirisi kapalıyken de çalışır
  cat "$out"
  judge_dup_output "$out"
  [ "$fail" -eq 0 ] && { echo "Çift-iş dedektörü: temiz."; exit 0; }
  echo "Çift-iş dedektörü: İNCELENECEK satırlar var."; exit 1
fi
[ "$MODE" = "run" ] || { echo "kullanım: multipod-smoke.sh [run|dup-check]"; exit 2; }

# ══ run kipi ══════════════════════════════════════════════════════════════════════════════════
IMAGE="${SMOKE_IMAGE:-}"
[ -n "$IMAGE" ] || { echo "::error::SMOKE_IMAGE zorunlu (ör. ghcr.io/<owner>/site-monitor:vX.Y.Z ya da scripts/build-image.sh çıktısı)"; exit 2; }
PG_IMAGE="${SMOKE_PG_IMAGE:-postgres:16.15-trixie@sha256:1a6ab3f5345eb6dbe04a1349529caabdb0ab09293a09590fad07b2246bfa4b54}"
PORT_A="${SMOKE_PORT_A:-18080}"
PORT_B="${SMOKE_PORT_B:-18081}"
SWEEP_S="${SMOKE_SWEEP_SECONDS:-600}"
HANDOVER_MAX_S="${SMOKE_HANDOVER_MAX_S:-40}"
SHUTDOWN_MAX_S="${SMOKE_SHUTDOWN_MAX_S:-110}"
POST_HANDOVER_S="${SMOKE_POST_HANDOVER_S:-90}"
MEMORY="${SMOKE_MEMORY:-1g}"
CPUS="${SMOKE_CPUS:-2}"
KEEP="${SMOKE_KEEP:-0}"
STRICT_LOGS="${SMOKE_STRICT_LOGS:-0}"
EVAL_CHECK="${SMOKE_EVAL_STATE:-auto}"
EVAL_VERIFY_MS="${SMOKE_EVAL_VERIFY_MS:-60000}"
FLIP_BACK="${SMOKE_FLIP_BACK:-1}"
EVAL_READ="${SMOKE_EVAL_READ:-0}"
EVAL_KILL="${SMOKE_EVAL_KILL:-1}"
KILL_TAKEOVER_MAX_S="${SMOKE_KILL_TAKEOVER_MAX_S:-300}"
case "$EVAL_READ" in 0|1) ;; *) echo "::error::SMOKE_EVAL_READ 0 | 1 olmalı"; exit 2 ;; esac
case "$EVAL_KILL" in 0|1) ;; *) echo "::error::SMOKE_EVAL_KILL 0 | 1 olmalı"; exit 2 ;; esac
case "$KILL_TAKEOVER_MAX_S" in ''|*[!0-9]*) echo "::error::SMOKE_KILL_TAKEOVER_MAX_S sayı olmalı (sn)"; exit 2 ;; esac
DIST="${SMOKE_DISTRIBUTED:-0}"
DIST_WORKERS_WAIT_S="${SMOKE_DIST_WORKERS_WAIT_S:-300}"
DIST_RERUN_MAX_S="${SMOKE_DIST_RERUN_MAX_S:-180}"
DIST_DRAIN_MAX_S="${SMOKE_DIST_DRAIN_MAX_S:-120}"
DIST_OFF_GRACE_S="${SMOKE_DIST_OFF_GRACE_S:-12}"
DIST_OFF_WATCH_S="${SMOKE_DIST_OFF_WATCH_S:-60}"
case "$DIST" in 0|1) ;; *) echo "::error::SMOKE_DISTRIBUTED 0 | 1 olmalı"; exit 2 ;; esac
for v in DIST_WORKERS_WAIT_S DIST_RERUN_MAX_S DIST_DRAIN_MAX_S DIST_OFF_GRACE_S DIST_OFF_WATCH_S; do
  case "${!v}" in ''|*[!0-9]*) echo "::error::SMOKE_$v sayı olmalı (sn)"; exit 2 ;; esac
done
case "$EVAL_VERIFY_MS" in ''|*[!0-9]*) echo "::error::SMOKE_EVAL_VERIFY_MS sayı olmalı (ms)"; exit 2 ;; esac
EVAL_WAIT_S="${SMOKE_EVAL_WAIT_S:-$(( EVAL_VERIFY_MS / 1000 * 4 + 60 ))}"
case "$EVAL_WAIT_S" in ''|*[!0-9]*) echo "::error::SMOKE_EVAL_WAIT_S sayı olmalı (sn)"; exit 2 ;; esac
case "$EVAL_CHECK" in auto|0|1) ;; *) echo "::error::SMOKE_EVAL_STATE auto | 0 | 1 olmalı"; exit 2 ;; esac
EVAL_SKIP=0; [ "$EVAL_CHECK" = "0" ] && EVAL_SKIP=1
if [ "$EVAL_READ" = "1" ] && [ "$EVAL_CHECK" = "0" ]; then
  echo "::error::SMOKE_EVAL_READ=1 kalıcı durum denetimlerini ister — SMOKE_EVAL_STATE=0 ile birlikte kullanılamaz"; exit 2
fi
WORK="${SMOKE_WORKDIR:-$(mktemp -d 2>/dev/null || echo "./multipod-smoke-$$")}"
mkdir -p "$WORK"

for tool in docker curl od; do
  command -v "$tool" >/dev/null 2>&1 || { echo "::error::$tool bulunamadı"; exit 2; }
done

rand_hex() { od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'; }
PREFIX="sm-smoke-$(rand_hex 3)"
NET="$PREFIX-net"
PG="$PREFIX-pg"
DB_NAME="sitemonitor"; DB_USER="smoke"; DB_PASS="$(rand_hex 16)"
ADMIN_USER="smokeadmin"; ADMIN_PASS="Smoke-$(rand_hex 8)"
SECRET_KEY="$(rand_hex 32)"
GRACE_STOP_S=$((SHUTDOWN_MAX_S + 10))

port_of() { if [ "$1" = "pod-a" ]; then echo "$PORT_A"; else echo "$PORT_B"; fi; }
cname() { echo "$PREFIX-$1"; }

cleanup() {
  for h in pod-a pod-b; do docker logs "$(cname "$h")" > "$WORK/$h.log" 2>&1 || true; done
  docker logs "$PG" > "$WORK/postgres.log" 2>&1 || true
  if [ "$KEEP" = "1" ]; then
    echo "SMOKE_KEEP=1 → kapsayıcılar bırakıldı: $(cname pod-a) $(cname pod-b) $PG (ağ $NET)"
  else
    docker rm -f "$(cname pod-a)" "$(cname pod-b)" "$PG" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
  fi
  echo "Günlükler: $WORK"
}
trap cleanup EXIT

psql_pg() { docker exec -i "$PG" psql -X -q -At -F '|' -U "$DB_USER" -d "$DB_NAME" "$@"; }
now_ms() { echo $(( $(date +%s) * 1000 )); }
iso_utc() { date -u +%Y-%m-%dT%H:%M:%S; }

# lease_info → "<owner>|<until_ms>" (yoksa boş)
lease_info() { psql_pg -c "SELECT claimed_by, next_due_at FROM monitor_check_schedule WHERE monitor_key = 'lease:sweep-leader'" 2>/dev/null | head -1; }
# lease_holder → geçerli kiranın sahibinin hostname'i (pod-a | pod-b) ya da boş
lease_holder() {
  local li owner until
  li="$(lease_info)"; [ -n "$li" ] || return 0
  owner="${li%%|*}"; until="${li##*|}"
  [ -n "$until" ] && [ "$until" -ge "$(now_ms)" ] 2>/dev/null || return 0
  case "$owner" in pod-a-*) echo pod-a ;; pod-b-*) echo pod-b ;; *) echo "?$owner" ;; esac
}

start_pod() {
  local host="$1" port="$(port_of "$1")"
  docker run -d --name "$(cname "$host")" --hostname "$host" --network "$NET" --network-alias "$host" \
    -p "127.0.0.1:$port:8080" \
    --read-only --tmpfs /tmp:rw,exec,mode=1777 --tmpfs /var/log:rw,mode=1777 \
    --memory "$MEMORY" --cpus "$CPUS" \
    -e SPRING_PROFILES_ACTIVE=prod -e LOG_LEVEL=INFO -e LOG_FORMAT=text \
    -e DB_HOST=pg -e DB_PORT=5432 -e DB_NAME="$DB_NAME" -e DB_USER="$DB_USER" -e DB_PASSWORD="$DB_PASS" \
    -e ADMIN_USERNAME="$ADMIN_USER" -e ADMIN_PASSWORD="$ADMIN_PASS" \
    -e SITE_MONITOR_SECRET_KEY="$SECRET_KEY" \
    -e SITE_MONITOR_EMAIL_ENABLED=false \
    -e SPRING_SESSION_STORE_TYPE=jdbc \
    -e STARTUP_CHECK_DELAY_MS=600000 \
    -e SITE_MONITOR_EVAL_STATE_VERIFY_MS="$EVAL_VERIFY_MS" \
    -e POD_NAME="$host" \
    -e JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:MinRAMPercentage=50.0 -Xss512k -XX:+ExitOnOutOfMemoryError" \
    "$IMAGE" >/dev/null
}

wait_ready() {
  local host="$1" port="$(port_of "$1")" i
  for i in $(seq 1 150); do
    if curl -fsS --max-time 3 "http://127.0.0.1:$port/health/readiness" 2>/dev/null | grep -q '"status":"UP"'; then
      ok "$host hazır (~$((i * 2)) sn)"; return 0
    fi
    if [ "$(docker inspect -f '{{.State.Running}}' "$(cname "$host")" 2>/dev/null)" != "true" ]; then
      err "$host açılışta çıktı (çıkış kodu $(docker inspect -f '{{.State.ExitCode}}' "$(cname "$host")" 2>/dev/null))"
      docker logs --tail 80 "$(cname "$host")" 2>&1 | sed 's/^/    /'
      return 1
    fi
    sleep 2
  done
  err "$host 300 sn içinde hazır olmadı"; return 1
}

COOKIE=""
API_STATUS=""
# api <host> <METHOD> <path> [json-body] → stdout: gövde; durum: $API_STATUS — `x="$(api …)"` alt kabukta koşar ve
# $API_STATUS'u ÜST kabuğa taşımaz, o yüzden durum ayrıca dosyaya yazılır: alt kabuktan sonra `api_status` okunur.
api() {
  local host="$1" method="$2" path="$3" body="${4:-}" port="$(port_of "$1")" tmp
  tmp="$WORK/.api.$$"
  if [ -n "$body" ]; then
    API_STATUS="$(curl -sS -o "$tmp" -w '%{http_code}' --max-time 30 -X "$method" -H 'Content-Type: application/json' \
      ${COOKIE:+-H "Cookie: SESSION=$COOKIE"} --data "$body" "http://127.0.0.1:$port$path" 2>/dev/null)"
  else
    API_STATUS="$(curl -sS -o "$tmp" -w '%{http_code}' --max-time 30 -X "$method" \
      ${COOKIE:+-H "Cookie: SESSION=$COOKIE"} "http://127.0.0.1:$port$path" 2>/dev/null)"
  fi
  printf '%s' "${API_STATUS:-000}" > "$WORK/.api-status"
  cat "$tmp" 2>/dev/null; rm -f "$tmp"
}
api_status() { cat "$WORK/.api-status" 2>/dev/null || echo 000; }

# Her pod'un kendi görüşü: Sistem Sağlığı → scheduler.sweep_leader.held_by_me
held_by_me() {
  local body
  body="$(api "$1" GET /api/admin/system)"
  printf '%s' "$body" | grep -o '"sweep_leader":{[^}]*}' | grep -c '"held_by_me":true' || true
}

# ── Kalıcı değerlendirme durumu (çok pod Sürüm 3-1) ───────────────────────────────────────────
# es_read <host> → ES_* değişkenleri: o pod'un Sistem Sağlığı yanıtındaki data.eval_state. JSON'u tek kullanımlık
# PostgreSQL'in json işleçleri ayrıştırır (gövde rastgele etiketli dolar-tırnakla stdin'den gider; komut satırı sınırı yok).
# Dönüş 1 = yanıt okunamadı (HTTP ≠ 200 ya da ayrıştırılamadı).
ES_SQL="SELECT (j #> '{data,eval_state}') IS NOT NULL,
  j #>> '{data,eval_state,this_pod,mode}', j #>> '{data,eval_state,this_pod,active}',
  j #>> '{data,eval_state,this_pod,generation}',
  COALESCE(json_typeof(j #> '{data,eval_state,this_pod,inherited}') = 'object', false),
  j #>> '{data,eval_state,this_pod,inherited,previous_generation}',
  j #>> '{data,eval_state,verify,generation}', j #>> '{data,eval_state,verify,status}',
  j #>> '{data,eval_state,verify,reason}', j #>> '{data,eval_state,verify,pass_fail_mismatches}',
  j #>> '{data,eval_state,verify,stale}', j #>> '{data,eval_state,verify,observed_by_pod}',
  COALESCE(j #>> '{data,eval_state,verify_error}', j #>> '{data,eval_state,this_pod_error}', j #>> '{data,eval_state,error}'),
  j #>> '{data,eval_state,verify,first_mismatches}'"
es_read() {
  local f="$WORK/.es-body" tag row
  ES_PRESENT=""; ES_MODE=""; ES_ACTIVE=""; ES_GEN=""; ES_INH=""; ES_PREV=""; ES_VGEN=""; ES_VSTATUS=""; ES_VREASON=""
  ES_VMIS=""; ES_VSTALE=""; ES_VPOD=""; ES_ERR=""; ES_VFIRST=""
  api "$1" GET /api/admin/system > "$f"
  if [ "$API_STATUS" != "200" ] || [ ! -s "$f" ]; then rm -f "$f"; return 1; fi
  tag="smk$(rand_hex 4)"
  row="$({ printf '%s FROM (SELECT $%s$' "$ES_SQL" "$tag"; cat "$f"; printf '$%s$::json AS j) s;\n' "$tag"; } \
          | psql_pg 2>/dev/null | head -1)"
  rm -f "$f"
  [ -n "$row" ] || return 1
  IFS='|' read -r ES_PRESENT ES_MODE ES_ACTIVE ES_GEN ES_INH ES_PREV ES_VGEN ES_VSTATUS ES_VREASON ES_VMIS ES_VSTALE \
    ES_VPOD ES_ERR ES_VFIRST <<< "$row"
  return 0
}

# await_eval_verify <lider pod> <en küçük kuşak> <önceki kuşak | ""> <etiket>
#   Lider pod'un kaydı yazıyor (active), kuşağı ≥ <en küçük kuşak> ve kümenin son doğrulaması BU kuşakta "ok" + 0 uyuşmazlık
#   olana dek en çok EVAL_WAIT_S bekler. Devirden hemen sonra "unknown / resync" (kuşağın ilk turu) ve "no_generation"
#   beklenir; başka bilinmeyen nedenler not düşülür ve yeniden denenir; "mismatch" hemen hatadır. <önceki kuşak> verilirse
#   this_pod.inherited dolu ve önceki kuşağı saymış olmalı. Sonuç: ES_GEN (bu pod'un kuşağı).
await_eval_verify() {
  local host="$1" min_gen="$2" prev_gen="$3" label="$4" t0 el last="Sistem Sağlığı henüz okunmadı"
  [ "$EVAL_SKIP" = "1" ] && return 0
  t0="$(date +%s)"
  [ -n "$min_gen" ] || min_gen=1
  while :; do
    el=$(( $(date +%s) - t0 ))
    if es_read "$host"; then
      if [ "$ES_PRESENT" != "t" ]; then
        if [ "$EVAL_CHECK" = "1" ]; then err "$label: Sistem Sağlığı yanıtında data.eval_state yok (çok pod Sürüm 3-1 öncesi imaj?)"; return 1; fi
        warn "$label: data.eval_state yok — imaj çok pod Sürüm 3-1 öncesi; kalıcı değerlendirme durumu denetimleri atlandı"
        EVAL_SKIP=1; return 0
      fi
      if [ "$ES_ACTIVE" != "true" ] || [ -z "$ES_GEN" ] || [ "$ES_GEN" -lt "$min_gen" ] 2>/dev/null; then
        last="kayıt yazmıyor ya da kuşak eski (active=${ES_ACTIVE:-?}, kuşak=${ES_GEN:-—}, beklenen ≥ $min_gen, kip=${ES_MODE:-?}${ES_ERR:+, hata $ES_ERR})"
      elif [ "$ES_VGEN" != "$ES_GEN" ]; then
        last="kuşak $ES_GEN için doğrulama henüz yayımlanmadı (son gözlem kuşağı ${ES_VGEN:-—}${ES_ERR:+, hata $ES_ERR})"
      elif [ "$ES_VSTALE" = "true" ]; then
        last="kuşak $ES_GEN doğrulaması eski (stale) — lider doğrulamıyor olabilir"
      else
        case "$ES_VSTATUS" in
          ok)
            if [ "$ES_VMIS" = "0" ]; then
              ok "$label: kuşak $ES_GEN doğrulaması uyuşuyor — 0 uyuşmazlık (doğrulayan ${ES_VPOD:-?}, ~${el} sn)"
              break
            fi
            last="durum ok ama uyuşmazlık sayısı '${ES_VMIS:-?}'" ;;
          mismatch)
            err "$label: kuşak $ES_GEN doğrulamasında ${ES_VMIS:-?} uyuşmazlık — ilk anahtarlar ${ES_VFIRST:-—} (pod günlüğünde 'Kalıcı değerlendirme doğrulaması')"
            return 1 ;;
          unknown)
            case "$ES_VREASON" in
              resync|no_generation) last="bilinmiyor / $ES_VREASON (devirden sonra beklenen — sonraki tur karşılaştırır)" ;;
              *) last="bilinmiyor / ${ES_VREASON:-?}"; echo "  ! $label: doğrulama turu bilinmiyor (${ES_VREASON:-?}) — yeniden denenecek" ;;
            esac ;;
          *) last="doğrulama durumu '${ES_VSTATUS:-?}'" ;;
        esac
      fi
    else
      last="Sistem Sağlığı okunamadı (HTTP ${API_STATUS:-?})"
    fi
    if [ "$el" -ge "$EVAL_WAIT_S" ]; then
      err "$label: ${EVAL_WAIT_S} sn içinde uyuşan bir doğrulama turu görülmedi — son durum: $last"
      return 1
    fi
    sleep 10
  done
  if [ -n "$prev_gen" ]; then
    if [ "$ES_INH" != "t" ]; then
      err "$label: this_pod.inherited yok — yeni kuşak önceki kuşağın satırlarını saymadı (günlükte 'devralınan satırlar sayılamadı'?)"
    elif [ -z "$ES_PREV" ]; then
      err "$label: this_pod.inherited.previous_generation boş — önceki kuşak $prev_gen'in satırları görülmedi"
    elif [ "$ES_PREV" = "$prev_gen" ]; then
      ok "$label: devralınan satırlar sayıldı (önceki kuşak $ES_PREV — karara girmez)"
    else
      warn "$label: devralınan önceki kuşak $ES_PREV (beklenen $prev_gen)"
    fi
  fi
  return 0
}

# ── Devirde okuma (çok pod Sürüm 3-2, yalnız SMOKE_EVAL_READ=1) ──────────────────────────────
# el_read <host> → EL_* değişkenleri: data.eval_state.read_enabled, this_pod.handover_ready / last_handover ve kümenin
# son yükleme gözlemi data.eval_state.load (outcome, reason, from_generation, pod_name, sayılar). Dönüş 1 = okunamadı.
EL_SQL="SELECT j #>> '{data,eval_state,read_enabled}', j #>> '{data,eval_state,this_pod,handover_ready}',
  j #>> '{data,eval_state,this_pod,last_handover}', j #>> '{data,eval_state,load,outcome}',
  j #>> '{data,eval_state,load,reason}', j #>> '{data,eval_state,load,from_generation}',
  j #>> '{data,eval_state,load,pod_name}', j #>> '{data,eval_state,load,recovery_counters}',
  j #>> '{data,eval_state,load,siblings}', j #>> '{data,eval_state,load_error}'"
el_read() {
  local f="$WORK/.el-body" tag row
  EL_READ=""; EL_READY=""; EL_LAST=""; EL_OUT=""; EL_REASON=""; EL_FROM=""; EL_POD=""; EL_REC=""; EL_SIB=""; EL_ERR=""
  api "$1" GET /api/admin/system > "$f"
  if [ "$API_STATUS" != "200" ] || [ ! -s "$f" ]; then rm -f "$f"; return 1; fi
  tag="smk$(rand_hex 4)"
  row="$({ printf '%s FROM (SELECT $%s$' "$EL_SQL" "$tag"; cat "$f"; printf '$%s$::json AS j) s;\n' "$tag"; } \
          | psql_pg 2>/dev/null | head -1)"
  rm -f "$f"
  [ -n "$row" ] || return 1
  IFS='|' read -r EL_READ EL_READY EL_LAST EL_OUT EL_REASON EL_FROM EL_POD EL_REC EL_SIB EL_ERR <<< "$row"
  return 0
}

# enable_eval_read — ayarı Genel Ayarlar uç noktasıyla açar; iki pod ayarı görene (≤ 10 sn ayar yenilemesi) ve lider
# this_pod.handover_ready = true diyene (kuşak eksiksiz) dek en çok EVAL_WAIT_S bekler.
enable_eval_read() {
  local body t0 el last="Sistem Sağlığı henüz okunmadı" a_on="" b_on=""
  body="$(api "$FOLLOWER" PUT /api/admin/general/settings '{"values":{"site.monitor.eval-state.read-enabled":"true"}}')"
  if [ "$(api_status)" = "200" ] && printf '%s' "$body" | grep -q '"success":true'; then
    ok "site.monitor.eval-state.read-enabled = true kaydedildi ($FOLLOWER üzerinden)"
  else
    err "devirde okuma ayarı kaydedilemedi (HTTP $(api_status)): $(printf '%s' "$body" | head -c 300)"; return 1
  fi
  t0="$(date +%s)"
  while :; do
    el=$(( $(date +%s) - t0 ))
    a_on=""; b_on=""
    if el_read "$FOLLOWER"; then
      if [ -z "$EL_READ" ]; then err "Sistem Sağlığı yanıtında data.eval_state.read_enabled yok — imaj çok pod Sürüm 3-2 öncesi"; return 1; fi
      b_on="$EL_READ"
    fi
    if el_read "$LEADER"; then
      a_on="$EL_READ"
      if [ "$a_on" = "true" ] && [ "$b_on" = "true" ] && [ "$EL_READY" = "true" ]; then
        ok "iki pod ayarı görüyor; lider $LEADER devre hazır (this_pod.handover_ready, ~${el} sn)"; return 0
      fi
      last="lider read_enabled=${a_on:-?}, handover_ready=${EL_READY:-?}; takipçi read_enabled=${b_on:-?}"
    else
      last="lider Sistem Sağlığı okunamadı (HTTP ${API_STATUS:-?})"
    fi
    if [ "$el" -ge "$EVAL_WAIT_S" ]; then err "devirde okuma ${EVAL_WAIT_S} sn içinde hazır olmadı — son durum: $last"; return 1; fi
    sleep 5
  done
}

# await_eval_load <host> <beklenen sonuç> <önceki kuşak | ""> <etiket> — kümenin yükleme gözlemi BU pod'un (pod_name) ve
# verilen önceki kuşaktan (from_generation) olana dek bekler; sonuç beklenenden farklıysa hata.
await_eval_load() {
  local host="$1" want="$2" from="$3" label="$4" t0 el last="gözlem henüz yok"
  t0="$(date +%s)"
  while :; do
    el=$(( $(date +%s) - t0 ))
    if el_read "$host"; then
      if [ "$EL_POD" = "$host" ] && [ -n "$EL_OUT" ] && { [ -z "$from" ] || [ "$EL_FROM" = "$from" ]; }; then
        if [ "$EL_OUT" = "$want" ]; then
          ok "$label: devirde yüklenen durum '$EL_OUT'${EL_REASON:+ ($EL_REASON)} — kuşak ${EL_FROM:-?}'den, ${EL_REC:-0} kurtarma sayacı, ${EL_SIB:-0} DOWN kardeş"
          return 0
        fi
        err "$label: devirde yüklenen durum '$EL_OUT'${EL_REASON:+ / $EL_REASON} (beklenen '$want') — RUNBOOK 'Kalıcı değerlendirme durumunu devirde okuma' → İzlenecekler"
        return 1
      fi
      last="gözlem pod=${EL_POD:-—}, kuşak=${EL_FROM:-—}, sonuç=${EL_OUT:-—}${EL_ERR:+, hata $EL_ERR}"
    else
      last="Sistem Sağlığı okunamadı (HTTP ${API_STATUS:-?})"
    fi
    if [ "$el" -ge "$EVAL_WAIT_S" ]; then err "$label: ${EVAL_WAIT_S} sn içinde $host'un yükleme gözlemi görülmedi — son: $last"; return 1; fi
    sleep 5
  done
}

# handover_marked <eski lider> — eski liderin (kapanmış kapsayıcı) günlüğünde devir işareti satırı var mı.
handover_marked() {
  if docker logs "$(cname "$1")" 2>&1 | grep -q "Devir öncesi kalıcı durum boşaltıldı ve işaretlendi"; then
    ok "$1 kirayı bırakmadan önce devir işaretini yazdı (günlükte 'Devir öncesi kalıcı durum boşaltıldı ve işaretlendi')"
  else
    err "$1 devir işaretini yazmadı — günlükte 'Devir öncesi kalıcı durum …' satırı: $(docker logs "$(cname "$1")" 2>&1 | grep 'Devir öncesi kalıcı durum' | tail -1)"
  fi
}

# term_and_handover <lider> <takipçi> — lidere SIGTERM; HANDOVER_S (devir sn) / STOPPED_S (kapanış sn), olmadıysa boş.
term_and_handover() {
  local from="$1" to="$2" t0 el i
  HANDOVER_S=""; STOPPED_S=""
  t0="$(date +%s)"
  docker kill --signal TERM "$(cname "$from")" >/dev/null
  while :; do
    el=$(( $(date +%s) - t0 ))
    if [ -z "$HANDOVER_S" ] && [ "$(lease_holder)" = "$to" ]; then HANDOVER_S=$el; ok "devir: $to ${el} sn sonra kirayı aldı"; fi
    if [ -z "$STOPPED_S" ] && [ "$(docker inspect -f '{{.State.Running}}' "$(cname "$from")" 2>/dev/null)" != "true" ]; then
      STOPPED_S=$el; ok "$from ${el} sn içinde kapandı (çıkış kodu $(docker inspect -f '{{.State.ExitCode}}' "$(cname "$from")" 2>/dev/null))"
    fi
    [ -n "$HANDOVER_S" ] && [ -n "$STOPPED_S" ] && break
    if [ "$el" -ge "$GRACE_STOP_S" ]; then
      [ -z "$STOPPED_S" ] && { err "$from ${GRACE_STOP_S} sn'de kapanmadı (Helm grace'te SIGKILL yerdi) — zorla durduruluyor"; docker kill "$(cname "$from")" >/dev/null 2>&1; STOPPED_S=$el; }
      break
    fi
    sleep 1
  done
  if [ -z "$HANDOVER_S" ]; then
    for i in $(seq 1 200); do [ "$(lease_holder)" = "$to" ] && { HANDOVER_S=$(( $(date +%s) - t0 )); break; }; sleep 1; done
  fi
  if [ -z "$HANDOVER_S" ]; then err "$to kirayı hiç almadı"
  elif [ "$HANDOVER_S" -le "$HANDOVER_MAX_S" ]; then ok "devir süresi ${HANDOVER_S} sn ≤ ${HANDOVER_MAX_S} sn"
  else err "devir ${HANDOVER_S} sn sürdü (> ${HANDOVER_MAX_S} sn) — kira kapanışın başında (ContextClosedEvent) bırakılmıyor mu?"; fi
  if [ -n "$STOPPED_S" ] && [ "$STOPPED_S" -le "$SHUTDOWN_MAX_S" ]; then ok "kapanış ${STOPPED_S} sn ≤ ${SHUTDOWN_MAX_S} sn (grace 120 − preStop 10)"
  else err "kapanış ${STOPPED_S:-?} sn > ${SHUTDOWN_MAX_S} sn"; fi
}

# ── Dağıtık kontroller (çok pod Sürüm 3-3, yalnız SMOKE_DISTRIBUTED=1) ─────────────────────────
DIST_KEY="site.monitor.checks.distributed.enabled"
DIST_SUMMARY=""
# db_now_ms → veritabanı saati (ms); monitor_check_jobs / monitor_check_workers zamanları bu saatle yazılır.
db_now_ms() { psql_pg -c "SELECT (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint" 2>/dev/null | head -1; }
# jobs_seq → monitor_check_jobs kimlik dizisinin son değeri: boşaltılıp silinen satırlarla da yalnız artar (hiç iş yoksa 0).
jobs_seq() {
  psql_pg -c "SELECT COALESCE(pg_sequence_last_value(pg_get_serial_sequence('monitor_check_jobs', 'id')::regclass), 0)" \
    2>/dev/null | head -1
}
# dist_set <host> <true|false> — ana anahtarı Genel Ayarlar uç noktasıyla yazar (o pod hemen, öteki ≤ 10 sn ayar yenilemesiyle).
dist_set() {
  local body
  body="$(api "$1" PUT /api/admin/general/settings "{\"values\":{\"$DIST_KEY\":\"$2\"}}")"
  if [ "$(api_status)" = "200" ] && printf '%s' "$body" | grep -q '"success":true'; then
    ok "$DIST_KEY = $2 kaydedildi ($1 üzerinden)"; return 0
  fi
  err "dağıtık kontrol anahtarı yazılamadı (HTTP $(api_status)) — imaj çok pod Sürüm 3-3 öncesi mi?: $(printf '%s' "$body" | head -c 300)"
  return 1
}
# worker_live <host> → "<canlı süreç sayısı>|<en büyük completed>": nabzı ≤ 30 sn, durmayan, instance_id <host>-… işçi satırları.
worker_live() {
  psql_pg -c "SELECT COUNT(*), COALESCE(MAX(completed), 0) FROM monitor_check_workers
              WHERE instance_id LIKE '$1-%' AND COALESCE(stopping, false) = false
                AND heartbeat_ms >= (EXTRACT(EPOCH FROM clock_timestamp()) * 1000) - 30000" 2>/dev/null | head -1
}
# await_dist_workers <lider> <takipçi> <başlangıç (date +%s)> <etiket> — iki pod'un işçisi canlı ve takipçi iş tamamlamış olana
# dek en çok DIST_WORKERS_WAIT_S bekler (başlangıçtan sayılır). Liderin tamamlaması 0 ise uyarı: lider kendi payıyla
# (leader-share-pct, 16 yuvada 1) ancak takipçiyle yarışarak iş alır; küçük kuyrukta 0 kalabilir.
await_dist_workers() {
  local lead="$1" fol="$2" t0="$3" label="$4" el wl wf
  while :; do
    el=$(( $(date +%s) - t0 ))
    wl="$(worker_live "$lead")"; wf="$(worker_live "$fol")"
    if [ "${wl%%|*}" -ge 1 ] 2>/dev/null && [ "${wf%%|*}" -ge 1 ] 2>/dev/null && [ "${wf#*|}" -gt 0 ] 2>/dev/null; then
      ok "$label: iki pod'un işçisi canlı — takipçi $fol ${wf#*|} iş, lider $lead ${wl#*|} iş tamamladı (~${el} sn)"
      [ "${wl#*|}" -gt 0 ] 2>/dev/null \
        || warn "$label: lider $lead henüz iş tamamlamadı (lider payı küçük kuyrukta 0 kalabilir; takipçi tamamladı)"
      return 0
    fi
    if [ "$el" -ge "$DIST_WORKERS_WAIT_S" ]; then
      err "$label: ${DIST_WORKERS_WAIT_S} sn içinde iki canlı işçi + takipçide tamamlanan iş görülmedi — lider ${wl:-?}, takipçi ${wf:-?} (canlı süreç|completed), iş dizisi $(jobs_seq)"
      return 1
    fi
    sleep 10
  done
}
# dist_kill_worker <kurban> <kalan> — kurban bir iş sahiplenene dek (≤ 240 sn) bekler, SIGKILL; o işler kira dolunca
# (≤ DIST_RERUN_MAX_S) kurbandan çıkmalı (başka pod sahiplendi / bitirdi / biçildi) ve ölen pod ölümünden sonra iş bitirmez.
DIST_RERUN_S=""
dist_kill_worker() {
  local victim="$1" survivor="$2" ids="" i t0 el left n kill_ms
  DIST_RERUN_S=""
  for i in $(seq 1 120); do
    ids="$(psql_pg -c "SELECT STRING_AGG(id::text, ',' ORDER BY id) FROM monitor_check_jobs
                       WHERE state = 'CLAIMED' AND claimed_by LIKE '$victim-%'" 2>/dev/null | head -1)"
    [ -n "$ids" ] && break
    sleep 2
  done
  [ -n "$ids" ] || warn "6e: $victim 240 sn içinde hiç iş sahiplenmedi — SIGKILL yine de denenir, yeniden koşma ölçülemez"
  kill_ms="$(db_now_ms)"
  t0="$(date +%s)"
  docker kill --signal KILL "$(cname "$victim")" >/dev/null 2>&1 || { err "$victim öldürülemedi"; return 1; }
  ok "$victim SIGKILL ile öldürüldü — sahiplendiği işler: ${ids:-—}"
  [ -n "$ids" ] || return 0
  while :; do
    el=$(( $(date +%s) - t0 ))
    left="$(psql_pg -c "SELECT COUNT(*) FROM monitor_check_jobs WHERE id IN ($ids) AND state = 'CLAIMED'
                        AND claimed_by LIKE '$victim-%'" 2>/dev/null | head -1)"
    if [ "${left:-x}" = "0" ]; then DIST_RERUN_S=$el; break; fi
    [ "$el" -ge "$DIST_RERUN_MAX_S" ] && break
    sleep 5
  done
  if [ -z "$DIST_RERUN_S" ]; then
    err "6e: $victim'in işleri ${DIST_RERUN_MAX_S} sn içinde ondan çıkmadı (kira 150 sn): $(psql_pg -c "SELECT id, state, claimed_by, attempts FROM monitor_check_jobs WHERE id IN ($ids)" 2>/dev/null | tr '\n' ' ')"
  else
    ok "6e: $victim'in işleri ${DIST_RERUN_S} sn sonra ondan çıktı (kira dolunca $survivor sahiplendi / bitirdi / biçti):"
    psql_pg -c "SELECT id, job_type, monitor_id, state, COALESCE(claimed_by, worker, '—'), attempts FROM monitor_check_jobs
                WHERE id IN ($ids) ORDER BY id" 2>/dev/null | sed 's/^/    /'
  fi
  [ -n "$kill_ms" ] || { warn "6e: veritabanı saati okunamadı — geç tamamlama denetimi atlandı"; return 0; }
  n="$(psql_pg -c "SELECT COUNT(*) FROM monitor_check_jobs WHERE worker LIKE '$victim-%' AND finished_ms > $kill_ms" 2>/dev/null | head -1)"
  [ "${n:-x}" = "0" ] && ok "6e: ölen $victim ölümünden sonra iş bitirmedi (geç tamamlama yok)" \
    || err "6e: ölen $victim'in ölümünden sonra bitmiş ${n:-?} işi var"
}
# dist_drained <önce (db ms)> <etiket> — bu andan ÖNCE biten DONE satırı kalmayana dek (yeni liderin boşaltıcısı) en çok
# DIST_DRAIN_MAX_S bekler.
dist_drained() {
  local before="$1" label="$2" t0 el n
  t0="$(date +%s)"
  while :; do
    el=$(( $(date +%s) - t0 ))
    n="$(psql_pg -c "SELECT COUNT(*) FROM monitor_check_jobs WHERE state = 'DONE' AND finished_ms < $before" 2>/dev/null | head -1)"
    if [ "${n:-x}" = "0" ]; then ok "$label: devirden önce biten sonuçlar boşaltıldı (kalan 0, ~${el} sn)"; return 0; fi
    if [ "$el" -ge "$DIST_DRAIN_MAX_S" ]; then
      err "$label: devirden önce biten ${n:-?} DONE satırı ${DIST_DRAIN_MAX_S} sn içinde boşaltılmadı — yeni liderin boşaltıcısı koşmuyor mu?"
      return 1
    fi
    sleep 5
  done
}
# dist_off — anahtarı kira sahibinde kapatır; DIST_OFF_GRACE_S sonra iş dizisi DIST_OFF_WATCH_S boyunca artmamalı (yeni iş yok)
# ve klasik taramalar satır yazmalı. Kalan kuyruk satırları bilgi olarak yazılır (kapalıyken boşaltılmaz, yeniden açılışta atılır).
dist_off() {
  local lead off_ms since seq1 seq2 rows
  lead="$(lease_holder)"
  case "$lead" in pod-a|pod-b) ;; *) err "8e: geçerli kira sahibi yok ('${lead:-—}') — kapatma adımı koşmadı"; return 1 ;; esac
  dist_set "$lead" false || return 1
  off_ms="$(db_now_ms)"
  sleep "$DIST_OFF_GRACE_S"
  seq1="$(jobs_seq)"
  since="$(psql_pg -c "SELECT to_char(to_timestamp(${off_ms:-0} / 1000.0 + $DIST_OFF_GRACE_S) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS')" 2>/dev/null | head -1)"
  sleep "$DIST_OFF_WATCH_S"
  seq2="$(jobs_seq)"
  rows="$(psql_pg -c "SELECT (SELECT COUNT(*) FROM port_checks WHERE checked_at > '$since') + (SELECT COUNT(*) FROM http_checks WHERE checked_at > '$since')" 2>/dev/null | head -1)"
  if [ -n "$seq1" ] && [ "$seq1" = "$seq2" ]; then
    ok "8e: kapatmadan ${DIST_OFF_GRACE_S} sn sonra ${DIST_OFF_WATCH_S} sn boyunca yeni iş gönderilmedi (iş dizisi $seq1)"
  else
    err "8e: anahtar kapandıktan sonra yeni iş gönderildi (iş dizisi ${seq1:-?} → ${seq2:-?})"
  fi
  [ "${rows:-0}" -gt 0 ] 2>/dev/null && ok "8e: klasik taramalar sürüyor ($since UTC sonrası $rows kontrol satırı)" \
    || err "8e: kapatmadan sonra hiç kontrol satırı yok — klasik yol devralmadı"
  echo "  kalan kuyruk (bilgi — kapalıyken boşaltılmaz, yeniden açılışta atılır): $(psql_pg -c "SELECT state || '=' || COUNT(*) FROM monitor_check_jobs GROUP BY state ORDER BY state" 2>/dev/null | tr '\n' ' ')"
}

echo "Çok pod duman testi — imaj $IMAGE, iş dizini $WORK"

# ── 1. Ağ + PostgreSQL ────────────────────────────────────────────────────────────────────────
step "1. Ağ ve tek kullanımlık PostgreSQL"
docker network create "$NET" >/dev/null || { echo "::error::docker ağı kurulamadı"; exit 1; }
pg_aliases=(--network-alias pg)
# Dağıtık kontrol koşusunun yavaş izlemeleri için ek adlar (aynı host:port ikinci kez izlenemez — ad farklı olmalı).
if [ "$DIST" = "1" ]; then for i in $(seq 1 10); do pg_aliases+=(--network-alias "pg-slow$i"); done; fi
docker run -d --name "$PG" --network "$NET" "${pg_aliases[@]}" \
  -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER="$DB_USER" -e POSTGRES_PASSWORD="$DB_PASS" "$PG_IMAGE" >/dev/null \
  || { echo "::error::PostgreSQL kapsayıcısı açılamadı"; exit 1; }
for i in $(seq 1 60); do
  docker exec "$PG" pg_isready -q -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1 && { ok "PostgreSQL hazır"; break; }
  sleep 2
  [ "$i" -eq 60 ] && { echo "::error::PostgreSQL 120 sn içinde hazır olmadı"; exit 1; }
done

# ── 2. pod-a, sonra pod-b (rollout'taki gibi sırayla) ────────────────────────────────────────
step "2. Uygulama kapsayıcıları (prod profili, JDBC oturum, salt-okunur kök FS)"
start_pod pod-a || { echo "::error::pod-a açılamadı"; exit 1; }
wait_ready pod-a || exit 1
start_pod pod-b || { echo "::error::pod-b açılamadı"; exit 1; }
wait_ready pod-b || exit 1

# ── 3. Oturum A'da açılır, B'de geçerli ───────────────────────────────────────────────────────
step "3. Paylaşılan oturum (A'da giriş, B'de aynı çerez)"
hdr="$WORK/.login-headers"
code="$(curl -sS -D "$hdr" -o /dev/null -w '%{http_code}' --max-time 30 -H 'Content-Type: application/json' \
  --data "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" "http://127.0.0.1:$PORT_A/api/login" 2>/dev/null)"
COOKIE="$(tr -d '\r' < "$hdr" | sed -n 's/^[Ss]et-[Cc]ookie: SESSION=\([^;]*\).*/\1/p' | head -1)"
rm -f "$hdr"
if [ "$code" != "200" ] || [ -z "$COOKIE" ]; then
  err "A'da giriş başarısız (HTTP $code, SESSION çerezi ${COOKIE:+var}${COOKIE:-yok}) — JDBC oturum deposu açık mı?"
  exit 1
fi
ok "A'da giriş 200, SESSION çerezi alındı (JDBC oturum deposu)"
me_b="$(api pod-b GET /api/me)"
if [ "$(api_status)" = "200" ] && printf '%s' "$me_b" | grep -q "$ADMIN_USER"; then
  ok "B /api/me aynı çerezle 200 ($ADMIN_USER)"
else
  err "B aynı çerezi tanımadı (HTTP $(api_status)) — oturumlar pod'lar arasında paylaşılmıyor"
fi
api pod-a GET /api/me >/dev/null
[ "$API_STATUS" = "200" ] && ok "A /api/me 200 (B'deki istek oturumu düşürmedi)" || err "B'deki istekten sonra A /api/me HTTP $API_STATUS"

# ── 4. Tek lider ──────────────────────────────────────────────────────────────────────────────
step "4. Tarama liderliği kirası tek pod'da"
LEADER=""
for i in $(seq 1 60); do LEADER="$(lease_holder)"; [ -n "$LEADER" ] && break; sleep 2; done
case "$LEADER" in
  pod-a|pod-b) ok "kira sahibi: $LEADER ($(lease_info))" ;;
  "") err "120 sn içinde geçerli bir kira oluşmadı"; exit 1 ;;
  *)  err "kira beklenmeyen bir sahipte: $LEADER"; exit 1 ;;
esac
FOLLOWER=pod-b; [ "$LEADER" = "pod-b" ] && FOLLOWER=pod-a
views=$(( $(held_by_me pod-a) + $(held_by_me pod-b) ))
[ "$views" -eq 1 ] && ok "pod'ların kendi görüşü: tam 1 pod held_by_me=true" || err "held_by_me=true diyen pod sayısı $views (1 olmalı)"

# ── 5. Taslak hedefler (takipçi pod'un uçları + PostgreSQL portu) ─────────────────────────────
step "5. Taslak izlemeler (takım 'General', 60 sn aralık)"
TEAM_ID="$(psql_pg -c "SELECT id FROM teams WHERE name = 'General' ORDER BY id LIMIT 1" 2>/dev/null | head -1)"
[ -n "$TEAM_ID" ] || { err "bootstrap takımı 'General' bulunamadı"; exit 1; }
create() {   # create <tür> <json>
  local body; body="$(api "$FOLLOWER" POST "/api/monitoring/$1" "$2")"
  if [ "$(api_status)" = "200" ] && printf '%s' "$body" | grep -q '"success":true'; then ok "$1 izlemesi: $(printf '%s' "$2" | sed -n 's/.*"name":"\([^"]*\)".*/\1/p')"
  else err "$1 izlemesi oluşturulamadı (HTTP $(api_status)): $(printf '%s' "$body" | head -c 300)"; fi
}
common="\"groupName\":\"smoke\",\"tags\":\"smoke\",\"teamId\":$TEAM_ID,\"intervalSeconds\":60"
create port "{\"name\":\"smoke-postgres\",\"host\":\"pg\",\"port\":5432,$common}"
create port "{\"name\":\"smoke-follower-port\",\"host\":\"$FOLLOWER\",\"port\":8080,$common}"
create http "{\"name\":\"smoke-follower-health\",\"url\":\"http://$FOLLOWER:8080/health\",$common}"
create http "{\"name\":\"smoke-follower-branding\",\"url\":\"http://$FOLLOWER:8080/api/branding\",$common}"
DIST_T0=""
if [ "$DIST" = "1" ]; then
  # ~30 sn süren, hep düşen kontroller (PostgreSQL bağlantıyı açık tutar, banner göndermez → okuma zaman aşımı): SIGKILL
  # anında takipçinin elinde sahiplenilmiş bir iş olsun. Aynı dönemde gönderilen on işin en çok sekizini lider kendi payıyla
  # (16 yuvanın yarısı) alabilir — takipçiye her dönem iş kalır. Teyitsiz (0) — doğrulama yeniden kontrolleri dönem
  # satırlarına karışmaz.
  for i in $(seq 1 10); do
    create port "{\"name\":\"smoke-slow-banner-$i\",\"host\":\"pg-slow$i\",\"port\":5432,\"protocol\":\"BANNER\",\"expect\":\"SMOKE-NEVER\",\"timeoutMs\":30000,\"confirmAttempts\":0,$common}"
  done
  step "5b. Dağıtık kontroller açılır ($DIST_KEY = true, tüm türler) — lider $LEADER üzerinden"
  DIST_T0="$(date +%s)"
  dist_set "$LEADER" true || DIST=0
fi

# ── 6. Taramalar ──────────────────────────────────────────────────────────────────────────────
step "6. Taramalar ${SWEEP_S} sn (lider el değiştirmemeli)"
flaps=0; double=0; t=0
while [ "$t" -lt "$SWEEP_S" ]; do
  sleep 30; t=$((t + 30))
  cur="$(lease_holder)"
  if [ "$cur" != "$LEADER" ]; then flaps=$((flaps + 1)); echo "  ! ${t}s: kira sahibi '$cur' (beklenen $LEADER)"; fi
  v=$(( $(held_by_me pod-a) + $(held_by_me pod-b) ))
  [ "$v" -gt 1 ] && { double=$((double + 1)); echo "  ! ${t}s: $v pod kendini lider sanıyor"; }
  [ $((t % 120)) -eq 0 ] && echo "  ${t}s: lider $cur, kontrol satırı port=$(psql_pg -c 'SELECT COUNT(*) FROM port_checks') http=$(psql_pg -c 'SELECT COUNT(*) FROM http_checks')${DIST_T0:+, iş dizisi $(jobs_seq)}"
done
[ "$flaps" -eq 0 ] && ok "kira $SWEEP_S sn boyunca $LEADER'da kaldı" || err "kira sabit durumda $flaps kez el değiştirdi"
[ "$double" -eq 0 ] && ok "hiçbir anda iki pod kendini lider saymadı" || err "$double ölçümde iki pod kendini lider saydı"
rows="$(psql_pg -c 'SELECT (SELECT COUNT(*) FROM port_checks) + (SELECT COUNT(*) FROM http_checks)')"
[ "${rows:-0}" -gt 0 ] && ok "taramalar kontrol satırı yazdı ($rows)" || err "hiç kontrol satırı yazılmadı (taramalar koşmadı)"

# ── 6b. Kalıcı değerlendirme durumu — ilk lider (çok pod Sürüm 3-1) ───────────────────────────
GEN1=""; GEN2=""; GEN3=""
if [ "$EVAL_SKIP" != "1" ]; then
  step "6b. Kalıcı değerlendirme durumu — ilk lider ($LEADER): kayıt yazıyor, doğrulama 0 uyuşmazlık (≤ ${EVAL_WAIT_S} sn)"
  await_eval_verify "$LEADER" 1 "" "ilk lider $LEADER" && [ "$EVAL_SKIP" != "1" ] && GEN1="$ES_GEN"
fi

# ── 6c. Devirde okuma ayarı (çok pod Sürüm 3-2, SMOKE_EVAL_READ=1) ───────────────────────────
LOAD_OUTS=""
if [ "$EVAL_READ" = "1" ]; then
  step "6c. Devirde okuma ayarı açılır (site.monitor.eval-state.read-enabled = true) — iki pod görür, lider devre hazır"
  if [ "$EVAL_SKIP" = "1" ]; then err "SMOKE_EVAL_READ=1: data.eval_state yok — imaj çok pod Sürüm 3-2 öncesi"; else enable_eval_read; fi
fi

# ── 6d / 6e. Dağıtık kontroller: işçiler, takipçiye SIGKILL (çok pod Sürüm 3-3, SMOKE_DISTRIBUTED=1) ────────────
if [ "$DIST" = "1" ]; then
  step "6d. Dağıtık kontroller — iki pod'un işçisi canlı, takipçi iş tamamladı (açılıştan ≤ ${DIST_WORKERS_WAIT_S} sn)"
  s0="$(jobs_seq)"
  [ "${s0:-0}" -gt 0 ] 2>/dev/null && ok "kuyruğa iş gönderildi (iş dizisi $s0)" \
    || err "hiç iş gönderilmedi (iş dizisi ${s0:-?}) — dağıtık kip etkinleşmedi mi? (Sistem Sağlığı → Küme 'Dağıtık kontroller')"
  await_dist_workers "$LEADER" "$FOLLOWER" "$DIST_T0" "6d"
  step "6e. Takipçiye ($FOLLOWER) SIGKILL — sahiplendiği işler kira dolunca (≤ ${DIST_RERUN_MAX_S} sn) başka pod'a geçer"
  dist_kill_worker "$FOLLOWER" "$LEADER"
  if docker start "$(cname "$FOLLOWER")" >/dev/null 2>&1 && wait_ready "$FOLLOWER"; then
    await_dist_workers "$LEADER" "$FOLLOWER" "$(date +%s)" "6e yeniden açılış"
  else
    err "$FOLLOWER SIGKILL'den sonra yeniden açılamadı"
  fi
  DIST_SUMMARY="işçiler tamam, SIGKILL sonrası işler ${DIST_RERUN_S:-?} sn'de geçti"
fi

# ── 7. Lidere SIGTERM → devir ─────────────────────────────────────────────────────────────────
step "7. Lidere ($LEADER) SIGTERM — devir ≤ ${HANDOVER_MAX_S} sn, kapanış ≤ ${SHUTDOWN_MAX_S} sn"
t0_iso="$(iso_utc)"
dist_t7=""; [ "$DIST" = "1" ] && dist_t7="$(db_now_ms)"
term_and_handover "$LEADER" "$FOLLOWER"
handover="$HANDOVER_S"; stopped="$STOPPED_S"
[ "$EVAL_READ" = "1" ] && [ "$EVAL_SKIP" != "1" ] && handover_marked "$LEADER"

# ── 8. Yeni lider taramaya devam eder ─────────────────────────────────────────────────────────
step "8. Yeni lider ($FOLLOWER) ${POST_HANDOVER_S} sn tarar"
sleep "$POST_HANDOVER_S"
after="$(psql_pg -c "SELECT (SELECT COUNT(*) FROM port_checks WHERE checked_at > '$t0_iso') + (SELECT COUNT(*) FROM http_checks WHERE checked_at > '$t0_iso')")"
[ "${after:-0}" -gt 0 ] && ok "devirden sonra $after kontrol satırı" || err "devirden sonra hiç kontrol satırı yok — yeni lider taramıyor"
[ "$(lease_holder)" = "$FOLLOWER" ] && ok "kira hâlâ $FOLLOWER'da" || err "kira devirden sonra $FOLLOWER'da kalmadı: $(lease_info)"
[ -n "$dist_t7" ] && dist_drained "$dist_t7" "devirden sonra $FOLLOWER"

# ── 8b. Kalıcı değerlendirme durumu — devirden sonra yeni lider ───────────────────────────────
if [ "$EVAL_SKIP" != "1" ]; then
  step "8b. Kalıcı değerlendirme durumu — yeni lider ($FOLLOWER): yeni kuşak, doğrulama 0 uyuşmazlık, devralınan satırlar"
  await_eval_verify "$FOLLOWER" $(( ${GEN1:-0} + 1 )) "$GEN1" "devirden sonra $FOLLOWER" && [ "$EVAL_SKIP" != "1" ] && GEN2="$ES_GEN"
  if [ "$EVAL_READ" = "1" ] && [ "$EVAL_SKIP" != "1" ]; then
    await_eval_load "$FOLLOWER" full "$GEN1" "devirden sonra $FOLLOWER" && LOAD_OUTS="full"
  fi
fi

# ── 8c. Geri devir: eski lider yeniden açılır, şimdiki lidere SIGTERM ─────────────────────────
flip_back=""
if [ "$FLIP_BACK" = "1" ]; then
  step "8c. Geri devir — $LEADER yeniden açılır, $FOLLOWER'a SIGTERM, kira $LEADER'a döner; sonra $FOLLOWER da yeniden açılır"
  if docker start "$(cname "$LEADER")" >/dev/null 2>&1 && wait_ready "$LEADER"; then
    dist_t8=""; [ "$DIST" = "1" ] && dist_t8="$(db_now_ms)"
    term_and_handover "$FOLLOWER" "$LEADER"
    flip_back="$HANDOVER_S"
    [ -n "$dist_t8" ] && dist_drained "$dist_t8" "geri devirden sonra $LEADER"
    # Taslak izlemeler $FOLLOWER'ın uçlarını hedefler: pod geri gelsin, izlemeler düzelsin, küme yine iki pod'la bitsin.
    if docker start "$(cname "$FOLLOWER")" >/dev/null 2>&1 && wait_ready "$FOLLOWER"; then
      views=$(( $(held_by_me pod-a) + $(held_by_me pod-b) ))
      [ "$views" -eq 1 ] && ok "geri devirden sonra tam 1 pod held_by_me=true" || err "geri devirden sonra held_by_me=true diyen pod sayısı $views (1 olmalı)"
    else
      err "$FOLLOWER yeniden açılamadı"
    fi
    if [ "$EVAL_SKIP" != "1" ]; then
      await_eval_verify "$LEADER" $(( ${GEN2:-0} + 1 )) "$GEN2" "geri devirden sonra $LEADER" && [ "$EVAL_SKIP" != "1" ] && GEN3="$ES_GEN"
      if [ "$EVAL_READ" = "1" ] && [ "$EVAL_SKIP" != "1" ]; then
        handover_marked "$FOLLOWER"
        await_eval_load "$LEADER" full "$GEN2" "geri devirden sonra $LEADER" && LOAD_OUTS="${LOAD_OUTS:+$LOAD_OUTS → }full"
      fi
    fi
  else
    err "$LEADER yeniden açılamadı — geri devir denenmedi"
  fi
fi

# ── 8d. Çökme: lidere SIGKILL → yalnız gözlemler (çok pod Sürüm 3-2, SMOKE_EVAL_READ=1 + SMOKE_EVAL_KILL=1) ──
if [ "$EVAL_READ" = "1" ] && [ "$EVAL_KILL" = "1" ] && [ "$EVAL_SKIP" != "1" ]; then
  CUR="$(lease_holder)"; OTHER=""
  case "$CUR" in pod-a) OTHER=pod-b ;; pod-b) OTHER=pod-a ;; esac
  if [ -z "$OTHER" ]; then
    err "8d: geçerli kira sahibi yok ('${CUR:-—}') — SIGKILL adımı koşmadı"
  else
    step "8d. Çökme — lidere ($CUR) SIGKILL: devir işareti yok; kira süresi dolunca $OTHER devralır, YALNIZ GÖZLEMLERİ yükler (≤ ${KILL_TAKEOVER_MAX_S} sn)"
    if [ "$(docker inspect -f '{{.State.Running}}' "$(cname "$OTHER")" 2>/dev/null)" != "true" ]; then
      { docker start "$(cname "$OTHER")" >/dev/null 2>&1 && wait_ready "$OTHER"; } || err "$OTHER açılamadı"
    fi
    KGEN=""
    es_read "$CUR" && KGEN="$ES_GEN"
    [ -n "$KGEN" ] || warn "8d: $CUR'un kuşağı okunamadı — yükleme gözlemi kuşak denetimsiz beklenecek"
    t0="$(date +%s)"; took=""
    docker kill --signal KILL "$(cname "$CUR")" >/dev/null 2>&1 || err "$CUR öldürülemedi"
    while :; do
      el=$(( $(date +%s) - t0 ))
      if [ "$(lease_holder)" = "$OTHER" ]; then took=$el; break; fi
      [ "$el" -ge "$KILL_TAKEOVER_MAX_S" ] && break
      sleep 5
    done
    if [ -z "$took" ]; then
      err "SIGKILL'den sonra $OTHER ${KILL_TAKEOVER_MAX_S} sn içinde kirayı almadı"
    else
      ok "SIGKILL'den sonra $OTHER ${took} sn sonra kirayı aldı (kira süresi doldu)"
      await_eval_load "$OTHER" observations "$KGEN" "SIGKILL sonrası $OTHER" && LOAD_OUTS="${LOAD_OUTS:+$LOAD_OUTS → }observations"
      await_eval_verify "$OTHER" $(( ${KGEN:-0} + 1 )) "$KGEN" "SIGKILL sonrası $OTHER" || true
    fi
    { docker start "$(cname "$CUR")" >/dev/null 2>&1 && wait_ready "$CUR"; } || warn "$CUR SIGKILL'den sonra yeniden açılamadı (dedektör yine koşar)"
  fi
fi

# ── 8e. Dağıtık kontroller kapatılır → klasik (çok pod Sürüm 3-3, SMOKE_DISTRIBUTED=1) ──────────
if [ "$DIST" = "1" ]; then
  step "8e. Ana anahtar kapatılır — ${DIST_OFF_GRACE_S} sn sonra yeni iş yok, klasik taramalar sürer"
  for h in pod-a pod-b; do
    if [ "$(docker inspect -f '{{.State.Running}}' "$(cname "$h")" 2>/dev/null)" != "true" ]; then
      { docker start "$(cname "$h")" >/dev/null 2>&1 && wait_ready "$h"; } || warn "8e: $h açılamadı"
    fi
  done
  dist_off && DIST_SUMMARY="${DIST_SUMMARY:+$DIST_SUMMARY, }kapatma → klasik"
fi

# ── 9. Çift-iş dedektörü ──────────────────────────────────────────────────────────────────────
step "9. Çift-iş dedektörü (scripts/multipod-dup-check.sql)"
dup_out="$WORK/multipod-dup-check.txt"
dup_vars=()
# Dağıtık koşu: dönem çiftleri (bölüm 9b) anahtar kapatılmış olsa da sayılır — taslak izlemelerde "Şimdi kontrol et" yok.
[ "$DIST" = "1" ] && dup_vars=(-v period_strict=1)
docker exec -i "$PG" psql -X -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=0 ${dup_vars[@]+"${dup_vars[@]}"} < "$DUP_SQL" > "$dup_out" 2>&1
judge_dup_output "$dup_out"

# ── 10. Günlükler ─────────────────────────────────────────────────────────────────────────────
step "10. Günlüklerde ERROR satırları"
for h in pod-a pod-b; do
  docker logs "$(cname "$h")" > "$WORK/$h.log" 2>&1 || true
  n="$(grep -cE '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9:.]+ +ERROR ' "$WORK/$h.log" || true)"
  if [ "${n:-0}" -eq 0 ]; then ok "$h: ERROR satırı yok"
  else
    msg="$h: $n ERROR satırı ($WORK/$h.log)"
    if [ "$STRICT_LOGS" = "1" ]; then err "$msg"; else warn "$msg"; fi
    grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9:.]+ +ERROR ' "$WORK/$h.log" | head -10 | sed 's/^/    /'
  fi
done

echo
if [ "$fail" -ne 0 ]; then echo "Çok pod duman testi BAŞARISIZ."; exit 1; fi
echo "Çok pod duman testi: tamam (lider $LEADER → $FOLLOWER, devir ${handover} sn, kapanış ${stopped} sn${flip_back:+; geri devir $FOLLOWER → $LEADER ${flip_back} sn}${GEN1:+; kalıcı durum kuşakları $GEN1${GEN2:+ → $GEN2}${GEN3:+ → $GEN3}, doğrulama 0 uyuşmazlık}${LOAD_OUTS:+; devirde yüklenen: $LOAD_OUTS}${DIST_SUMMARY:+; dağıtık kontroller: $DIST_SUMMARY})."
