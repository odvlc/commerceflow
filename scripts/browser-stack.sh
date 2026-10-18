#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
if [ ! -f workflow-tests/target/test-classpath.txt ]; then
  echo 'First run: ./mvnw verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt -Dmdep.includeScope=test'
  exit 1
fi
BROWSER_CLASSPATH="$(cat workflow-tests/target/test-classpath.txt)"
exec java -Duser.timezone=UTC -cp "workflow-tests/target/test-classes:$BROWSER_CLASSPATH" com.commerceflow.workflow.BrowserTestStack
