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

from setuptools import setup

DESCRIPTION = "A simple Python API for Livy powered by requests"

CLASSIFIERS = [
    'Development Status :: 1 - Planning',
    'Intended Audience :: Developers',
    'Operating System :: OS Independent',
    'Programming Language :: Python :: 3',
    'Programming Language :: Python :: 3.9',
    'Topic :: Software Development :: Libraries :: Python Modules',
]

requirements = [
    'cloudpickle>=0.2.1',
    'requests>=2.10.0',
    'responses>=0.5.1',
    'requests-kerberos>=0.11.0',
]

setup(
    name='livy-python-api',
    # PEP 440 disallows Maven-style `-SNAPSHOT` suffixes; use `.dev0` instead,
    # which is the canonical Python equivalent for "pre-release under active
    # development". Modern pip (>= 24) rejects `1.0.0-SNAPSHOT` outright with
    # `Invalid version: '1.0.0-SNAPSHOT'` when parsing this package.
    version="1.0.0.dev0",
    packages=["livy"],
    package_dir={
        "": "src/main/python",
    },
    url='https://github.com/apache/livy',
    author_email='user@livy.apache.org',
    license='Apache License, Version 2.0',
    description=DESCRIPTION,
    platforms=['any'],
    keywords='livy pyspark development',
    classifiers=CLASSIFIERS,
    install_requires=requirements,
)
