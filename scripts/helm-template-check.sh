#!/usr/bin/env bash
# Helm şablon denetimi (2026-10-21, çok pod sürüm 1) — .github/workflows/ci.yml "helm-lint" işi koşar.
# `helm lint` yalnız sözdizimine bakar; bu betik RENDER EDİLEN çıktıyı ve chart'ın kendi korumalarını denetler.
# Kümeye bağlanmaz, hiçbir şey kurmaz (yalnız `helm template`).
#
# Kullanım:  bash scripts/helm-template-check.sh            (depo kökünden ya da herhangi bir yerden)
#            HELM=/yol/helm bash scripts/helm-template-check.sh
#
# Denetimler (biri bile tutmazsa çıkış kodu 1, her biri ::error:: satırı):
#   1. Üç ortam (master / develop / release) + yalın values.yaml render olur.
#   2. Her render'da terminationGracePeriodSeconds ve preStop "sleep N" var; grace − preStop ≥ SPRING_BUDGET_S
#      (bildirim havuzu boşaltma 20 + web 20 + yürütücü 20 + @Scheduled bekleme 20 + @PreDestroy 10 = 90 sn —
#      backend application.properties + WebConfig.NOTIFY_AWAIT_SECONDS; Java tarafını ShutdownBudgetGateTest denetler).
#   3. Birden çok pod koşabilen her render'da (replicas > 1 ya da HPA) oturum deposu jdbc.
#   4. Koruma: replicaCount=2 + sessionStoreType=memory ve autoscaling + none → render BAŞARISIZ olur;
#      replicaCount=1 + memory → render olur (tek pod geri dönüşü serbest).
#   5. preStopSleepSeconds=0 → lifecycle bloğu yok; preStopSleepSeconds ≥ grace → render BAŞARISIZ.
#   6. Staging (release.yaml): sabit 2 replika, HPA yok. Prod (master.yaml): bu sürümde 1 replika.
#   7. Yerleşim kancaları: nodeSelector / affinity / tolerations / matchLabelKeys verilince render edilir,
#      verilmezse hiçbiri render edilmez (varsayılan çıktı değişmez).
set -uo pipefail

HELM="${HELM:-helm}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART="$ROOT/helm/site-monitor"
VALUES="$CHART/values.yaml"
SPRING_BUDGET_S=90
fail=0
err() { echo "::error::$*"; fail=1; }
ok()  { echo "  ok: $*"; }

command -v "$HELM" >/dev/null 2>&1 || { echo "::error::helm bulunamadı (HELM=$HELM)"; exit 1; }

# render <adı> <ek helm argümanları…> → stdout: manifest; dönüş kodu helm'inki
render() { "$HELM" template rel "$CHART" --namespace ns -f "$VALUES" "$@" 2>&1; }

# Deployment'tan tek bir alan (ilk eşleşme): grace, preStop sleep, replicas
grace_of()   { awk '/^      terminationGracePeriodSeconds:/ { print $2; exit }' <<<"$1"; }
prestop_of() { sed -n 's/.*command: \["sh", "-c", "sleep \([0-9][0-9]*\)"\].*/\1/p' <<<"$1" | head -1; }
replicas_of(){ awk '/^kind: Deployment/ { d = 1 } d && /^  replicas:/ { print $2; exit }' <<<"$1"; }
has_hpa()    { grep -q '^kind: HorizontalPodAutoscaler' <<<"$1"; }
session_of() { sed -n 's/^  SPRING_SESSION_STORE_TYPE: "\(.*\)"/\1/p' <<<"$1" | head -1; }

check_env() {
  local name="$1"; shift
  local out rc
  out="$(render "$@")"; rc=$?
  echo "── $name"
  if [ $rc -ne 0 ]; then err "$name: helm template başarısız: $out"; return; fi
  local grace prestop replicas session
  grace="$(grace_of "$out")"; prestop="$(prestop_of "$out")"; replicas="$(replicas_of "$out")"; session="$(session_of "$out")"
  [ -n "$grace" ]   || err "$name: terminationGracePeriodSeconds render edilmedi"
  [ -n "$prestop" ] || err "$name: preStop 'sleep N' render edilmedi"
  if [ -n "$grace" ] && [ -n "$prestop" ]; then
    if [ $((grace - prestop)) -ge $SPRING_BUDGET_S ]; then
      ok "grace ${grace}s, preStop ${prestop}s → uygulamaya $((grace - prestop))s (≥ ${SPRING_BUDGET_S}s)"
    else
      err "$name: grace ${grace}s − preStop ${prestop}s = $((grace - prestop))s < ${SPRING_BUDGET_S}s (Spring kapanış bütçesi): pod @PreDestroy'dan önce SIGKILL yer, tarama liderliği kirası bırakılmaz"
    fi
  fi
  local multi=0
  if [ -n "$replicas" ] && [ "$replicas" -gt 1 ]; then multi=1; fi
  if has_hpa "$out"; then multi=1; fi
  if [ $multi -eq 1 ]; then
    [ "$session" = "jdbc" ] && ok "çok pod (replicas=${replicas:-HPA}) → oturum deposu jdbc" \
      || err "$name: birden çok pod koşabilir ama SPRING_SESSION_STORE_TYPE='$session' (jdbc olmalı)"
  else
    ok "tek pod (replicas=${replicas:-?}), oturum deposu '${session}'"
  fi
  LAST_OUT="$out"
}

