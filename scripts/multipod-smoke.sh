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
#     5. scripts/multipod-dup-check.sql → DUP_TOTAL = 0 ve STUCK_TOTAL = 0 (çift kontrol / bildirim / push / açık
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
#   uyarıdır; 1: hata).
# Gerekenler: docker, curl, bash. Yerel makinede koşulabilir (Git Bash dahil); prod/staging kümesine dokunmaz.
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
api() {   # api <host> <METHOD> <path> [json-body] → stdout: gövde, $API_STATUS
  local host="$1" method="$2" path="$3" body="${4:-}" port="$(port_of "$1")" tmp
  tmp="$WORK/.api.$$"
  if [ -n "$body" ]; then
    API_STATUS="$(curl -sS -o "$tmp" -w '%{http_code}' --max-time 30 -X "$method" -H 'Content-Type: application/json' \
      ${COOKIE:+-H "Cookie: SESSION=$COOKIE"} --data "$body" "http://127.0.0.1:$port$path" 2>/dev/null)"
  else
    API_STATUS="$(curl -sS -o "$tmp" -w '%{http_code}' --max-time 30 -X "$method" \
      ${COOKIE:+-H "Cookie: SESSION=$COOKIE"} "http://127.0.0.1:$port$path" 2>/dev/null)"
  fi
  cat "$tmp" 2>/dev/null; rm -f "$tmp"
}

# Her pod'un kendi görüşü: Sistem Sağlığı → scheduler.sweep_leader.held_by_me
held_by_me() {
  local body
  body="$(api "$1" GET /api/admin/system)"
  printf '%s' "$body" | grep -o '"sweep_leader":{[^}]*}' | grep -c '"held_by_me":true' || true
}

echo "Çok pod duman testi — imaj $IMAGE, iş dizini $WORK"

# ── 1. Ağ + PostgreSQL ────────────────────────────────────────────────────────────────────────
step "1. Ağ ve tek kullanımlık PostgreSQL"
docker network create "$NET" >/dev/null || { echo "::error::docker ağı kurulamadı"; exit 1; }
docker run -d --name "$PG" --network "$NET" --network-alias pg \
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
if [ "$API_STATUS" = "200" ] && printf '%s' "$me_b" | grep -q "$ADMIN_USER"; then
  ok "B /api/me aynı çerezle 200 ($ADMIN_USER)"
