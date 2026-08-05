# Site Monitor — OpenShift Installation Guide

## Prerequisites

| Tool | Minimum version |
|------|----------------|
| `oc` CLI | 4.10+ |
| `helm` | 3.12+ |
| OpenShift cluster | 4.10+ |
| Cluster role | `namespace-admin` (or `cluster-admin` for SCC setup) |

```bash
oc version
helm version
```

---

## 1. Create Project (Namespace)

```bash
oc new-project site-monitor
# or for a specific environment:
oc new-project site-monitor-staging
```

---

## 2. Security Context Constraints (SCC)

Site Monitor runs as UID **1000** with a read-only root filesystem. OpenShift's default `restricted` SCC blocks fixed UIDs on OCP < 4.11.

### Option A — OCP 4.11+ (recommended)

OCP 4.11+ ships `restricted-v2` which allows `runAsNonRoot` without a fixed UID. No SCC changes needed if you **remove** `runAsUser` from the values override:

```yaml
# openshift-values.yaml
podSecurityContext:
  runAsNonRoot: true
  fsGroup: 1000
  # runAsUser omitted — OpenShift assigns a UID from the project's range
```

### Option B — OCP 4.10 or earlier

Grant `anyuid` SCC to the chart's service account:

```bash
# After installing (service account is created by the chart)
oc adm policy add-scc-to-user anyuid \
  -z site-monitor-sitemonitor-chart \
  -n site-monitor
```

> The service account name follows the pattern `<release-name>-sitemonitor-chart`.
> Verify with: `oc get sa -n site-monitor`

### PostgreSQL subchart SCC (when `postgresql.enabled=true`)

Bitnami PostgreSQL also runs as a fixed UID (1001). Grant `anyuid` to its service account:

```bash
oc adm policy add-scc-to-user anyuid \
  -z site-monitor-postgresql \
  -n site-monitor
```

---

## 3. Ingress vs Route

OpenShift supports two approaches:

### Option A — OpenShift Route (native, no extra operator)

Skip Helm ingress and create a Route after install:

```bash
# Disable ingress in your values override
# ingress:
#   enabled: false

oc expose svc/site-monitor-sitemonitor-chart -n site-monitor
# For TLS edge termination:
oc create route edge site-monitor \
  --service=site-monitor-sitemonitor-chart \
  --hostname=site-monitor.apps.<cluster-domain> \
  -n site-monitor
```

### Option B — NGINX Ingress Operator

If the NGINX Ingress Operator is installed on your cluster, the default Helm values work as-is. Verify:

```bash
oc get ingresscontroller -n openshift-ingress-operator
```

Use `ingressClassName: nginx` in values (already the default).

---

## 4. Install — External PostgreSQL

```bash
helm dep update helm/site-monitor

helm upgrade --install site-monitor ./helm/site-monitor \
  -f helm/site-monitor/values.yaml \
  -f helm/site-monitor/environments/master.yaml \
  -f openshift-values.yaml \
  --set image.tag=<VERSION> \
  --set config.dbHost=<POSTGRES_HOST> \
  --set secret.adminPassword=<ADMIN_PASSWORD> \
  --set secret.dbPassword=<DB_PASSWORD> \
  --set secret.smtpPassword=<SMTP_PASSWORD> \
  --set ingress.enabled=false \
  -n site-monitor
```

Minimal `openshift-values.yaml` for OCP 4.11+:

```yaml
podSecurityContext:
  runAsNonRoot: true
  fsGroup: 1000
```

---

## 5. Install — Bundled PostgreSQL Subchart

```bash
helm dep update helm/site-monitor

helm upgrade --install site-monitor ./helm/site-monitor \
  -f helm/site-monitor/values.yaml \
  -f helm/site-monitor/environments/master.yaml \
  -f openshift-values.yaml \
  --set image.tag=<VERSION> \
  --set postgresql.enabled=true \
  --set postgresql.auth.password=<DB_PASSWORD> \
  --set secret.adminPassword=<ADMIN_PASSWORD> \
  --set secret.smtpPassword=<SMTP_PASSWORD> \
  --set ingress.enabled=false \
  -n site-monitor
```

Then grant SCC to the PostgreSQL service account (see §2 above).

---

## 6. Optional: Enable HPA and PDB

Both default to `false`. Enable per environment via `--set` or your env values file:

```bash
# HPA
--set autoscaling.enabled=true \
--set autoscaling.minReplicas=2 \
--set autoscaling.maxReplicas=5

# PDB
--set podDisruptionBudget.enabled=true \
--set podDisruptionBudget.minAvailable=1
```

> On OpenShift, HPA requires the Metrics Server or cluster metrics to be available.
> Verify: `oc get apiservice v1beta1.metrics.k8s.io`

---

## 7. Verify Installation

```bash
# Pod status
oc get pods -n site-monitor

# Application logs
oc logs -l app.kubernetes.io/name=sitemonitor-chart -n site-monitor

# Expose health check
oc port-forward svc/site-monitor-sitemonitor-chart 8080:80 -n site-monitor
curl http://localhost:8080/actuator/health

# If using Route
oc get route -n site-monitor
curl https://<route-host>/actuator/health
```

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Pod stuck `CreateContainerConfigError` | SCC blocks UID 1000 | Grant `anyuid` SCC (§2) |
| PostgreSQL pod `CrashLoopBackOff` | SCC blocks postgres UID | Grant `anyuid` to postgresql SA (§2) |
| Ingress 404 / no route | Ingress class not found | Use Route instead (§3 Option A) |
| `ADMIN_PASSWORD` not set warning | Secret placeholder not overridden | Pass `--set secret.adminPassword=<val>` |
| HPA `unknown` metrics | Metrics server absent | Install OpenShift metrics or disable HPA |