expect_fail() {
  local name="$1" needle="$2"; shift 2
  local out rc
  out="$(render "$@")"; rc=$?
  if [ $rc -eq 0 ]; then
    err "koruma çalışmadı: '$name' render OLDU (başarısız olmalıydı)"
  elif grep -q -- "$needle" <<<"$out"; then
    ok "koruma: $name → reddedildi"
  else
    err "koruma: '$name' başka bir nedenle düştü (beklenen ileti: '$needle'): $out"
  fi
}

echo "Helm: $("$HELM" version --short 2>/dev/null || "$HELM" version)"

# ── 1-3: ortamlar ───────────────────────────────────────────────────────────────────────────
LAST_OUT=""
check_env "values.yaml (yalın)"
default_out="$LAST_OUT"
check_env "master (prod)"   -f "$CHART/environments/master.yaml"
master_out="$LAST_OUT"
check_env "develop"         -f "$CHART/environments/develop.yaml"
check_env "release (staging)" -f "$CHART/environments/release.yaml"
release_out="$LAST_OUT"

# ── 6: ortam beklentileri ───────────────────────────────────────────────────────────────────
echo "── ortam beklentileri"
r="$(replicas_of "$release_out")"
[ "$r" = "2" ] && ok "staging sabit 2 replika" || err "staging replicas '$r' (beklenen 2 — çok pod staging'de doğrulanır)"
if has_hpa "$release_out"; then err "staging'de HPA render edildi (sürüm 1'de kapalı olmalı: bellek metriği HPA'yı tavana kilitler)"; else ok "staging'de HPA yok"; fi
r="$(replicas_of "$master_out")"
[ "$r" = "1" ] && ok "prod bu sürümde 1 replika (2'ye geçiş ayrı helm upgrade)" \
  || echo "  bilgi: prod replicas=$r — 1 → 2 geçişi ayrı bir sürüm/commit olmalı (docs/PROD_DEPLOY_CHECKLIST.md 'Çok pod')"

# ── 4: oturum deposu koruması ───────────────────────────────────────────────────────────────
echo "── koruma: bellek oturumu + çok pod"
expect_fail "replicaCount=2 + sessionStoreType=memory" "config.sessionStoreType is" \
  --set replicaCount=2 --set config.sessionStoreType=memory
expect_fail "autoscaling.enabled + sessionStoreType=none" "config.sessionStoreType is" \
  --set autoscaling.enabled=true --set config.sessionStoreType=none
expect_fail "release.yaml + sessionStoreType=memory" "config.sessionStoreType is" \
  -f "$CHART/environments/release.yaml" --set config.sessionStoreType=memory
out="$(render --set replicaCount=1 --set config.sessionStoreType=memory)"
[ $? -eq 0 ] && ok "tek pod + memory render olur" || err "tek pod + memory render olmadı: $out"

# ── 5: preStop sınırları ───────────────────────────────────────────────────────────────────
echo "── preStop sınırları"
out="$(render --set preStopSleepSeconds=0)"
if [ $? -ne 0 ]; then err "preStopSleepSeconds=0 render olmadı: $out"
elif grep -q 'preStop:' <<<"$out"; then err "preStopSleepSeconds=0 iken preStop render edildi"
else ok "preStopSleepSeconds=0 → preStop yok"; fi
expect_fail "preStopSleepSeconds ≥ terminationGracePeriodSeconds" "must be well below terminationGracePeriodSeconds" \
  --set preStopSleepSeconds=120
expect_fail "preStopSleepSeconds < 0" "must be 0 (no preStop hook) or more" \
  --set preStopSleepSeconds=-1

# ── 7: yerleşim kancaları ───────────────────────────────────────────────────────────────────
echo "── yerleşim kancaları"
for k in 'nodeSelector:' 'affinity:' 'tolerations:' 'matchLabelKeys:'; do
  if grep -q "^      ${k}\|^          ${k}" <<<"$default_out"; then err "varsayılan render'da '$k' var (boşken render edilmemeli)"; fi
done
ok "varsayılan render'da nodeSelector / affinity / tolerations / matchLabelKeys yok"
out="$(render --set nodeSelector.sitemonitor-egress=ok --set 'tolerations[0].key=dedicated' --set 'tolerations[0].operator=Exists' \
  --set topologySpreadConstraints.enabled=true --set 'topologySpreadConstraints.matchLabelKeys[0]=pod-template-hash' \
  --set topologySpreadConstraints.whenUnsatisfiable=ScheduleAnyway)"
if [ $? -ne 0 ]; then err "yerleşim kancaları render olmadı: $out"; else
  grep -q '^      nodeSelector:' <<<"$out" && grep -q 'sitemonitor-egress: ok' <<<"$out" || err "nodeSelector render edilmedi"
  grep -q '^      tolerations:' <<<"$out" || err "tolerations render edilmedi"
  grep -q 'whenUnsatisfiable: ScheduleAnyway' <<<"$out" || err "whenUnsatisfiable değeri uygulanmadı"
  grep -q -- '- pod-template-hash' <<<"$out" || err "matchLabelKeys render edilmedi"
  ok "nodeSelector / tolerations / whenUnsatisfiable / matchLabelKeys verilince render edilir"
fi

if [ "$fail" -ne 0 ]; then
  echo "Helm şablon denetimi BAŞARISIZ."
  exit 1
fi
echo "Helm şablon denetimi: tamam."
