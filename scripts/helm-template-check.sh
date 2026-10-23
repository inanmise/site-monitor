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
#      (bildirim havuzu boşaltma 20 + web 20 + yürütücü 20 + @Scheduled bekleme 20 + ortak yok etme bütçesi 10 (ShutdownDeadline) = 90 sn —
#      backend application.properties + WebConfig.NOTIFY_AWAIT_SECONDS; Java tarafını ShutdownBudgetGateTest denetler).
#   3. Birden çok pod koşabilen her render'da (replicas > 1 ya da HPA) oturum deposu jdbc.
#   4. Koruma: replicaCount=2 + sessionStoreType=memory ve autoscaling + none → render BAŞARISIZ olur;
#      replicaCount=1 + memory → render olur (tek pod geri dönüşü serbest).
#   5. preStopSleepSeconds=0 → lifecycle bloğu yok; preStopSleepSeconds ≥ grace → render BAŞARISIZ.
#   6. Staging (release.yaml): sabit 2 replika, HPA yok. Prod (master.yaml): bu sürümde 1 replika.
#   7. Yerleşim kancaları: nodeSelector / affinity / tolerations / matchLabelKeys verilince render edilir,
#      verilmezse hiçbiri render edilmez (varsayılan çıktı değişmez).
#   Çok pod sürüm 2b (2026-10-23):
#   8. HPA: açıkken Deployment'ta replicas YOK, varsayılan yalnız CPU metriği (bellek yok), behavior var; bellek hedefi
#      verilince bellek metriği; hiç hedef yok / min > max → render BAŞARISIZ.
#   9. PDB: tek pod + minAvailable 1 (ve HPA minReplicas 1) → BAŞARISIZ; minAvailable + maxUnavailable birlikte →
#      BAŞARISIZ; maxUnavailable 0 ve minAvailable "100%" → BAŞARISIZ; maxUnavailable tek başına render olur.
#  10. priorityClassName ve bölge (zone) yayılımı yalnız verilince render edilir; staging pod-template-hash taşır.
#  11. OTel ajanı: @Scheduled açılımı AÇIK kalır (varsayılan ortamda OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED yok —
#      kapalıyken tikin her SQL deyimi ayrı kök iz olur), ajan ortamı değişmedi, agent.extraEnv yine ezer.
#  12. ephemeral-storage request / limit ve /var/log emptyDir sizeLimit her ortamda; /tmp sizeLimit yalnız verilince.
#  13. DB_POOL_MIN (varsayılan 5; > dbPoolMax ya da sayı değil → BAŞARISIZ) ve config.extraEnv render edilir;
#      ConfigMap / container env / Secret anahtarıyla çakışan ya da geçersiz adlı extraEnv → BAŞARISIZ.
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
# Çok pod sürüm 2b yardımcıları: tek belge (kind), yayılım kısıtları bloğu, container env değeri, birim, ConfigMap değeri
doc_of()     { awk -v k="$1" '$0 == "kind: " k { d = 1 } d && /^---/ { exit } d' <<<"$2"; }
spread_of()  { awk '/^      topologySpreadConstraints:/ { d = 1; next } d && /^      [a-z]/ { exit } d' <<<"$1"; }
env_value()  { awk -v n="$2" '$0 ~ ("- name: " n "$") { getline; sub(/^ *value: /, ""); print; exit }' <<<"$1"; }
volume_of()  { awk -v n="$1" '$0 ~ ("^        - name: " n "$") { d = 1; next } d && (/^        - name:/ || /^      [a-z]/) { exit } d && !/^ *#/' <<<"$2"; }
cm_value()   { sed -n "s/^  $2: \"\(.*\)\"\$/\1/p" <<<"$1" | head -1; }

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
# Uygulama değeri kırpıp küçük harfe çevirir (SessionStoreAutoConfigurationFilter) — koruma da öyle okumalı.
out="$(render --set replicaCount=2 --set config.sessionStoreType=' JDBC ')"
[ $? -eq 0 ] && ok "2 replika + ' JDBC ' (büyük harf / boşluk) render olur" || err "2 replika + ' JDBC ' reddedildi (uygulama bunu jdbc okur): $out"

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

# ── 8: HPA ──────────────────────────────────────────────────────────────────────────────────
echo "── HPA (çok pod sürüm 2b)"
for o in "$default_out" "$master_out" "$release_out"; do
  if has_hpa "$o"; then err "bir ortamda HPA render edildi (her ortamda kapalı kalmalı)"; fi
