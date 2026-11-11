"""一键跑完所有 e2e 脚本，按依赖顺序执行并汇总通过/失败。

用法（口令只放在环境变量里，别写进文件）：
    # PowerShell
    $env:MES_PASSWORD='你的 admin 口令'
    python scripts/run-all-e2e.py

    # 只跑某几个
    python scripts/run-all-e2e.py e2e-cancel,e2e-outsource

说明：
- 先跑 e2e-full 造出工单/领料/报工数据，再跑 outsourse / issue 这些依赖在制工单的专项；
- e2e-cancel 自带数据、只用到 sales/planner/qc 三个岗位账号，不依赖 admin 口令；
- 建议先 reset 一次（scripts/reset-demo-data.sql），否则排产窗口会被历史草稿单占满。
"""
import os
import re
import subprocess
import sys
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# 顺序有讲究：e2e-full 先造数据，依赖「现有工单」的专项紧随其后，其余可任意顺序
ORDER = [
    "e2e-full", "e2e-units", "e2e-outsource", "e2e-issue", "e2e-cancel", "e2e-trace",
    "e2e-report-policy", "e2e-task-assign",
    "e2e-routing-init", "e2e-org", "e2e-capacity", "e2e-aps-substitute",
    "e2e-mrp", "e2e-mrp-multi", "e2e-quality", "e2e-inbound", "e2e-purchase",
    "e2e-product-supplier",
    "e2e-requisition", "e2e-rfq", "e2e-ap", "e2e-supplier-performance",
    "e2e-device", "e2e-location", "e2e-approval", "e2e-approval-switch", "e2e-approval-route",
    "e2e-approval-line", "e2e-notice", "e2e-job",
    "e2e-offline", "e2e-factory", "e2e-files", "e2e-export", "e2e-data",
    "e2e-items", "e2e-views", "e2e-screen", "e2e-cost", "e2e-performance",
    "e2e-security", "e2e-readiness",
]


def scripts_to_run():
    if len(sys.argv) > 1:
        return [name.strip() for name in sys.argv[1].split(",") if name.strip()]
    return ORDER


def main():
    names = scripts_to_run()
    missing = [n for n in names if not os.path.exists(os.path.join(SCRIPT_DIR, n + ".py"))]
    if missing:
        raise SystemExit("找不到脚本：" + "、".join(missing))

    if not os.environ.get("MES_PASSWORD"):
        print("提示：没有设置 MES_PASSWORD，脚本会用默认初始口令登录；admin 改过口令时请先设置它。\n")

    rows = []
    for name in names:
        started = time.time()
        proc = subprocess.run(
            [sys.executable, os.path.join(SCRIPT_DIR, name + ".py")],
            capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=SCRIPT_DIR)
        out = (proc.stdout or "") + (proc.stderr or "")
        fails = [line.strip() for line in out.splitlines() if "[FAIL]" in line]
        match = re.search(r"通过[:：]?\s*(\d+)\s*项", out) or re.search(r"(\d+)\s*项通过", out)
        passed = int(match.group(1)) if match else 0
        rows.append((name, passed, len(fails), round(time.time() - started, 1), proc.returncode))
        print("%-26s 通过=%-4d 失败=%-3d %5.1fs" % (name, passed, len(fails), rows[-1][3]), flush=True)
        for line in fails[:6]:
            print("     " + line[:160], flush=True)

    bad = [r for r in rows if r[2] > 0 or r[4] != 0]
    print("\n================ 汇总 ================")
    print("脚本 %d 个，断言 %d 项通过，失败脚本 %d 个" % (len(rows), sum(r[1] for r in rows), len(bad)))
    for row in bad:
        print("  %s  失败 %d 项" % (row[0], row[2]))
    if bad:
        sys.exit(1)


if __name__ == "__main__":
    main()
