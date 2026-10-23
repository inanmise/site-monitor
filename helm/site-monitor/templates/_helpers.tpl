{{/*
Expand the name of the chart.
*/}}
{{- define "site-monitor.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "site-monitor.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Chart label
*/}}
{{- define "site-monitor.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "site-monitor.labels" -}}
helm.sh/chart: {{ include "site-monitor.chart" . }}
{{ include "site-monitor.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "site-monitor.selectorLabels" -}}
app.kubernetes.io/name: {{ include "site-monitor.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
ServiceAccount name
*/}}
{{- define "site-monitor.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "site-monitor.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Image tag. release.yml pushes ONLY ghcr.io/<owner>/site-monitor:vX.Y.Z and :latest, while
Chart.AppVersion is the bare "X.Y.Z" -> the default MUST add the "v" prefix, otherwise a plain
install asks the registry for a tag that does not exist (ImagePullBackOff, prod gate P3-1).
An explicit image.tag (develop-<sha>, X.Y.Z-rc, a pinned vX.Y.Z) is used exactly as given.
*/}}
{{- define "site-monitor.imageTag" -}}
{{- .Values.image.tag | default (printf "v%s" (trimPrefix "v" .Chart.AppVersion)) -}}
{{- end }}

{{/*
Full image reference: repository:tag, plus "@sha256:..." when image.digest is set. With a digest
the node pulls exactly that content even if the tag was re-pushed (rollback to a known image).
*/}}
{{- define "site-monitor.image" -}}
{{- $ref := printf "%s:%s" .Values.image.repository (include "site-monitor.imageTag" .) -}}
{{- if .Values.image.digest -}}
{{- $ref = printf "%s@%s" $ref .Values.image.digest -}}
{{- end -}}
{{- $ref -}}
{{- end }}

{{/*
Secret name — either existing or managed by this chart
*/}}
{{- define "site-monitor.secretName" -}}
{{- if .Values.secret.existingSecret }}
{{- .Values.secret.existingSecret }}
{{- else }}
{{- include "site-monitor.fullname" . }}-secret
{{- end }}
{{- end }}

{{/*
OpenTelemetry Java agent (2026-10-21): the operator's inject annotation written by hand into podAnnotations; empty when
absent or an explicit opt-out ("false"). Used by the deployment guard and NOTES.txt.
*/}}
{{- define "site-monitor.manualOtelInject" -}}
{{- $v := toString (get (.Values.podAnnotations | default dict) "instrumentation.opentelemetry.io/inject-java") -}}
{{- if and (ne $v "") (ne (lower $v) "false") -}}{{ $v }}{{- end -}}
{{- end }}

{{/*
Query parameter names the OpenTelemetry Java agent redacts in span url.query / url.full
(OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS). The setting REPLACES the agent's
built-in list (2.32.0: AWSAccessKeyId … X-Goog-Signature), so it is repeated; exact, case-sensitive names. "token" =
the weekly / technical-error report approval links. Single source: OpenTelemetryAgentProbe.SENSITIVE_QUERY_PARAMETERS
(OtelAgentQueryRedactionGateTest compares them).
*/}}
{{- define "site-monitor.otelSensitiveQueryParameters" -}}
AWSAccessKeyId,Signature,X-Amz-Signature,X-Amz-Credential,X-Amz-Security-Token,sig,X-Goog-Signature,token,access_token,api_key,apiKey,apikey,key,code,password,secret,signature,client_secret,subscription-key,auth,jsessionid,otp
{{- end }}

{{/*
Container env of the OpenTelemetry Java agent (config.otel.agent.enabled) as a YAML map — the deployment renders it,
the ConfigMap refuses config.extraEnv keys it contains (container env would silently win over envFrom). Defaults: no
client spans / traceparent on checks to monitored sites, metrics stay on Prometheus, Spring Boot must not map the
agent's OTEL_* variables into its own exporter, log MDC keys stay traceId/spanId, credential query parameters redacted
in url.query, and (2026-10-23) no span per @Scheduled tick. config.otel.agent.extraEnv wins over every default.
*/}}
{{- define "site-monitor.agentEnv" -}}
{{- $otel := .Values.config.otel | default dict -}}
{{- $agent := $otel.agent | default dict -}}
{{- $env := dict "OTEL_SERVICE_NAME" ($agent.serviceName | default (include "site-monitor.fullname" .)) "OTEL_METRICS_EXPORTER" "none" "OTEL_INSTRUMENTATION_JAVA_HTTP_CLIENT_ENABLED" "false" "OTEL_INSTRUMENTATION_HTTP_URL_CONNECTION_ENABLED" "false" "MANAGEMENT_OPENTELEMETRY_MAP_ENVIRONMENT_VARIABLES" "false" "OTEL_INSTRUMENTATION_COMMON_LOGGING_TRACE_ID_KEY" "traceId" "OTEL_INSTRUMENTATION_COMMON_LOGGING_SPAN_ID_KEY" "spanId" "OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS" (include "site-monitor.otelSensitiveQueryParameters" .) "OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED" "false" -}}
{{- with $otel.resourceAttributes -}}
{{- $_ := set $env "OTEL_RESOURCE_ATTRIBUTES" . -}}
{{- end -}}
{{- toYaml (mergeOverwrite $env ($agent.extraEnv | default dict)) -}}
{{- end }}

{{/*
Environment names config.extraEnv must not use (2026-10-23): the container env of the Deployment (it wins over the
ConfigMap) and the keys of the chart's Secret (envFrom secretRef comes after the ConfigMap and wins; a secret also does
not belong in a ConfigMap). Keep in step with templates/deployment.yaml `env:` and templates/secret.yaml —
scripts/helm-template-check.sh renders the Deployment and refuses every container env name through config.extraEnv.
*/}}
{{- define "site-monitor.reservedEnvNames" -}}
NODE_NAME POD_NAME POD_IP APP_ENVIRONMENT HELM_RELEASE_NAME HELM_RELEASE_REVISION HELM_CHART_VERSION APP_IMAGE_REF CONFIG_CHECKSUM ADMIN_USERNAME ADMIN_PASSWORD DB_PASSWORD SPRING_MAIL_USERNAME SPRING_MAIL_PASSWORD SITE_MONITOR_EMAIL_FROM SITE_MONITOR_SECRET_KEY SITE_MONITOR_SECRET_KEY_PREVIOUS METRICS_EXPORT_TOKEN HTTP_PROXY_USER HTTP_PROXY_PASS
{{- end }}

{{/*
DB_POOL_MIN (2026-10-23): config.dbPoolMin, default "5"; 0 is a valid value (Hikari minimum-idle 0). helm template
fails for a non-number or a value above config.dbPoolMax (Hikari would silently raise maximum-pool-size).
*/}}
{{- define "site-monitor.dbPoolMin" -}}
{{- $v := .Values.config.dbPoolMin -}}
{{- $min := "5" -}}
{{- if and (not (kindIs "invalid" $v)) (ne (toString $v) "") -}}{{- $min = toString $v -}}{{- end -}}
{{- if not (regexMatch "^[0-9]+$" $min) -}}
{{- fail (printf "config.dbPoolMin must be a whole number ≥ 0, got %q" $min) -}}
{{- end -}}
{{- $max := toString .Values.config.dbPoolMax -}}
{{- if and (regexMatch "^[0-9]+$" $max) (gt (int $min) (int $max)) -}}
{{- fail (printf "config.dbPoolMin (%s) must not be larger than config.dbPoolMax (%s): it is the number of idle connections kept per pod, the pool still grows to dbPoolMax" $min $max) -}}
{{- end -}}
{{- $min -}}
{{- end }}
