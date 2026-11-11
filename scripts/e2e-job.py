"""后台定时任务端到端回归：任务清单、手工执行与执行记录、未知任务、越权拦截。

用法：python scripts/e2e-job.py [http://localhost:8080]
"""

import json
import sys
import urllib.error
import urllib.request
import os

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PASSED = 0
FAILED = []


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as err:
        text = err.read().decode()
        try:
            return err.code, json.loads(text)
        except json.JSONDecodeError:
            return err.code, {"code": -1, "message": text}


def login(username, password=None):
    # 强制改密之后 admin 的口令可能已经变了，用 MES_PASSWORD 传入
    password = password or os.environ.get("MES_PASSWORD", "123456")
    status, res = call("POST", "/auth/login", body={"username": username, "password": password})
    return res["data"]["token"] if res.get("code") == 0 else None


def ok(payload):
    return payload.get("code") == 0


def check(name, cond, extra=""):
    global PASSED
    if cond:
        PASSED += 1
        print("  [OK]   " + name)
    else:
        FAILED.append(name)
        print("  [FAIL] " + name + "  " + str(extra))


admin = login("admin")
check("管理员登录", admin is not None)

print("\n== 任务清单 ==")
status, jobs = call("GET", "/system/jobs", admin)
rows = jobs.get("data") or []
codes = [row.get("code") for row in rows]
check("返回十个任务", len(rows) == 10, codes)
check("包含交期预警", "DELIVERY_ALERT" in codes, codes)
check("包含缺料巡检", "SHORTAGE_SCAN" in codes, codes)
check("包含成本补算", "COST_REFRESH" in codes, codes)
check("包含设备保养提醒", "DEVICE_UPKEEP" in codes, codes)
check("包含通知清理", "NOTICE_CLEANUP" in codes, codes)
check("包含离线记录清理", "OFFLINE_CLEANUP" in codes, codes)
check("包含逾期任务提醒", "TASK_OVERDUE" in codes, codes)
check("包含物料缺口预警", "MRP_ALERT" in codes, codes)
check("任务带计划与说明", all(r.get("cron") and r.get("description") for r in rows), rows)

print("\n== 手工执行 ==")
status, run = call("POST", "/system/jobs/NOTICE_CLEANUP/run", admin)
result = run.get("data") or {}
check("通知清理执行成功", result.get("status") == "SUCCESS", run)
check("返回处理条数与耗时", result.get("affected") is not None and result.get("durationMs") is not None, result)

status, run2 = call("POST", "/system/jobs/COST_REFRESH/run", admin)
check("成本补算执行成功", (run2.get("data") or {}).get("status") == "SUCCESS", run2)

print("\n== 执行记录 ==")
status, logs = call("GET", "/system/jobs/logs?code=NOTICE_CLEANUP&limit=10", admin)
log_rows = logs.get("data") or []
check("按任务过滤出记录", len(log_rows) >= 1, log_rows)
check("记录标记为手工触发", log_rows and log_rows[0].get("triggerType") == "MANUAL", log_rows[:1])
check("记录带执行人", log_rows and log_rows[0].get("operator"), log_rows[:1])

print("\n== 边界与权限 ==")
status, bad = call("POST", "/system/jobs/NOT_EXISTS/run", admin)
check("未知任务被拒", status >= 400 or bad.get("code") not in (0, None), bad)

warehouse = login("warehouse")
status, forbidden = call("GET", "/system/jobs", warehouse)
check("仓库无权查看定时任务", status == 403 or forbidden.get("code") not in (0, None), (status, forbidden))

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
