#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

{{/* vim: set filetype=mustache: */}}
{{/*
Expand the name of the chart.
*/}}
{{- define "livycluster.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "livycluster.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "livycluster.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels for the Spark History Server component.
*/}}
{{- define "livycluster.historyLabels" -}}
app.kubernetes.io/name: {{ include "livycluster.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: history-server
helm.sh/chart: {{ include "livycluster.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/*
Selector labels for the Spark History Server component.
*/}}
{{- define "livycluster.historySelectorLabels" -}}
app.kubernetes.io/name: {{ include "livycluster.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: history-server
{{- end -}}

{{/*
Spark image tag; defaults to v<sparkVersion> when not overridden.
*/}}
{{- define "livycluster.sparkImageTag" -}}
{{- .Values.image.spark.tag | default (printf "v%s" .Values.sparkVersion) -}}
{{- end -}}

{{/*
Livy image tag; defaults to spark<sparkVersion> when not overridden.
*/}}
{{- define "livycluster.livyImageTag" -}}
{{- .Values.image.livy.tag | default (printf "spark%s" .Values.sparkVersion) -}}
{{- end -}}

{{/*
Spark examples JAR path inside the container image.
*/}}
{{- define "livycluster.sparkExamplesJar" -}}
local:///opt/spark/examples/jars/spark-examples_{{ .Values.scalaBinaryVersion }}-{{ .Values.sparkVersion }}.jar
{{- end -}}

{{/*
History server URL exposed through ingress.
*/}}
{{- define "livycluster.historyServerUrl" -}}
https://{{ .Values.clusterHost }}/historyserver
{{- end -}}

{{/*
Default TLS certificate reference for ingress-nginx (namespace/secretName).
*/}}
{{- define "livycluster.defaultTlsCertificate" -}}
{{- printf "%s/%s" .Release.Namespace .Values.ingress.tls.secretName -}}
{{- end -}}

{{/*
Optional JDWP agent for remote debugging.
*/}}
{{- define "livycluster.serverJavaOpts" -}}
{{- if .Values.debug.enabled -}}
-agentlib:jdwp=transport=dt_socket,server=y,address={{ .Values.debug.port }},suspend=n
{{- end -}}
{{- end -}}
