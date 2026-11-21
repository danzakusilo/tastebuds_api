{{/* Chart name, truncated to 63 characters. */}}
{{- define "tastebuds-core.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Fully qualified app name, truncated to 63 characters. */}}
{{- define "tastebuds-core.fullname" -}}
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

{{/* Chart name and version for the helm.sh/chart label. */}}
{{- define "tastebuds-core.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Common labels. Takes a dict: ctx (root context), component. */}}
{{- define "tastebuds-core.labels" -}}
helm.sh/chart: {{ include "tastebuds-core.chart" .ctx }}
{{ include "tastebuds-core.selectorLabels" . }}
app.kubernetes.io/version: {{ .ctx.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .ctx.Release.Service }}
app.kubernetes.io/part-of: tastebuds
{{- end }}

{{/* Selector labels. Takes a dict: ctx (root context), component. */}}
{{- define "tastebuds-core.selectorLabels" -}}
app.kubernetes.io/name: {{ include "tastebuds-core.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{/* Container image reference. */}}
{{- define "tastebuds-core.image" -}}
{{- printf "%s:%s" .Values.image.repository (default .Chart.AppVersion .Values.image.tag) }}
{{- end }}

{{/* Pod-level security settings (restricted Pod Security Standard). Fixed, not configurable. */}}
{{- define "tastebuds-core.podSecurityContext" -}}
automountServiceAccountToken: false
enableServiceLinks: false
securityContext:
  runAsNonRoot: true
  runAsUser: 65532
  runAsGroup: 65532
  fsGroup: 65532
  seccompProfile:
    type: RuntimeDefault
{{- end }}

{{/* Container-level security settings. Fixed, not configurable. */}}
{{- define "tastebuds-core.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop:
    - ALL
{{- end }}
