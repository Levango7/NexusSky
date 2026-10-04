{{/*
NexusSky Chart 命名助手：资源名 release 化。

为什么需要：本 chart 此前所有资源名是静态的（cloud-backend / nexussky-config /
flight-logs ...），同一 namespace 装第二个 release（staging+prod、蓝绿）必撞名。
标准做法是资源名带上 release 前缀（helm create 的 fullname 模式）：
  <release>-cloud-backend、<release>-gcs-web、<release>-drone-sim、
  <release>-config、<release>-secret、<release>-flight-logs、
  <release>-postgres（builtin PG）、<release>-ingress、<release>-actuator-restrict
chart 内部交叉引用（envFrom / claimName / Ingress backend / HPA scaleTarget）
全部改为引用这些助手，保证改名后引用一致。

兼容性：release 名含 chart 名（如 helm install nexussky ...）时前缀即 release 名
本身的标准行为；gcs-web 镜像内 nginx 反代上游不再写死 cloud-backend:8080，
改经 NS_BACKEND_HOST/NS_BACKEND_PORT 环境变量注入（见 Dockerfile.web 与
deploy/docker/nginx.conf 模板），默认值保持 compose 行为不变。
*/}}

{{- define "nexussky.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "nexussky.fullname" -}}
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

{{/* 各组件资源名：<fullname>-<component>。组件段固定，release 段由上面决定。 */}}
{{- define "nexussky.cloudBackendFullname" -}}
{{- printf "%s-cloud-backend" (include "nexussky.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "nexussky.droneSimFullname" -}}
{{- printf "%s-drone-sim" (include "nexussky.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "nexussky.gcsWebFullname" -}}
{{- printf "%s-gcs-web" (include "nexussky.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "nexussky.configFullname" -}}
{{- printf "%s-config" (include "nexussky.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "nexussky.secretFullname" -}}
{{- printf "%s-secret" (include "nexussky.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "nexussky.flightLogsFullname" -}}
{{- printf "%s-flight-logs" (include "nexussky.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "nexussky.postgresFullname" -}}
{{- printf "%s-postgres" (include "nexussky.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "nexussky.ingressFullname" -}}
{{- printf "%s-ingress" (include "nexussky.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "nexussky.actuatorRestrictFullname" -}}
{{- printf "%s-actuator-restrict" (include "nexussky.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* 内置 PG 启用时的数据源 URL（否则用 values.database.url 指向外部 PG）。 */}}
{{- define "nexussky.datasourceUrl" -}}
{{- if .Values.database.builtin.enabled -}}
{{- printf "jdbc:postgresql://%s:5432/%s" (include "nexussky.postgresFullname" .) .Values.database.builtin.database | quote }}
{{- else -}}
{{- .Values.database.url | quote }}
{{- end }}
{{- end }}
