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

FROM ubuntu:noble

# configure locale
RUN apt-get update -qq > /dev/null && apt-get install -qq --yes --no-install-recommends \
    locales && \
    locale-gen en_US.UTF-8
ENV LANG="en_US.UTF-8" \
    LANGUAGE="en_US.UTF-8" \
    LC_ALL="en_US.UTF-8" \
    TZ=US \
    DEBIAN_FRONTEND=noninteractive

# Install necessary dependencies for build/test/debug
# Use `lsof -i -P -n` to find open ports
RUN apt-get install -qq \
    apt-transport-https \
    curl \
    git \
    libkrb5-dev \
    openjdk-8-jdk-headless \
    openjdk-17-jdk-headless \
    r-base \
    maven \
    software-properties-common \
    vim \
    wget \
    telnet \
    lsof

# This fixes integration tests
# If setsid is available, signals are sent to containers in MiniYarnCluster using negative pids, however
# in the Docker container this results in a kill(0) system call which triggers an ExitCodeException in
# the kill function that breaks test execution. If setsid is removed, pids < 0 are not used.
# See https://github.com/apache/hadoop/blob/rel/release-2.7.3/hadoop-common-project/hadoop-common/src/main/java/org/apache/hadoop/util/Shell.java#L238
RUN rm /usr/bin/setsid

# python build
RUN apt-get install -y \
  build-essential \
  libbz2-dev \
  libffi-dev \
  liblzma-dev \
  libncurses-dev \
  libreadline-dev \
  libsqlite3-dev \
  libssl-dev \
  zlib1g-dev

ENV HOME=/root

RUN git clone https://github.com/pyenv/pyenv.git $HOME/pyenv

ENV PYENV_ROOT=$HOME/pyenv
ENV PATH="$HOME/pyenv/shims:$HOME/pyenv/bin:$HOME/bin:$PATH"

# Python 3.11.11 is chosen because it is in the officially supported range of
# BOTH matrix profiles, per each Spark release's python/setup.py:
#   * -Pspark4 (Spark 4.1.2): python_requires=">=3.10", classifiers list
#     3.10 / 3.11 / 3.12 / 3.13 / 3.14.
#   * -Pspark3 (Spark 3.5.6): python_requires=">=3.8", classifiers list
#     3.8 / 3.9 / 3.10 / 3.11.
# The intersection is 3.10 and 3.11; we pick the higher one (3.11.11) so the
# same image serves both profiles. When bumping this, keep
# .github/workflows/integration-tests.yaml's `pyenv global` in sync.
RUN pyenv install -v 3.11.11 && \
  pyenv global 3.11.11 && \
  pyenv rehash

# Install build dependencies for python3
RUN python3 -m pip install -U pip && pip3 install \
        cloudpickle \
        codecov \
        flake8 \
        flaky \
        pytest \
        pytest-runner \
        requests-kerberos \
        requests \
        responses

RUN pyenv rehash

RUN apt remove -y openjdk-11-jre-headless

WORKDIR /workspace
