"""工序报工权限端到端回归：建单时派工 + 「只有派工人员能报」。

流程：管理员建单时按工序派工（第 1 道限定只有 worker1 能报，第 2 道所有人可报），
排产下达后验证：计划员报第 1 道被拦、管理员可代办、worker1 能报、第 2 道谁都能报，
以及派工弹窗能把权限改回「所有人可报」。

用法：python scripts/e2e-report-policy.py [http://localhost:8080]
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
TODAY = date.today()
TODAY_S = TODAY.isoformat()
DELIVERY = (TODAY + timedelta(days=5)).isoformat()
PASSED = 0
FAILED = []


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as err:
        return json.loads(err.read().decode() or "{}")


def login(username, password=None):
    # 强制改密之后口令可能已经变了，用 MES_PASSWORD 传入
    password = password or os.environ.get("MES_PASSWORD", "123456")
    res = call("POST", "/auth/login", body={"username": username, "password": password})
    return res["data"]["token"] if res.get("code") == 0 else None


def ok(payload):
    return isinstance(payload, dict) and payload.get("code") == 0


def check(name, condition, detail=""):
    global PASSED
    if condition:
        PASSED += 1
        print("  [OK]   " + name)
    else:
        FAILED.append(name)
        print("  [FAIL] " + name + ("  " + str(detail) if detail else ""))


def section(title):
    print("\n== " + title + " ==")


admin = login("admin")
planner = login("planner")
worker1 = login("worker1")
worker2 = login("worker2")
if not all([admin, planner, worker1, worker2]):
    raise SystemExit("登录失败：admin / planner / worker1 / worker2 四个演示账号要能用初始口令登录")

section("1. 建单时按工序派工")
users = call("GET", "/system/users/options", token=admin)["data"]
worker1_id = next(u["id"] for u in users if u["username"] == "worker1")
worker2_id = next(u["id"] for u in users if u["username"] == "worker2")
worker1_name = next(u["realName"] for u in users if u["username"] == "worker1")
worker2_name = next(u["realName"] for u in users if u["username"] == "worker2")

created = call("POST", "/work-orders", token=admin, body={
    "productId": 2, "planQty": 4, "planStart": TODAY_S, "planEnd": DELIVERY,
    "priority": "NORMAL", "remark": "报工权限回归 " + str(int(time.time()))[-6:],
    "assignments": [
        {"seq": 1, "userId": worker1_id, "reportPolicy": "ASSIGNED"},
        {"seq": 2, "userId": worker2_id, "reportPolicy": "ANY"}
    ]
})
check("建单带派工", ok(created), created.get("message"))
if not ok(created):
    print("\n通过: %d 项" % PASSED)
    sys.exit(1)
work_id = created["data"]

detail = call("GET", "/work-orders/%d" % work_id, token=admin)["data"]
ops = {op["seq"]: op for op in detail.get("operations") or []}
check("建单即完成第 1 道派工", ops[1].get("assignedUserId") == worker1_id, ops[1].get("assignedWorker"))
check("第 1 道权限为仅他可报", ops[1].get("reportPolicy") == "ASSIGNED", ops[1].get("reportPolicy"))
check("第 2 道派给另一人但所有人可报",
      ops[2].get("assignedUserId") == worker2_id and ops[2].get("reportPolicy") == "ANY",
      (ops[2].get("assignedWorker"), ops[2].get("reportPolicy")))

section("2. 派工弹窗可改报工权限")
changed = call("POST", "/work-orders/%d/operations/%d/assign" % (work_id, ops[1]["id"]), token=admin, body={
    "userId": worker1_id, "reportPolicy": "ANY"
})
check("权限改回所有人可报", ok(changed) and changed["data"].get("reportPolicy") == "ANY", changed)
back = call("POST", "/work-orders/%d/operations/%d/assign" % (work_id, ops[1]["id"]), token=admin, body={
    "userId": worker1_id, "reportPolicy": "ASSIGNED"
})
check("再改回仅他可报", ok(back) and back["data"].get("reportPolicy") == "ASSIGNED", back)
bad = call("POST", "/work-orders/%d/operations/%d/assign" % (work_id, ops[1]["id"]), token=admin, body={
    "userId": worker1_id, "reportPolicy": "BOSS_ONLY"
})
check("非法权限值被拒", not ok(bad), bad.get("message"))

section("3. 下达后按权限报工")
call("POST", "/scheduling/%d/schedule" % work_id, token=planner, body={"date": TODAY_S})
released = call("POST", "/work-orders/%d/release" % work_id, token=planner, body={})
check("工单已下达", ok(released), released.get("message"))

blocked = call("POST", "/work-orders/%d/report" % work_id, token=planner, body={
    "operationId": ops[1]["id"], "qty": 1, "defectQty": 0, "worker": "计划员代报"
})
check("非派工人员报工被拦", not ok(blocked) and blocked.get("code") == 2024, blocked)
check("提示点名只允许谁报", "只允许" in str(blocked.get("message")), blocked.get("message"))

bypassed = call("POST", "/work-orders/%d/report" % work_id, token=admin, body={
    "operationId": ops[1]["id"], "qty": 1, "defectQty": 0, "worker": "管理员代办"
})
check("管理员可代办报工", ok(bypassed), bypassed.get("message"))

assigned = call("POST", "/work-orders/%d/report" % work_id, token=worker1, body={
    "operationId": ops[1]["id"], "qty": 3, "defectQty": 0, "worker": worker1_name
})
check("派工人员本人能报", ok(assigned), assigned.get("message"))

anyone = call("POST", "/work-orders/%d/report" % work_id, token=planner, body={
    "operationId": ops[2]["id"], "qty": 4, "defectQty": 0, "worker": "计划员"
})
check("未限制的工序谁都能报", ok(anyone), anyone.get("message"))

final = call("GET", "/work-orders/%d" % work_id, token=admin)["data"]
final_ops = {op["seq"]: op for op in final.get("operations") or []}
check("第 1 道累计报工 4 件", float(final_ops[1].get("doneQty") or 0) == 4.0, final_ops[1].get("doneQty"))
check("第 2 道累计报工 4 件", float(final_ops[2].get("doneQty") or 0) == 4.0, final_ops[2].get("doneQty"))

section("4. 多人分配：按人卡配额")
# 用第 2 道（折弯）做多人分配：它没配持证要求，两个演示工人都能派；
# 第 1 道先报满，免得被工序防差错挡住
multi = call("POST", "/work-orders", token=admin, body={
    "productId": 2, "planQty": 4, "planStart": TODAY_S, "planEnd": DELIVERY,
    "priority": "NORMAL", "remark": "多人分配回归 " + str(int(time.time()))[-6:],
    "assignments": [
        {"seq": 2, "userId": worker1_id, "quotaQty": 2},
        {"seq": 2, "userId": worker2_id, "quotaQty": 2}
    ]
})
check("建单时给同一道工序分两个人", ok(multi), multi.get("message"))
if ok(multi):
    multi_id = multi["data"]
    multi_detail = call("GET", "/work-orders/%d" % multi_id, token=admin)["data"]
    multi_ops = {op["seq"]: op for op in multi_detail.get("operations") or []}
    multi_op = multi_ops[2]
    check("分配名单两条", len(multi_op.get("assignments") or []) == 2, multi_op.get("assignments"))
    check("两人各分 2 件",
          sorted(float(row["quotaQty"]) for row in multi_op.get("assignments") or []) == [2.0, 2.0],
          multi_op.get("assignments"))

    call("POST", "/scheduling/%d/schedule" % multi_id, token=planner, body={"date": TODAY_S})
    released_multi = call("POST", "/work-orders/%d/release" % multi_id, token=planner, body={})
    check("多人分配的工单已下达", ok(released_multi), released_multi.get("message"))
    call("POST", "/work-orders/%d/report" % multi_id, token=admin, body={
        "operationId": multi_ops[1]["id"], "qty": 4, "defectQty": 0, "worker": "管理员代办"
    })

    over = call("POST", "/work-orders/%d/report" % multi_id, token=worker1, body={
        "operationId": multi_op["id"], "qty": 3, "defectQty": 0, "worker": worker1_name
    })
    check("超过自己的分配量被拦", not ok(over) and over.get("code") == 2025, over)

    outsider2 = call("POST", "/work-orders/%d/report" % multi_id, token=planner, body={
        "operationId": multi_op["id"], "qty": 1, "defectQty": 0, "worker": "计划员"
    })
    check("名单外的人报工被拦", not ok(outsider2) and outsider2.get("code") == 2025, outsider2)

    first_half = call("POST", "/work-orders/%d/report" % multi_id, token=worker1, body={
        "operationId": multi_op["id"], "qty": 2, "defectQty": 0, "worker": worker1_name
    })
    check("worker1 报满自己的 2 件", ok(first_half), first_half.get("message"))
    second_half = call("POST", "/work-orders/%d/report" % multi_id, token=worker2, body={
        "operationId": multi_op["id"], "qty": 2, "defectQty": 0, "worker": worker2_name
    })
    check("worker2 报满自己的 2 件", ok(second_half), second_half.get("message"))

    again = call("POST", "/work-orders/%d/report" % multi_id, token=worker1, body={
        "operationId": multi_op["id"], "qty": 1, "defectQty": 0, "worker": worker1_name
    })
    check("配额用完后不能再报", not ok(again) and again.get("code") == 2025, again)

    multi_after = call("GET", "/work-orders/%d" % multi_id, token=admin)["data"]
    multi_after_op = {op["seq"]: op for op in multi_after.get("operations") or []}[2]
    reported = sorted(float(row["reportedQty"]) for row in multi_after_op.get("assignments") or [])
    check("两人各自累计已报 2 件", reported == [2.0, 2.0], reported)
    check("工序累计报工 4 件", float(multi_after_op.get("doneQty") or 0) == 4.0, multi_after_op.get("doneQty"))

    cleared = call("POST", "/work-orders/%d/operations/%d/assignments" % (multi_id, multi_op["id"]),
                   token=admin, body={"items": []})
    check("清空分配名单", ok(cleared) and not (cleared.get("data") or {}).get("assignments"), cleared)

print("\n================ 汇总 ================")
print("通过: %d 项" % PASSED)
print("失败: %d 项" % len(FAILED))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)

