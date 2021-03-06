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

set -e -o pipefail

# Resolves directory paths
CURRENT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
ROOT_DIR="$( cd "`dirname $0`"/..; pwd )"

# Loads version variables from `pom.xml`
. ${ROOT_DIR}/bin/package.sh && get_package_variables_from_pom "${ROOT_DIR}"

# Downloads any data given an URL
download_app() {
  local url="$1/$2"
  local binary="${CURRENT_DIR}/$2"

  # check if we already have the data
  if [ -z "$2" -o ! -f "$binary" ]; then
    local curl_opts="--progress-bar -L"
    local wget_opts="--progress=bar:force"

    # check if we have curl installed
    # download application
    [ $(command -v curl) ] && echo "exec: curl ${curl_opts} ${url}" 1>&2 && \
      curl ${curl_opts} "${url}" > "${binary}"
    # if the file still doesn't exist, lets try `wget` and cross our fingers
    [ ! -f "${binary}" ] && [ $(command -v wget) ] && \
      echo "exec: wget ${wget_opts} ${url}" 1>&2 && \
      wget ${wget_opts} -O "${binary}" "${url}"
    # if both were unsuccessful, exit
    [ ! -f "${binary}" ] && \
      echo -n "ERROR: Cannot download $2 with cURL or wget; " && \
      echo "please download manually and try again." && \
      exit 2
  fi
  : # end of the function
}

download_app \
  "https://github.com/schemaspy/schemaspy/releases/download/v${SCHEMASPY_VERSION}" \
  "schemaspy-${SCHEMASPY_VERSION}.jar"

download_app \
  "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/${SQLITE_JDBC_VERSION}" \
  "sqlite-jdbc-${SQLITE_JDBC_VERSION}.jar"

download_app \
  "https://jdbc.postgresql.org/download" \
  "postgresql-${POSTGRESQL_JDBC_VERSION}.jar"

