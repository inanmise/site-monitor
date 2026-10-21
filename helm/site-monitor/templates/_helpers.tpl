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
