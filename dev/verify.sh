#!/bin/bash
# 本机验证（不连真机）：JVM 单测 + 构建 + lint + 空白检查，并汇总单测结果。
# 真机项目请另跑 connectedAndroidTest，再用 dev/check-test-results.py 核对跳过情况。
# 用法：dev/verify.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== 1/3 git diff --check =="
git diff --check && echo "空白检查干净"
echo

echo "== 2/3 gradle: testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug =="
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
echo

echo "== 3/3 单测结果汇总 =="
python3 dev/check-test-results.py --allow-skipped "app/build/test-results/testDebugUnitTest/TEST-*.xml"
