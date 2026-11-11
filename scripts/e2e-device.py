"""设备点检保养端到端回归：任务生成、点检提交与撤销、保养滚动、报修与关闭、越权。

用法：python scripts/e2e-device.py [http://localhost:8080]
"""

import json
import sys
import time
import urllib.error
import urllib.request
import os

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PASSED = 0
FAILED = []
TAG = str(int(time.time()))[-6:]
TODAY = time.strftime("%Y-%m-%d")
YESTERDAY = time.strftime("%Y-%m-%d", time.localtime(time.time() - 86400))


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

print("\n== 设备与点检项 ==")
status, devices = call("GET", "/devices", admin)
device_rows = devices.get("data") or []
check("设备台账可读", status == 200 and ok(devices) and len(device_rows) > 0, len(device_rows))
device = device_rows[0] if device_rows else {}
device_id = device.get("id")
check("设备带编码与工作中心", device.get("code") and device.get("workCenterName"), device.get("code"))

status, created_item = call("POST", "/device-maintenance/check-items", admin, {
    "deviceId": device_id, "name": "回归点检项" + TAG, "method": "目视",
    "standard": "无异常", "sort": 1, "enabled": True
})
check("新增点检项", ok(created_item) and (created_item.get("data") or {}).get("id"), created_item)
item_id = ((created_item.get("data") or {}).get("id") or 0)

status, weak = call("POST", "/device-maintenance/check-items", admin, {
    "deviceId": device_id, "name": " ", "method": "目视"
})
check("点检项名称必填", weak.get("code") == 1002, weak)

status, items = call("GET", f"/device-maintenance/check-items?deviceId={device_id}", admin)
item_rows = items.get("data") or []
check("按设备查点检项包含刚建的那条",
      any(row.get("name") == "回归点检项" + TAG for row in item_rows), len(item_rows))

print("\n== 点检任务 ==")
status, generated = call("POST", f"/device-maintenance/checks/generate?date={TODAY}", admin)
check("生成当日点检任务", ok(generated) and (generated.get("data") or {}).get("created") is not None, generated)

status, checks = call("GET", f"/device-maintenance/checks?date={TODAY}&deviceId={device_id}", admin)
task_rows = checks.get("data") or []
check("当天该设备有点检任务", len(task_rows) == 1, len(task_rows))
task = task_rows[0] if task_rows else {}
check("任务带任务号与状态", task.get("taskNo") and task.get("status"), task.get("taskNo"))

status, detail = call("GET", f"/device-maintenance/checks/{task.get('id')}", admin)
detail_data = detail.get("data") or {}
pending = detail_data.get("items") or []
check("未点检时返回待点检清单", len(pending) >= 1, len(pending))

status, submitted = call("POST", f"/device-maintenance/checks/{task.get('id')}/submit", admin, {
    "results": [
        {"itemId": item_id, "itemName": "回归点检项" + TAG, "method": "目视",
         "standard": "无异常", "result": "OK", "remark": ""}
    ],
    "remark": "回归点检"
})
result = submitted.get("data") or {}
check("提交点检成功", ok(submitted) and result.get("items") == 1, submitted)

status, after = call("GET", f"/device-maintenance/checks/{task.get('id')}", admin)
check("提交后状态为已点检", (after.get("data") or {}).get("status") == "DONE", after.get("data", {}).get("status"))

status, reset = call("POST", f"/device-maintenance/checks/{task.get('id')}/reset", admin)
check("撤销点检", ok(reset), reset)
status, after_reset = call("GET", f"/device-maintenance/checks/{task.get('id')}", admin)
check("撤销后回到待点检", (after_reset.get("data") or {}).get("status") == "PENDING",
      after_reset.get("data", {}).get("status"))

print("\n== 保养计划 ==")
status, plan = call("POST", "/device-maintenance/plans", admin, {
    "deviceId": device_id, "name": "回归保养" + TAG, "cycleDays": 1,
    "lastDate": YESTERDAY, "standard": "按手册", "enabled": True
})
plan_id = (plan.get("data") or {}).get("id")
check("新增保养计划", ok(plan) and plan_id, plan)

status, due = call("GET", "/device-maintenance/plans?dueOnly=true", admin)
check("到期计划里能查到", any(row.get("id") == plan_id for row in (due.get("data") or [])),
      [row.get("name") for row in (due.get("data") or [])][:5])

status, executed = call("POST", f"/device-maintenance/plans/{plan_id}/execute", admin, {
    "doneDate": TODAY, "worker": "回归", "cost": 66.5, "remark": "回归保养登记"
})
executed_data = executed.get("data") or {}
check("登记保养并按周期推下次日期", ok(executed) and executed_data.get("nextDate") > TODAY, executed)

status, logs = call("GET", f"/device-maintenance/plan-logs?planId={plan_id}", admin)
log_rows = logs.get("data") or []
check("保养记录已落库", len(log_rows) == 1 and float(log_rows[0].get("cost") or 0) == 66.5, log_rows)

status, due_after = call("GET", "/device-maintenance/plans?dueOnly=true", admin)
after_rows = [row for row in (due_after.get("data") or []) if row.get("id") == plan_id]
check("保养后不再逾期（下次日期已推到明天）",
      after_rows and after_rows[0].get("overdue") is False and int(after_rows[0].get("daysLeft") or 0) >= 1,
      after_rows)

print("\n== 故障与停机 ==")
status, fault = call("POST", "/device-maintenance/faults", admin, {"deviceId": device_id, "symptom": "回归故障"})
fault_id = (fault.get("data") or {}).get("id")
check("报修成功", ok(fault) and fault_id, fault)

status, faults = call("GET", "/device-maintenance/faults?status=OPEN", admin)
open_rows = faults.get("data") or []
mine = [row for row in open_rows if row.get("id") == fault_id]
check("故障列表可查到待处理", len(mine) == 1, len(open_rows))

status, devices_after = call("GET", "/devices", admin)
current = next((row for row in (devices_after.get("data") or []) if row.get("id") == device_id), {})
check("报修后设备置为停机", current.get("status") == "OFFLINE", current.get("status"))

status, closed = call("POST", f"/device-maintenance/faults/{fault_id}/close", admin, {
    "cause": "回归原因", "action": "已处理", "handler": "回归"
})
closed_data = closed.get("data") or {}
check("关闭故障并算停机时长",
      ok(closed) and closed_data.get("status") == "CLOSED" and closed_data.get("downtimeMinutes") is not None,
      closed)

status, devices_final = call("GET", "/devices", admin)
final_device = next((row for row in (devices_final.get("data") or []) if row.get("id") == device_id), {})
check("关闭后设备恢复待机", final_device.get("status") == "IDLE", final_device.get("status"))

status, summary = call("GET", "/device-maintenance/summary", admin)
summary_data = summary.get("data") or {}
check("概览含今日点检与故障统计",
      all(key in summary_data for key in ("todayTasks", "todayDone", "maintainDue", "openFaults")),
      list(summary_data.keys()))

print("\n== 越权 ==")
qc = login("qc")
status, forbidden = call("GET", "/device-maintenance/checks", qc)
check("质检无权维护设备点检", status == 403 or forbidden.get("code") not in (0, None), (status, forbidden))

print("\n== 清理测试数据 ==")
call("DELETE", f"/device-maintenance/plans/{plan_id}", admin)
call("DELETE", f"/device-maintenance/check-items/{item_id}", admin)

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
