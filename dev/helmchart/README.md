# Apache Livy on Kubernetes (Local Development)

This Helm chart deploys Apache Livy and Apache Spark on a local Kubernetes cluster,
such as Docker Desktop with Kubernetes enabled. It is intended for development and
debugging without relying on cloud services.

JIRA: [LIVY-979](https://issues.apache.org/jira/browse/LIVY-979)

## Prerequisites

1. Install [Docker Desktop](https://www.docker.com/products/docker-desktop/) and enable Kubernetes.
2. Install [Helm 3](https://helm.sh/docs/intro/install/).
3. Build and push the Spark and Livy container images described in [Docker.md](Docker.md).
4. Add a hosts entry that matches `clusterHost` in `values.yaml` (default `my-cluster.example.com`):

```text
127.0.0.1 my-cluster.example.com
```

## Version alignment

The chart defaults match a Livy build produced with:

```shell
mvn -Pthriftserver -Pscala-2.12 -Pspark3 package
```

| Setting | Default |
|---------|---------|
| Livy | `1.0.0-SNAPSHOT` |
| Spark | `3.5.6` |
| Scala | `2.12` |

Update `values.yaml` if you build with different profiles or versions. Image tags default
from `sparkVersion` when left empty (`v3.5.6` for Spark, `spark3.5.6` for Livy).

## Build chart dependencies

Chart dependencies are declared in `Chart.yaml` and resolved at deploy time. Do not
commit the downloaded archives under `charts/`:

```shell
cd dev/helmchart
helm dependency build
```

When ingress is enabled, the chart installs `cert-manager` and `ingress-nginx` as
subcharts. On a fresh cluster, cert-manager CRDs are installed via
`cert-manager.installCRDs=true` (the chart default).

References:
- [cert-manager nginx ingress tutorial](https://cert-manager.io/docs/tutorials/acme/nginx-ingress/)
- [cert-manager Helm chart](https://artifacthub.io/packages/helm/cert-manager/cert-manager)

## Deploy the chart

```shell
cd dev/helmchart
helm dependency build

kubectl create namespace livy-dev

helm -n livy-dev install livycluster . \
  --set image.livy.repository=<your_repository>/livy \
  --set image.spark.repository=<your_repository>/spark-py \
  --set ingress-nginx.controller.extraArgs.default-ssl-certificate=livy-dev/ingress-default-tls
```

Set `ingress-nginx.controller.extraArgs.default-ssl-certificate` to
`<release-namespace>/<tls-secret-name>` (defaults: `livy-dev/ingress-default-tls`).

## Remote debugging

Remote debugging is enabled by default (`debug.enabled: true`) on port `9010`, matching
the pattern used in `dev/docker/livy-dev-cluster/conf/livy/livy-env.sh`. Disable it for
non-debug deployments:

```shell
helm upgrade livycluster . -n livy-dev --set debug.enabled=false
```

## Grafana and Loki (optional)

Livy can link Spark driver and executor logs to Grafana when Loki is available. The chart
maps the following `values.yaml` keys to `livy.conf`:

| values.yaml | livy.conf |
|-------------|-----------|
| `grafana.loki.enabled` | `livy.server.kubernetes.grafana.loki.enabled` |
| `grafana.url` | `livy.server.kubernetes.grafana.url` |
| `grafana.lokiDatasource` | `livy.server.kubernetes.grafana.loki.datasource` |
| `grafana.timeRange` | `livy.server.kubernetes.grafana.timeRange` |

To enable Loki integration:

1. Install Grafana and Loki in the cluster (for example with the [Loki stack Helm chart](https://grafana.com/docs/loki/latest/setup/install/helm/)).
2. Add a Loki datasource named `loki` in Grafana (or override `grafana.lokiDatasource`).
3. Deploy or upgrade with Loki integration enabled:

```shell
helm upgrade livycluster . -n livy-dev \
  --set grafana.loki.enabled=true \
  --set grafana.url=http://grafana.livy-dev.svc.cluster.local:3000
```

When enabled, Livy adds log links in the session UI that open Grafana Explore for the
matching Spark application labels.

## Verify the deployment

```shell
kubectl -n livy-dev get pods -w
```

### REST API smoke tests

Create an interactive session:

```shell
curl -k -X POST -H "Content-Type: application/json" \
  --data '{"kind": "spark"}' \
  https://my-cluster.example.com/livy/sessions | jq
```

Run a statement:

```shell
curl -k -X POST \
  -H "Content-Type: application/json" \
  -d '{"kind": "spark", "code": "sc.parallelize(1 to 10).count()"}' \
  https://my-cluster.example.com/livy/sessions/0/statements | jq
```

Submit a batch job:

```shell
curl -s -k -H "Content-Type: application/json" -X POST \
  -d '{
    "name": "testbatch1",
    "className": "org.apache.spark.examples.SparkPi",
    "numExecutors": 2,
    "file": "local:///opt/spark/examples/jars/spark-examples_2.12-3.5.6.jar",
    "args": ["10000"]
  }' "https://my-cluster.example.com/livy/batches" | jq
```

Update the examples JAR path if you change `scalaBinaryVersion` or `sparkVersion` in
`values.yaml` (pattern: `spark-examples_<scala>-<spark>.jar`).

## Chart validation

Run the chart checks locally:

```shell
dev/helmchart/test/validate.sh
```

## Related documentation

- Docker image build steps: [Docker.md](Docker.md)
- Standalone Spark cluster (non-Kubernetes): [../docker/README.md](../docker/README.md)
