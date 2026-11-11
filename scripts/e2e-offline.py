"""移动端离线队列端到端回归：幂等键防重复、重放返回上次结果、状态接口、运维记录与清理任务。

用法：python scripts/e2e-offline.py [http://localhost:8080]
"""

import json
import sys
import urllib.error
import urllib.request
import uuid
import os

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PASSED = 0
FAILED = []


def call(method, path, token=None, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode()), dict(resp.headers)
    except urllib.error.HTTPError as err:
        text = err.read().decode()
        try:
            return err.code, json.loads(text), dict(err.headers)
        except json.JSONDecodeError:
            return err.code, {"code": -1, "message": text}, dict(err.headers)


def login(username, password=None):
    # 强制改密之后 admin 的口令可能已经变了，用 MES_PASSWORD 传入
    password = password or os.environ.get("MES_PASSWORD", "123456")
    status, res, _ = call("POST", "/auth/login", body={"username": username, "password": password})
    return res["data"]["token"] if res.get("code") == 0 else None


def ok(payload):
    return isinstance(payload, dict) and payload.get("code") == 0


def section(title):
    print("\n== " + title + " ==")


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

section("离线队列状态")
status, payload, _ = call("GET", "/offline/status", admin)
data = payload.get("data") or {}
check("状态接口返回开关", data.get("enabled") is True, payload)
check("队列上限至少 20 条", (data.get("queueLimit") or 0) >= 20, data)
check("保留天数至少 1 天", (data.get("retentionDays") or 0) >= 1, data)
check("返回服务端时间", bool(data.get("serverTime")), data)

section("准备一张可报工的工单")
status, created, _ = call("POST", "/work-orders", admin, {
    "productId": 2, "planQty": 6, "planStart": "2026-10-23", "planEnd": "2026-11-05"
})
work_id = created.get("data")
check("工单创建", ok(created) and work_id, created)
status, scheduled, _ = call("POST", f"/scheduling/{work_id}/schedule", admin, {"date": "2026-10-24"})
check("工单排产", status < 400 and ok(scheduled), scheduled)
status, released, _ = call("POST", f"/work-orders/{work_id}/release", admin)
check("工单下达", status < 400 and ok(released), released)

_, detail, _ = call("GET", f"/work-orders/{work_id}", admin)
work = detail.get("data") or {}
operations = work.get("operations") or []
check("工单有工序", bool(operations), work.get("workNo"))
op = operations[0] if operations else {}
op_id = op.get("id")
done_before = float(op.get("doneQty") or 0)

section("同一幂等键只执行一次")
key = "e2e" + uuid.uuid4().hex[:16]
report_body = {"operationId": op_id, "qty": 1, "qualifiedQty": 1, "defectQty": 0,
               "remark": "离线重传回归"}
status, first, first_headers = call("POST", f"/work-orders/{work_id}/report", admin,
                                   report_body, {"X-Idempotency-Key": key})
check("首次报工成功", status < 400 and ok(first), first)
check("首次不是重放", str(first_headers.get("X-Idempotent-Replay", "")).lower() != "true", first_headers)

_, after_first, _ = call("GET", f"/work-orders/{work_id}", admin)
done_first = float((after_first.get("data") or {}).get("operations", [{}])[0].get("doneQty") or 0)
check("报工数量已增加", done_first == done_before + 1, (done_before, done_first))

status, second, second_headers = call("POST", f"/work-orders/{work_id}/report", admin,
                                      report_body, {"X-Idempotency-Key": key})
check("重传返回相同响应", second == first, (first, second))
check("重传标记为 replay", str(second_headers.get("X-Idempotent-Replay", "")).lower() == "true",
      second_headers)

_, after_second, _ = call("GET", f"/work-orders/{work_id}", admin)
done_second = float((after_second.get("data") or {}).get("operations", [{}])[0].get("doneQty") or 0)
check("重传没有重复计数", done_second == done_first, (done_first, done_second))

section("换新键 / 无键仍正常执行")
new_key = "e2e" + uuid.uuid4().hex[:16]
status, third, _ = call("POST", f"/work-orders/{work_id}/report", admin, report_body,
                        {"X-Idempotency-Key": new_key})
check("新幂等键正常执行", status < 400 and ok(third), third)
_, after_third, _ = call("GET", f"/work-orders/{work_id}", admin)
done_third = float((after_third.get("data") or {}).get("operations", [{}])[0].get("doneQty") or 0)
check("新键再次计数", done_third == done_second + 1, (done_second, done_third))

status, fourth, _ = call("POST", f"/work-orders/{work_id}/report", admin, report_body)
check("老客户端不带幂等键仍可用", status < 400 and ok(fourth), fourth)

section("运维记录与权限")
status, recent, _ = call("GET", "/offline/recent?limit=50", admin)
rows = recent.get("data") or []
keys = [row.get("key") for row in rows]
check("重传记录里能查到幂等键", key in keys, keys[:5])
check("记录带用户与路径", any(row.get("userName") and row.get("path") for row in rows), rows[:2])

warehouse = login("warehouse")
status, forbidden, _ = call("GET", "/offline/recent", warehouse)
check("仓库无权查看重传记录", status == 403 or not ok(forbidden), (status, forbidden))

section("清理任务")
status, run, _ = call("POST", "/system/jobs/OFFLINE_CLEANUP/run", admin)
check("离线清理任务可手工执行", ok(run) and (run.get("data") or {}).get("status") == "SUCCESS", run)

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
