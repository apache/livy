# Building Container Images for the Kubernetes Helm Chart

These steps build Spark and Livy images for the local Kubernetes development environment
described in [README.md](README.md). Version defaults match `values.yaml`.

## Build Livy

From the repository root:

```shell
mvn -Pthriftserver -Pscala-2.12 -Pspark3 package
cp assembly/target/apache-livy-1.0.0-SNAPSHOT_2.12-bin.zip /tmp/
```

## Spark image with Python bindings

Download and extract Spark:

```shell
wget https://archive.apache.org/dist/spark/spark-3.5.6/spark-3.5.6-bin-hadoop3.tgz
tar -xzf spark-3.5.6-bin-hadoop3.tgz
```

Build and push the Spark image:

```shell
./spark-3.5.6-bin-hadoop3/bin/docker-image-tool.sh \
  -r <your_repository> \
  -t v3.5.6 \
  -p kubernetes/dockerfiles/spark/bindings/python/Dockerfile \
  build

./spark-3.5.6-bin-hadoop3/bin/docker-image-tool.sh \
  -r <your_repository> \
  -t v3.5.6 \
  -p kubernetes/dockerfiles/spark/bindings/python/Dockerfile \
  push
```

## Livy image

Create `/tmp/Dockerfile`:

```dockerfile
FROM <your_repository>/spark-py:v3.5.6

ENV LIVY_VERSION=1.0.0-SNAPSHOT
ENV LIVY_PACKAGE=apache-livy-${LIVY_VERSION}_2.12-bin
ENV LIVY_HOME=/opt/livy
ENV LIVY_CONF_DIR=/conf
ENV PATH=$PATH:$LIVY_HOME/bin

USER root

COPY ${LIVY_PACKAGE}.zip /
RUN apt-get update && apt-get install -y unzip && \
    unzip /${LIVY_PACKAGE}.zip -d / && \
    mv /${LIVY_PACKAGE} /opt/ && \
    rm -rf ${LIVY_HOME} && \
    ln -s /opt/${LIVY_PACKAGE} ${LIVY_HOME} && \
    rm -f /${LIVY_PACKAGE}.zip

RUN mkdir -p /var/log/livy && ln -s /var/log/livy ${LIVY_HOME}/logs

WORKDIR ${LIVY_HOME}
ENTRYPOINT ["livy-server"]
```

Build and push:

```shell
cd /tmp
docker build -t <your_repository>/livy:spark3.5.6 .
docker push <your_repository>/livy:spark3.5.6
rm -f apache-livy-1.0.0-SNAPSHOT_2.12-bin.zip
```

Update `values.yaml` or pass `--set` flags so `image.livy.repository`,
`image.livy.tag`, `image.spark.repository`, and `image.spark.tag` match the images
you pushed.
