#!/usr/bin/env bash
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

set -euo pipefail

CHART_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VALUES_FILE="${CHART_DIR}/values.yaml"
LICENSE_MARKER="Licensed to the Apache Software Foundation"
TEST_NAMESPACE="default"
TEST_RELEASE="livycluster"

failures=0

echo "Checking ASF license headers in Helm source files..."
while IFS= read -r -d '' file; do
  if ! grep -q "${LICENSE_MARKER}" "${file}"; then
    echo "Missing license header: ${file}"
    failures=$((failures + 1))
  fi
done < <(find "${CHART_DIR}" \
  \( -path "${CHART_DIR}/templates/*" -o -name "Chart.yaml" -o -name "values.yaml" \) \
  \( -name "*.yaml" -o -name "*.tpl" \) -print0)

echo "Checking version consistency in values.yaml..."
for key in livyVersion scalaBinaryVersion sparkVersion clusterHost; do
  if ! grep -q "^${key}:" "${VALUES_FILE}"; then
    echo "Missing required values key: ${key}"
    failures=$((failures + 1))
  fi
done

spark_version="$(grep '^sparkVersion:' "${VALUES_FILE}" | awk '{print $2}' | tr -d '"')"

if ! grep -Fq 'charts/*.tgz' "${CHART_DIR}/.gitignore"; then
  echo "Expected dev/helmchart/.gitignore to exclude charts/*.tgz"
  failures=$((failures + 1))
fi

repo_root="$(git -C "${CHART_DIR}" rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -n "${repo_root}" ]] && git -C "${repo_root}" ls-files 'dev/helmchart/charts/*.tgz' | grep -q .; then
  echo "Committed chart archives found under dev/helmchart/charts; run helm dependency build locally instead"
  failures=$((failures + 1))
fi

echo "Checking rendered manifest contract..."
if command -v helm >/dev/null 2>&1; then
  if [[ ! -f "${CHART_DIR}/charts/ingress-nginx-4.12.1.tgz" ]] || \
     [[ ! -f "${CHART_DIR}/charts/cert-manager-v1.16.2.tgz" ]]; then
    echo "Fetching chart dependencies..."
    helm dependency build "${CHART_DIR}"
  fi

  rendered="$(mktemp)"
  helm template "${TEST_RELEASE}" "${CHART_DIR}" \
    --namespace "${TEST_NAMESPACE}" \
    --set ingress-nginx.enabled=false \
    --set cert-manager.enabled=false \
    > "${rendered}"

  for expected in \
    "livy.server.kubernetes.ingress.host=my-cluster.example.com" \
    "livy.server.kubernetes.grafana.loki.enabled=false" \
    "livy.file.local-dir-whitelist=/opt/jars" \
    "log4j.rootCategory=INFO, console" \
    "spark.kubernetes.namespace=${TEST_NAMESPACE}" \
    "spark.driver.cores=1" \
    "spark.driver.memory=1g" \
    "spark.kubernetes.container.image=your_repository/spark-py:v${spark_version}"; do
    if ! grep -q "${expected}" "${rendered}"; then
      echo "Rendered manifest missing expected config: ${expected}"
      failures=$((failures + 1))
    fi
  done

  expected_sa="${TEST_RELEASE}"
  if ! grep -q "serviceAccountName: ${expected_sa}" "${rendered}"; then
    echo "Rendered Livy StatefulSet missing serviceAccountName: ${expected_sa}"
    failures=$((failures + 1))
  fi
  if ! grep -q "kind: ServiceAccount" "${rendered}" || ! grep -q "name: ${expected_sa}" "${rendered}"; then
    echo "Rendered manifest missing ServiceAccount: ${expected_sa}"
    failures=$((failures + 1))
  fi

  if ! grep -q 'spark-examples_{{ .Values.scalaBinaryVersion }}-{{ .Values.sparkVersion }}.jar' \
    "${CHART_DIR}/templates/_helpers.tpl"; then
    echo "Spark examples JAR helper does not template scalaBinaryVersion and sparkVersion"
    failures=$((failures + 1))
  fi

  expected_config_map="${TEST_RELEASE}-config"
  if ! grep -q "name: ${expected_config_map}" "${rendered}"; then
    echo "Rendered manifest missing ConfigMap: ${expected_config_map}"
    failures=$((failures + 1))
  fi

  expected_history="${TEST_RELEASE}-history"
  if ! grep -q "name: ${expected_history}" "${rendered}"; then
    echo "Rendered manifest missing history StatefulSet: ${expected_history}"
    failures=$((failures + 1))
  fi

  rm -f "${rendered}"

  echo "Running helm lint..."
  helm lint "${CHART_DIR}" \
    --set ingress-nginx.enabled=false \
    --set cert-manager.enabled=false
else
  echo "helm not installed; skipping helm template and lint checks"
fi

if [[ "${failures}" -gt 0 ]]; then
  echo "${failures} validation check(s) failed"
  exit 1
fi

echo "Helm chart validation passed"
