#!/usr/bin/env bash
# Node başına çıkış (egress) ve saat denetimi (2026-10-21, çok pod sürüm 1 — ön koşul P1 + P2).
#
# Neden: birden çok pod'da BÜTÜN izlemeyi tarama liderliği kirasını tutan pod koşar ve kira her rollout'ta node
# değiştirir. Vekil ACL'i, firewall izin listeleri, SMTP aktarıcısı, LDAP / AD, push ağ geçidi ya da RDAP node IP'sine
# göre açılmışsa ilk devirde toplu "kesinti" alarmı ya da sessiz posta kaybı yaşanır. Bu betik, uygulamanın KENDİ
# imajıyla ve KENDİ ConfigMap'iyle (vekil, SMTP) her node'a sabitlenmiş tek kullanımlık bir pod açar, hedeflere TCP
# bağlantısı + vekil üzerinden bir HTTPS isteği dener, node'un saatini yazar ve pod'u siler.
#
# Kullanım (kümeye kubectl erişimi, uygulamanın namespace'inde pod oluşturma yetkisi gerekir):
#   NS=<namespace> REL=<helm release> TARGETS="ldap.kurum:636 push.kurum:443 ic-hedef:443" \
#     bash scripts/multipod-egress-check.sh [node-1 node-2 …]
#   Node verilmezse kümedeki bütün node'lar denenir (yalnız pod'ların koşabileceği node'ları verin: nodeSelector /
#   taint'ler). Pod restricted PodSecurity ile uyumludur (root değil, ayrıcalık yükseltme yok, yetenek yok).
#
# Çıktı: node başına "saat <epoch>", "rdap <http kodu>" ve her hedef için "OK host:port" / "FAIL host:port".
# Başarısız bir hedef varsa çıkış kodu 1. Saat değerleri birbirinden ve yerel saatten en çok 1-2 sn farklı olmalı (P2).
set -uo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

NS="${NS:?NS (namespace) zorunlu}"
REL="${REL:?REL (helm release adı) zorunlu}"
TARGETS="${TARGETS:-}"
TIMEOUT_S="${EGRESS_TIMEOUT_S:-180}"
command -v kubectl >/dev/null 2>&1 || { echo "::error::kubectl bulunamadı"; exit 2; }

sel="app.kubernetes.io/instance=$REL"
IMG="$(kubectl get deploy -n "$NS" -l "$sel" -o jsonpath='{.items[0].spec.template.spec.containers[0].image}')"
CM="$(kubectl get deploy -n "$NS" -l "$sel" -o jsonpath='{.items[0].spec.template.spec.containers[0].envFrom[?(@.configMapRef)].configMapRef.name}')"
PULL="$(kubectl get deploy -n "$NS" -l "$sel" -o jsonpath='{.items[0].spec.template.spec.imagePullSecrets[*].name}')"
[ -n "$IMG" ] && [ -n "$CM" ] || { echo "::error::$NS/$REL için Deployment bulunamadı (imaj='$IMG', configmap='$CM')"; exit 2; }
if [ "$#" -gt 0 ]; then NODES="$*"; else NODES="$(kubectl get nodes -o jsonpath='{.items[*].metadata.name}')"; fi
pull_yaml=""
for p in $PULL; do pull_yaml="$pull_yaml{name: $p},"; done
[ -n "$pull_yaml" ] && pull_yaml="imagePullSecrets: [${pull_yaml%,}]"

echo "İmaj $IMG, ConfigMap $CM, yerel saat $(date -u +%s), hedefler: SMTP (ConfigMap) ${TARGETS:-<ek hedef yok>}"
fail=0; i=0
for NODE in $NODES; do
  i=$((i + 1)); POD="sitemonitor-egress-check-$i-$(date +%s)"
  # Heredoc burada açılır (pod adı, node, imaj); pod İÇİNDE açılacak değişkenler ($HTTP_PROXY_HOST, $TARGETS …) $ ile kaçışlıdır.
  kubectl apply -n "$NS" -f - >/dev/null <<EOF || { echo "::error::$NODE: pod oluşturulamadı"; fail=1; continue; }
apiVersion: v1
kind: Pod
metadata:
  name: $POD
  labels: { app.kubernetes.io/name: sitemonitor-egress-check }
spec:
  nodeName: $NODE
  restartPolicy: Never
  $pull_yaml
  securityContext: { runAsNonRoot: true, runAsUser: 1000, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: check
      image: $IMG
      envFrom: [ { configMapRef: { name: $CM } } ]
      env: [ { name: TARGETS, value: "$TARGETS" } ]
      securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: [ALL] } }
      resources: { requests: { cpu: 50m, memory: 64Mi }, limits: { cpu: 500m, memory: 128Mi } }
      command:
        - bash
        - -c
        - |
          echo "saat \$(date -u +%s)"
          V=""; [ -n "\${HTTP_PROXY_HOST:-}" ] && [ "\${HTTP_PROXY_PORT:-0}" != "0" ] && V="-x \$HTTP_PROXY_HOST:\$HTTP_PROXY_PORT"
          code=\$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 \$V https://data.iana.org/rdap/dns.json 2>/dev/null)
          echo "rdap \${code:-000} (\${V:-doğrudan})"; bad=0; [ "\$code" = "200" ] || bad=1
          for hp in "\${SPRING_MAIL_HOST:-}:\${SPRING_MAIL_PORT:-}" \$TARGETS; do
            case "\$hp" in :|*:) continue ;; esac
            if timeout 5 bash -c "</dev/tcp/\${hp%:*}/\${hp##*:}" 2>/dev/null; then echo "OK   \$hp"; else echo "FAIL \$hp"; bad=1; fi
          done
          exit \$bad
EOF
  phase=""
  for _ in $(seq 1 "$TIMEOUT_S"); do
    phase="$(kubectl get pod -n "$NS" "$POD" -o jsonpath='{.status.phase}' 2>/dev/null)"
    case "$phase" in Succeeded|Failed) break ;; esac
    sleep 1
  done
  echo "== $NODE ($phase)"
  kubectl logs -n "$NS" "$POD" 2>&1 | sed 's/^/   /'
  [ "$phase" = "Succeeded" ] || { echo "::error::$NODE: denetim başarısız ya da zaman aşımı (faz '$phase')"; fail=1; }
  kubectl delete pod -n "$NS" "$POD" --wait=false >/dev/null 2>&1 || true
done

if [ "$fail" -ne 0 ]; then
  echo "Egress denetimi: en az bir node'da BAŞARISIZ — o node düzeltilmeden ya da pod'lar sağlam node'lara sabitlenmeden (Helm nodeSelector / affinity) 2 pod'a geçmeyin."
  exit 1
fi
echo "Egress denetimi: bütün node'lar tamam. Saat değerlerini karşılaştırın (en çok 1-2 sn fark)."