else
  err "B aynı çerezi tanımadı (HTTP $API_STATUS) — oturumlar pod'lar arasında paylaşılmıyor"
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
  if [ "$API_STATUS" = "200" ] && printf '%s' "$body" | grep -q '"success":true'; then ok "$1 izlemesi: $(printf '%s' "$2" | sed -n 's/.*"name":"\([^"]*\)".*/\1/p')"
  else err "$1 izlemesi oluşturulamadı (HTTP $API_STATUS): $(printf '%s' "$body" | head -c 300)"; fi
}
common="\"groupName\":\"smoke\",\"tags\":\"smoke\",\"teamId\":$TEAM_ID,\"intervalSeconds\":60"
create port "{\"name\":\"smoke-postgres\",\"host\":\"pg\",\"port\":5432,$common}"
create port "{\"name\":\"smoke-follower-port\",\"host\":\"$FOLLOWER\",\"port\":8080,$common}"
create http "{\"name\":\"smoke-follower-health\",\"url\":\"http://$FOLLOWER:8080/health\",$common}"
create http "{\"name\":\"smoke-follower-branding\",\"url\":\"http://$FOLLOWER:8080/api/branding\",$common}"

# ── 6. Taramalar ──────────────────────────────────────────────────────────────────────────────
step "6. Taramalar ${SWEEP_S} sn (lider el değiştirmemeli)"
flaps=0; double=0; t=0
while [ "$t" -lt "$SWEEP_S" ]; do
  sleep 30; t=$((t + 30))
  cur="$(lease_holder)"
  if [ "$cur" != "$LEADER" ]; then flaps=$((flaps + 1)); echo "  ! ${t}s: kira sahibi '$cur' (beklenen $LEADER)"; fi
  v=$(( $(held_by_me pod-a) + $(held_by_me pod-b) ))
  [ "$v" -gt 1 ] && { double=$((double + 1)); echo "  ! ${t}s: $v pod kendini lider sanıyor"; }
  [ $((t % 120)) -eq 0 ] && echo "  ${t}s: lider $cur, kontrol satırı port=$(psql_pg -c 'SELECT COUNT(*) FROM port_checks') http=$(psql_pg -c 'SELECT COUNT(*) FROM http_checks')"
done
[ "$flaps" -eq 0 ] && ok "kira $SWEEP_S sn boyunca $LEADER'da kaldı" || err "kira sabit durumda $flaps kez el değiştirdi"
[ "$double" -eq 0 ] && ok "hiçbir anda iki pod kendini lider saymadı" || err "$double ölçümde iki pod kendini lider saydı"
rows="$(psql_pg -c 'SELECT (SELECT COUNT(*) FROM port_checks) + (SELECT COUNT(*) FROM http_checks)')"
[ "${rows:-0}" -gt 0 ] && ok "taramalar kontrol satırı yazdı ($rows)" || err "hiç kontrol satırı yazılmadı (taramalar koşmadı)"

# ── 7. Lidere SIGTERM → devir ─────────────────────────────────────────────────────────────────
step "7. Lidere ($LEADER) SIGTERM — devir ≤ ${HANDOVER_MAX_S} sn, kapanış ≤ ${SHUTDOWN_MAX_S} sn"
t0="$(date +%s)"; t0_iso="$(iso_utc)"
docker kill --signal TERM "$(cname "$LEADER")" >/dev/null
handover=""; stopped=""
while :; do
  el=$(( $(date +%s) - t0 ))
  if [ -z "$handover" ] && [ "$(lease_holder)" = "$FOLLOWER" ]; then handover=$el; ok "devir: $FOLLOWER ${el} sn sonra kirayı aldı"; fi
  if [ -z "$stopped" ] && [ "$(docker inspect -f '{{.State.Running}}' "$(cname "$LEADER")" 2>/dev/null)" != "true" ]; then
    stopped=$el; ok "$LEADER ${el} sn içinde kapandı (çıkış kodu $(docker inspect -f '{{.State.ExitCode}}' "$(cname "$LEADER")" 2>/dev/null))"
  fi
  [ -n "$handover" ] && [ -n "$stopped" ] && break
  if [ "$el" -ge "$GRACE_STOP_S" ]; then
    [ -z "$stopped" ] && { err "$LEADER ${GRACE_STOP_S} sn'de kapanmadı (Helm grace'te SIGKILL yerdi) — zorla durduruluyor"; docker kill "$(cname "$LEADER")" >/dev/null 2>&1; stopped=$el; }
    break
  fi
  sleep 1
done
if [ -z "$handover" ]; then
  for i in $(seq 1 200); do [ "$(lease_holder)" = "$FOLLOWER" ] && { handover=$(( $(date +%s) - t0 )); break; }; sleep 1; done
fi
if [ -z "$handover" ]; then err "$FOLLOWER kirayı hiç almadı"
elif [ "$handover" -le "$HANDOVER_MAX_S" ]; then ok "devir süresi ${handover} sn ≤ ${HANDOVER_MAX_S} sn"
else err "devir ${handover} sn sürdü (> ${HANDOVER_MAX_S} sn) — kira kapanışın başında (ContextClosedEvent) bırakılmıyor mu?"; fi
[ -n "$stopped" ] && [ "$stopped" -le "$SHUTDOWN_MAX_S" ] && ok "kapanış ${stopped} sn ≤ ${SHUTDOWN_MAX_S} sn (grace 120 − preStop 10)" \
  || err "kapanış ${stopped:-?} sn > ${SHUTDOWN_MAX_S} sn"

# ── 8. Yeni lider taramaya devam eder ─────────────────────────────────────────────────────────
step "8. Yeni lider ($FOLLOWER) ${POST_HANDOVER_S} sn tarar"
sleep "$POST_HANDOVER_S"
after="$(psql_pg -c "SELECT (SELECT COUNT(*) FROM port_checks WHERE checked_at > '$t0_iso') + (SELECT COUNT(*) FROM http_checks WHERE checked_at > '$t0_iso')")"
[ "${after:-0}" -gt 0 ] && ok "devirden sonra $after kontrol satırı" || err "devirden sonra hiç kontrol satırı yok — yeni lider taramıyor"
[ "$(lease_holder)" = "$FOLLOWER" ] && ok "kira hâlâ $FOLLOWER'da" || err "kira devirden sonra $FOLLOWER'da kalmadı: $(lease_info)"

# ── 9. Çift-iş dedektörü ──────────────────────────────────────────────────────────────────────
step "9. Çift-iş dedektörü (scripts/multipod-dup-check.sql)"
dup_out="$WORK/multipod-dup-check.txt"
docker exec -i "$PG" psql -X -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=0 < "$DUP_SQL" > "$dup_out" 2>&1
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
echo "Çok pod duman testi: tamam (lider $LEADER → $FOLLOWER, devir ${handover} sn, kapanış ${stopped} sn)."