done
ok "hiçbir ortamda HPA yok"
hpa_out="$(render --set autoscaling.enabled=true)"
if [ $? -ne 0 ]; then err "autoscaling.enabled=true render olmadı: $hpa_out"; else
  r="$(replicas_of "$hpa_out")"
  if [ -z "$r" ]; then ok "HPA açıkken Deployment'ta replicas yok (sayıyı HPA tutar)"
  else err "HPA açıkken Deployment replicas: $r render edildi (her helm upgrade HPA'nın sayısını sıfırlar)"; fi
  hpa_doc="$(doc_of HorizontalPodAutoscaler "$hpa_out")"
  grep -q 'name: cpu' <<<"$hpa_doc" && ok "HPA CPU metriği var" || err "HPA CPU metriği render edilmedi"
  if grep -q 'name: memory' <<<"$hpa_doc"; then err "HPA varsayılanında bellek metriği var (JVM yığını küçülmez — HPA tavana kilitlenir)"
  else ok "HPA varsayılanında bellek metriği yok"; fi
  if grep -q 'stabilizationWindowSeconds: 600' <<<"$hpa_doc" && grep -q 'periodSeconds: 120' <<<"$hpa_doc" && grep -q 'periodSeconds: 300' <<<"$hpa_doc"; then
    ok "HPA behavior (küçültme: 600 s pencere, 1 pod / 300 s; büyütme: 1 pod / 120 s)"
  else err "HPA behavior render edilmedi: $hpa_doc"; fi
fi
out="$(render --set autoscaling.enabled=true --set autoscaling.targetMemoryUtilizationPercentage=80)"
grep -q 'name: memory' <<<"$out" && ok "bellek hedefi verilince bellek metriği render edilir" || err "bellek hedefi verildi ama bellek metriği yok: $out"
expect_fail "HPA + hiç metrik hedefi yok" "needs at least one metric target" \
  --set autoscaling.enabled=true --set autoscaling.targetCPUUtilizationPercentage=
expect_fail "HPA minReplicas > maxReplicas" "must not be larger than autoscaling.maxReplicas" \
  --set autoscaling.enabled=true --set autoscaling.minReplicas=6

# ── 9: PDB ──────────────────────────────────────────────────────────────────────────────────
echo "── PDB (çok pod sürüm 2b)"
expect_fail "tek pod + PDB minAvailable 1" "must be below replicaCount" \
  --set podDisruptionBudget.enabled=true
expect_fail "HPA minReplicas 1 + PDB minAvailable 1" "must be below autoscaling.minReplicas" \
  --set autoscaling.enabled=true --set replicaCount=3 --set podDisruptionBudget.enabled=true
expect_fail "PDB minAvailable + maxUnavailable birlikte" "not both" \
  --set replicaCount=2 --set podDisruptionBudget.enabled=true --set podDisruptionBudget.maxUnavailable=1
expect_fail "PDB maxUnavailable 0" "never allows a voluntary eviction" \
  --set replicaCount=2 --set podDisruptionBudget.enabled=true --set podDisruptionBudget.minAvailable=null --set podDisruptionBudget.maxUnavailable=0
expect_fail "PDB minAvailable 100%" "never allows a voluntary eviction" \
  --set replicaCount=3 --set podDisruptionBudget.enabled=true --set podDisruptionBudget.minAvailable=100%
out="$(render --set replicaCount=2 --set podDisruptionBudget.enabled=true --set podDisruptionBudget.minAvailable=null --set podDisruptionBudget.maxUnavailable=1)"
if [ $? -ne 0 ]; then err "PDB maxUnavailable 1 render olmadı: $out"; else
  pdb_doc="$(doc_of PodDisruptionBudget "$out")"
  if grep -q '^  maxUnavailable: 1$' <<<"$pdb_doc" && ! grep -q 'minAvailable' <<<"$pdb_doc"; then ok "PDB maxUnavailable tek başına render edilir"
  else err "PDB maxUnavailable render edilmedi: $pdb_doc"; fi
fi
pdb_doc="$(doc_of PodDisruptionBudget "$release_out")"
grep -q '^  minAvailable: 1$' <<<"$pdb_doc" && ok "staging PDB minAvailable 1 (2 replikanın biri boşaltılabilir)" || err "staging PDB beklenen minAvailable 1 değil: $pdb_doc"

