#!/usr/bin/env bash
# Prepares pinned native test dependencies under the ignored cache; these processes are separate
# from persistent runtime infrastructure.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$PROJECT_ROOT/.cache/tools"
REDIS_VERSION=8.10.2
REDIS_SHA256=b9ffee226b5eecdba98a679260dad764b2a4ebd90dce4ad5ac9e9f3eef9c02b3
# All downloaded/native build outputs stay in the ignored cache rather than modifying application
# source.
mkdir -p "$TOOLS"
if [ ! -x "$TOOLS/redis-$REDIS_VERSION/src/redis-server" ]; then
  curl --fail --location --retry 3 "https://download.redis.io/releases/redis-$REDIS_VERSION.tar.gz" -o "$TOOLS/redis-$REDIS_VERSION.tar.gz"
  printf '%s  %s\n' "$REDIS_SHA256" "$TOOLS/redis-$REDIS_VERSION.tar.gz" | shasum -a 256 -c -
  tar -xzf "$TOOLS/redis-$REDIS_VERSION.tar.gz" -C "$TOOLS"
fi
# Build core only: CommerceFlow needs strings, hashes and Lua, not optional Redis modules.
make -C "$TOOLS/redis-$REDIS_VERSION/src" -j4 redis-server
"$TOOLS/redis-$REDIS_VERSION/src/redis-server" --version

case "$(uname -s)" in Darwin) ES_OS=darwin ;; Linux) ES_OS=linux ;; *) echo 'Use Docker Testcontainers on this OS'; exit 1 ;; esac
case "$(uname -m)" in arm64|aarch64) ES_ARCH=aarch64 ;; x86_64) ES_ARCH=x86_64 ;; *) echo 'Unsupported native test architecture'; exit 1 ;; esac
ES_ARCHIVE="elasticsearch-9.4.5-$ES_OS-$ES_ARCH.tar.gz"
if [ ! -x "$TOOLS/elasticsearch-9.4.5/bin/elasticsearch" ]; then
  curl --fail --location --retry 3 "https://artifacts.elastic.co/downloads/elasticsearch/$ES_ARCHIVE" -o "$TOOLS/$ES_ARCHIVE"
  curl --fail --location --retry 3 "https://artifacts.elastic.co/downloads/elasticsearch/$ES_ARCHIVE.sha512" -o "$TOOLS/$ES_ARCHIVE.sha512"
  (cd "$TOOLS" && shasum -a 512 -c "$ES_ARCHIVE.sha512")
  tar -xzf "$TOOLS/$ES_ARCHIVE" -C "$TOOLS"
fi
"$TOOLS/elasticsearch-9.4.5/bin/elasticsearch" --version

if [ ! -x "$TOOLS/keycloak-26.8.1/bin/kc.sh" ]; then
  curl --fail --location --retry 3 https://github.com/keycloak/keycloak/releases/download/26.8.1/keycloak-26.8.1.tar.gz -o "$TOOLS/keycloak-26.8.1.tar.gz"
  printf '%s  %s\n' 4e2740b181faa2ea623a1c2ecf00855935311e546046e2f857615a47ed24cbe8 "$TOOLS/keycloak-26.8.1.tar.gz" | shasum -a 256 -c -
  tar -xzf "$TOOLS/keycloak-26.8.1.tar.gz" -C "$TOOLS"
fi
"$TOOLS/keycloak-26.8.1/bin/kc.sh" --version
