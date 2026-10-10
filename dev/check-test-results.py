#!/usr/bin/env python3
"""汇总 JUnit XML 结果，并在出现失败或跳过用例时报警。

背景：androidTest 有大量 assumeTrue 门控，无真机/无服务时用例会以
assumption failure 结束、报告里仍显示绿。本脚本把“跳过”当成需要关注的结果，
避免把“整类被跳过”误读为通过。

用法：
  dev/check-test-results.py [--allow-skipped] [--expected-skips 文件] [路径或 glob ...]

默认路径（connectedAndroidTest 产物）：
  app/build/outputs/androidTest-results/connected/**/TEST-*.xml

预期跳过名单默认读脚本同目录的 dev/expected-skips.txt：名单内的用例跳过不算失败
（它们是靠显式 `-e` 开关 opt-in 的探针）；名单外的跳过一律报警。

退出码：0 通过；1 有 failures/errors；2 有未预期的跳过且未加 --allow-skipped；3 找不到结果文件或参数错误。
"""
from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_PATTERNS = ["app/build/outputs/androidTest-results/connected/**/TEST-*.xml"]
DEFAULT_EXPECTED_SKIPS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "expected-skips.txt")


def load_expected_skips(path):
    """读预期跳过名单：每行一个 `包名.类名.方法名`，# 后为注释。"""
    if not os.path.isfile(path):
        return set()
    names = set()
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            entry = line.split("#", 1)[0].strip()
            if entry:
                names.add(entry)
    return names


def collect_files(patterns):
    files = []
    for pattern in patterns:
        if any(ch in pattern for ch in "*?["):
            files.extend(glob.glob(pattern, recursive=True))
        elif os.path.isdir(pattern):
            files.extend(glob.glob(os.path.join(pattern, "**", "TEST-*.xml"), recursive=True))
        else:
            files.append(pattern)
    return sorted({f for f in files if os.path.isfile(f)})


def short(text, limit=160):
    text = " ".join((text or "").split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


def main(argv):
    args = argv[1:]
    allow_skipped = False
    expected_path = DEFAULT_EXPECTED_SKIPS
    patterns = []
    index = 0
    while index < len(args):
        arg = args[index]
        if arg == "--allow-skipped":
            allow_skipped = True
            index += 1
        elif arg == "--expected-skips":
            if index + 1 >= len(args):
                print("--expected-skips 需要一个文件路径", file=sys.stderr)
                return 3
            expected_path = args[index + 1]
            index += 2
        elif arg.startswith("--"):
            print(f"未知参数：{arg}", file=sys.stderr)
            return 3
        else:
            patterns.append(arg)
            index += 1

    expected = load_expected_skips(expected_path)
    files = collect_files(patterns or DEFAULT_PATTERNS)
    if not files:
        print("找不到测试结果 XML：先运行 connectedAndroidTest，或用参数显式给出 TEST-*.xml", file=sys.stderr)
        return 3

    tests = failures = errors = skipped_count = 0
    expected_skips = []
    skipped_cases = []
    failed_cases = []

    for path in files:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in suites:
            tests += int(suite.get("tests") or 0)
            failures += int(suite.get("failures") or 0)
            errors += int(suite.get("errors") or 0)
            skipped_count += int(suite.get("skipped") or 0)
            for case in suite.iter("testcase"):
                name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
                skip = case.find("skipped")
                if skip is not None:
                    entry = (name, short(skip.get("message") or skip.text))
                    (expected_skips if name in expected else skipped_cases).append(entry)
                bad = case.find("failure")
                if bad is None:
                    bad = case.find("error")
                if bad is not None:
                    failed_cases.append((name, short(bad.get("message") or bad.text)))

    print(f"结果文件：{len(files)} 个")
    print(f"合计：tests={tests} failures={failures} errors={errors} skipped={skipped_count}（其中预期跳过 {len(expected_skips)}）")
    for name, reason in failed_cases:
        print(f"[失败] {name} :: {reason}")
    for name, reason in expected_skips:
        print(f"[预期跳过] {name} :: {reason}")
    for name, reason in skipped_cases:
        print(f"[跳过] {name} :: {reason}")
    detail_count = len(skipped_cases) + len(expected_skips)
    if skipped_count > detail_count:
        print(f"[注意] 另有 {skipped_count - detail_count} 个跳过计数没有用例明细（结果文件可能被裁剪）")

    if failures or errors:
        print("结论：FAIL（存在失败用例）")
        return 1
    if skipped_cases and not allow_skipped:
        print("结论：FAIL（存在未预期的跳过用例；确认是预期跳过就加 --allow-skipped，长期保留则列入 dev/expected-skips.txt）")
        return 2
    if skipped_count:
        print("结论：PASS（跳过均为预期跳过，或被 --allow-skipped 放行）")
        return 0
    print("结论：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