# ── 10: priorityClassName, bölge yayılımı, staging pod-template-hash ───────────────────────
echo "── yerleşim (çok pod sürüm 2b)"
if grep -q 'priorityClassName:' <<<"$default_out"; then err "varsayılan render'da priorityClassName var"; else ok "varsayılan render'da priorityClassName yok"; fi
out="$(render --set priorityClassName=sitemonitor-high)"
grep -q '^      priorityClassName: "sitemonitor-high"$' <<<"$out" && ok "priorityClassName verilince render edilir" || err "priorityClassName render edilmedi"
spread="$(spread_of "$release_out")"
grep -q -- '- pod-template-hash' <<<"$spread" && ok "staging yayılımı pod-template-hash ile (yalnız aynı ReplicaSet'in podları sayılır)" \
  || err "staging topologySpreadConstraints matchLabelKeys: [pod-template-hash] render etmiyor: $spread"
if grep -q 'topology.kubernetes.io/zone' <<<"$default_out$release_out$master_out"; then err "bölge yayılımı varsayılan olarak render edildi"; else ok "bölge yayılımı varsayılan olarak yok"; fi
spread="$(spread_of "$(render --set topologySpreadConstraints.zone.enabled=true)")"
if grep -q 'topologyKey: topology.kubernetes.io/zone' <<<"$spread" && grep -q 'whenUnsatisfiable: ScheduleAnyway' <<<"$spread" \
   && ! grep -q 'kubernetes.io/hostname' <<<"$spread"; then ok "bölge yayılımı tek başına açılır (ScheduleAnyway)"
else err "bölge yayılımı render edilmedi: $spread"; fi
spread="$(spread_of "$(render -f "$CHART/environments/release.yaml" --set topologySpreadConstraints.zone.enabled=true)")"
n="$(grep -c 'topologyKey:' <<<"$spread")"
[ "$n" = "2" ] && ok "staging + bölge → iki yayılım kısıtı" || err "staging + bölge: $n yayılım kısıtı (beklenen 2)"

# ── 11: OTel ajanı ortamı ───────────────────────────────────────────────────────────────────
echo "── OTel ajanı (çok pod sürüm 2b)"
out="$(render --set config.otel.agent.enabled=true)"
if grep -q 'OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED' <<<"$out"; then
  err "ajan ortamı @Scheduled açılımını kapatıyor (kapalıyken JDBC deyimlerinin her biri ayrı KÖK iz olur — tik izi gruplamalı)"
else ok "ajan: @Scheduled açılımı varsayılanda açık (tik başına bir iz)"; fi
names="$(awk '/^          env:/ { d = 1; next } d && /^          envFrom:/ { exit } d && /- name: OTEL_|- name: MANAGEMENT_/ { printf "%s ", $3 }' <<<"$out")"
expected_names="MANAGEMENT_OPENTELEMETRY_MAP_ENVIRONMENT_VARIABLES OTEL_INSTRUMENTATION_COMMON_LOGGING_SPAN_ID_KEY OTEL_INSTRUMENTATION_COMMON_LOGGING_TRACE_ID_KEY OTEL_INSTRUMENTATION_HTTP_URL_CONNECTION_ENABLED OTEL_INSTRUMENTATION_JAVA_HTTP_CLIENT_ENABLED OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS OTEL_METRICS_EXPORTER OTEL_SERVICE_NAME "
[ "$names" = "$expected_names" ] && ok "ajan ortamı 20.166.0 ile aynı (8 değişken)" || err "ajan ortamı değişti: $names"
out="$(render --set config.otel.agent.enabled=true --set-string config.otel.agent.extraEnv.OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED=false)"
v="$(env_value "$out" OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED)"
[ "$v" = '"false"' ] && ok "ajan: agent.extraEnv ek değişken yazar" || err "agent.extraEnv render edilmedi (değer: '$v')"
if grep -q 'OTEL_INSTRUMENTATION' <<<"$default_out"; then err "ajan kapalıyken OTEL ortamı render edildi"; else ok "ajan kapalıyken OTEL ortamı yok"; fi

# ── 12: geçici disk ─────────────────────────────────────────────────────────────────────────
echo "── geçici disk (çok pod sürüm 2b)"
for name in values.yaml master release; do
  case "$name" in values.yaml) o="$default_out" ;; master) o="$master_out" ;; release) o="$release_out" ;; esac
  res="$(awk '/^          resources:/ { d = 1; next } d && /^          [a-z]/ { exit } d' <<<"$o")"
  [ "$(grep -c 'ephemeral-storage:' <<<"$res")" = "2" ] && ok "$name: ephemeral-storage request + limit" || err "$name: resources'ta ephemeral-storage request / limit yok: $res"
  vl="$(volume_of varlog "$o")"
  grep -q 'sizeLimit: "2Gi"' <<<"$vl" && ok "$name: /var/log emptyDir sizeLimit 2Gi" || err "$name: varlog emptyDir sizeLimit yok: $vl"
