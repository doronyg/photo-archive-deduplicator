#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

gradle_status=0
./gradlew --continue --rerun-tasks :shared:allTests :desktopApp:test :app:testDebugUnitTest "$@" || gradle_status=$?

python3 - "$ROOT_DIR" <<'PY'
import glob
import os
import sys
import xml.etree.ElementTree as ET

root = sys.argv[1]
targets = [
    ("shared:jvmTest", "shared/build/test-results/jvmTest"),
    ("shared:testAndroidHostTest", "shared/build/test-results/testAndroidHostTest"),
    ("desktopApp:test", "desktopApp/build/test-results/test"),
    ("app:testDebugUnitTest", "app/build/test-results/testDebugUnitTest"),
]
totals = {"tests": 0, "passed": 0, "failed": 0, "skipped": 0}

print("\nTest results:")
for name, relative_dir in targets:
    result_files = glob.glob(os.path.join(root, relative_dir, "**", "TEST-*.xml"), recursive=True)
    counts = {"tests": 0, "failed": 0, "skipped": 0}
    for path in result_files:
        suite = ET.parse(path).getroot()
        counts["tests"] += int(suite.get("tests", 0))
        counts["failed"] += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
        counts["skipped"] += int(suite.get("skipped", 0))

    counts["passed"] = counts["tests"] - counts["failed"] - counts["skipped"]
    for key in totals:
        totals[key] += counts[key]

    print(
        f"  {name}: {counts['passed']} passed, {counts['failed']} failed, "
        f"{counts['skipped']} skipped ({counts['tests']} total)"
    )

print(
    f"  Total: {totals['passed']} passed, {totals['failed']} failed, "
    f"{totals['skipped']} skipped ({totals['tests']} total)"
)
PY

exit "$gradle_status"
