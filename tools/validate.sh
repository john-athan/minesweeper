#!/usr/bin/env bash
# Every check this project has. CI calls this script and nothing else, so what
# runs here is exactly what runs there.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew --console=plain testDebugUnitTest lintDebug "$@"