done
if grep -q 'sizeLimit' <<<"$(volume_of tmp "$default_out")"; then err "/tmp varsayılanında sizeLimit var (k6 koşusunda pod tahliye edilebilir)"; else ok "/tmp varsayılanında sizeLimit yok"; fi
grep -q 'sizeLimit: "1Gi"' <<<"$(volume_of tmp "$(render --set storage.tmpSizeLimit=1Gi)")" \
  && ok "storage.tmpSizeLimit verilince /tmp sizeLimit render edilir" || err "storage.tmpSizeLimit render edilmedi"

# ── 13: DB_POOL_MIN ve config.extraEnv ─────────────────────────────────────────────────────
echo "── DB_POOL_MIN ve config.extraEnv (çok pod sürüm 2b)"
for name in values.yaml master release; do
  case "$name" in values.yaml) o="$default_out" ;; master) o="$master_out" ;; release) o="$release_out" ;; esac
  v="$(cm_value "$o" DB_POOL_MIN)"
  [ "$v" = "5" ] && ok "$name: DB_POOL_MIN 5" || err "$name: DB_POOL_MIN '$v' (beklenen 5)"
done
[ "$(cm_value "$(render --set config.dbPoolMin=0)" DB_POOL_MIN)" = "0" ] && ok "config.dbPoolMin=0 geçerli (boşta bağlantı yok)" || err "config.dbPoolMin=0 render edilmedi"
expect_fail "config.dbPoolMin > dbPoolMax" "must not be larger than config.dbPoolMax" --set config.dbPoolMin=26
expect_fail "config.dbPoolMin sayı değil" "must be a whole number" --set config.dbPoolMin=five
out="$(render --set-string config.extraEnv.TOMCAT_MAX_THREADS=200 --set config.extraEnv.SITE_MONITOR_TRACING_DEFER_TO_AGENT=false)"
if [ $? -ne 0 ]; then err "config.extraEnv render olmadı: $out"; else
  if [ "$(cm_value "$out" TOMCAT_MAX_THREADS)" = "200" ] && [ "$(cm_value "$out" SITE_MONITOR_TRACING_DEFER_TO_AGENT)" = "false" ]; then
    ok "config.extraEnv ConfigMap'e yazılır"
  else err "config.extraEnv ConfigMap'te yok"; fi
fi
if grep -q 'config.extraEnv (values)' <<<"$default_out"; then err "boş config.extraEnv bir şey render etti"; else ok "boş config.extraEnv render edilmez"; fi
expect_fail "config.extraEnv ConfigMap anahtarı (DB_POOL_MAX)" "already sets DB_POOL_MAX in the ConfigMap" --set config.extraEnv.DB_POOL_MAX=9
expect_fail "config.extraEnv geçersiz ad" "is not an environment variable name" --set 'config.extraEnv.bad-name=1'
expect_fail "config.extraEnv ajan ortamı (ajan açık)" "use config.otel.agent.extraEnv" \
  --set config.otel.agent.enabled=true --set config.extraEnv.OTEL_METRICS_EXPORTER=otlp
# Deployment container env adlarının ve chart Secret anahtarlarının HEPSİ reddedilmeli (site-monitor.reservedEnvNames
# elle tutulan bir liste — buradaki tarama şablonla sapmasını yakalar).
secret_out="$(render --set secret.secretKeyPrevious=x --set secret.metricsExportToken=y)"
names="$(awk '/^          env:/ { d = 1; next } d && /^          envFrom:/ { exit } d && /- name: / { print $3 }' <<<"$default_out")
$(doc_of Secret "$secret_out" | awk '/^stringData:/ { s = 1; next } s && /^  [A-Z0-9_]+:/ { sub(/:.*/, ""); sub(/^ +/, ""); print }')"
cnt=0
for n in $names; do
  cnt=$((cnt + 1))
  if render --set "config.extraEnv.$n=x" >/dev/null 2>&1; then err "config.extraEnv.$n render OLDU (container env / Secret anahtarı reddedilmeli)"; fi
done
[ "$cnt" -ge 19 ] && ok "config.extraEnv: $cnt container env / Secret adının hepsi reddedilir" || err "container env / Secret adları okunamadı (yalnız $cnt)"

if [ "$fail" -ne 0 ]; then
  echo "Helm şablon denetimi BAŞARISIZ."
  exit 1
fi
echo "Helm şablon denetimi: tamam."
