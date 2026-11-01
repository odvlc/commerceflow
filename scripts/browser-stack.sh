#!/usr/bin/env bash
# Starts the ephemeral browser harness after test classpaths exist. The stop sentinel triggers ordered shutdown.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
if [ ! -f workflow-tests/target/test-classpath.txt ]; then
  echo 'First run: ./mvnw verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test'
  exit 1
fi
BROWSER_CLASSPATH="$(cat workflow-tests/target/test-classpath.txt)"
# Replace the shell with the harness so signals reach its ordered shutdown handler.
exec java -Duser.timezone=UTC -cp "workflow-tests/target/test-classes:$BROWSER_CLASSPATH" com.commerceflow.workflow.BrowserTestStack
