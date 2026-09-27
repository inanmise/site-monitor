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

### PostgreSQL subchart — removed

The bundled Bitnami PostgreSQL subchart was removed from the chart (see `Chart.yaml`). Use an external /
managed PostgreSQL (section 4); `postgresql.enabled=true` is no longer supported. The command below is
kept only for clusters that still run an old subchart-managed database:

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
# The HAProxy router cuts requests after 30 s by default; a manual page-integrity check can take
# up to 120 s. Raise the route timeout above that:
oc annotate route site-monitor haproxy.router.openshift.io/timeout=130s -n site-monitor
```

### Option B — NGINX Ingress Operator

If the NGINX Ingress Operator is installed on your cluster, the default Helm values work as-is. Verify:

```bash
oc get ingresscontroller -n openshift-ingress-operator
```

Use `ingressClassName: nginx` in values (already the default).

---

## 4. Install — External PostgreSQL

For production follow `docs/PROD_DEPLOY_CHECKLIST.md` (the one supported prod command, secret-key
carry-over, rollback). The OpenShift-specific additions are the extra values file and `ingress.enabled=false`:

```bash
helm upgrade --install "$REL" ./helm/site-monitor --namespace "$NS" \
  -f helm/site-monitor/values.yaml \
  -f helm/site-monitor/environments/master.yaml \
  -f /secure/path/prod-private.yaml \
  -f openshift-values.yaml \
  --set image.repository=ghcr.io/<owner>/site-monitor \
  --set image.tag=v<VERSION> \
  --set secret.existingSecret=<existing-secret-name> \
  --set ingress.enabled=false \
  --atomic --wait --timeout 10m
```

- The image tag carries a `v` (`v20.86.0`): the release pipeline pushes only `:vX.Y.Z` and `:latest`.
- `prod-private.yaml` (kept outside the repo) holds `config.dbHost`, `config.dbName`, `config.dbUser`.
- `REL` / `NS` must be the real release name and namespace (`helm list -A`); another name installs a second release.
- A fresh install without `existingSecret` must pass every `secret.*` value, including a new random
  `secret.secretKey` (32+ characters). An existing install must keep its CURRENT `SITE_MONITOR_SECRET_KEY`.

Minimal `openshift-values.yaml` for OCP 4.11+:

```yaml
podSecurityContext:
  runAsNonRoot: true
  fsGroup: 1000
```

---

## 5. Install — Bundled PostgreSQL Subchart (removed)

No longer available: the subchart was removed from the chart. Provision PostgreSQL separately and use section 4.

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
curl http://localhost:8080/health/readiness   # 200 once the app accepts traffic
curl http://localhost:8080/health/liveness

# If using Route
oc get route -n site-monitor
curl https://<route-host>/health/readiness
```

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Pod stuck `CreateContainerConfigError` | SCC blocks UID 1000 | Grant `anyuid` SCC (§2) |
| `ImagePullBackOff` | Tag passed without the `v` prefix, or a private registry without credentials | `--set image.tag=vX.Y.Z`; set `imagePullSecrets` |
| Ingress 404 / no route | Ingress class not found | Use Route instead (§3 Option A) |
| `ADMIN_PASSWORD` not set warning | Secret placeholder not overridden | Pass `--set secret.adminPassword=<val>` |
| HPA `unknown` metrics | Metrics server absent | Install OpenShift metrics or disable HPA |
