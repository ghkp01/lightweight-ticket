"""全流程任务指派端到端回归：订单转生产 → 算料任务 → 改派 → 完成 → 下达派工/领料。

验证的核心是一句话：每个交接点都要有人负责。
以前订单转生产之后没有任何通知，MRP 谁跑、缺口谁处理全靠人盯；
现在转生产就给计划开一条算料任务，指到人、发站内信，做完才算收口。

用法：python scripts/e2e-task-assign.py [http://localhost:8080]
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


def find_task(token, task_type, ref_no=None):
    rows = call("GET", "/tasks/my?status=OPEN", token=token).get("data") or []
    for row in rows:
        if row.get("taskType") != task_type:
            continue
        if ref_no and row.get("refNo") != ref_no:
            continue
        return row
    return None


admin = login("admin")
planner = login("planner")
warehouse = login("warehouse")
if not all([admin, planner, warehouse]):
    raise SystemExit("登录失败：admin / planner / warehouse 三个演示账号要能用初始口令登录")

# ---------------------------------------------------------------- 1. 接单转生产
section("1. 销售接单 → 确认 → 转生产")

created = call("POST", "/sales-orders", token=admin, body={
    "customerId": 1,
    "orderDate": TODAY_S,
    "deliveryDate": DELIVERY,
    "priority": "URGENT",
    "remark": "任务指派回归 " + str(int(time.time()))[-6:],
    "items": [{"productId": 2, "qty": 4, "price": 100}]
})
check("建销售订单", ok(created), created.get("message"))
if not ok(created):
    print("\n通过: %d 项" % PASSED)
    sys.exit(1)
order_id = created["data"]

check("确认订单", ok(call("POST", "/sales-orders/%d/confirm" % order_id, token=admin)))

order = call("GET", "/sales-orders/%d" % order_id, token=admin).get("data") or {}
order_no = order.get("orderNo") or order.get("order_no")
check("拿到订单号", bool(order_no), order)

to_prod = call("POST", "/sales-orders/%d/to-production" % order_id, token=admin)
check("订单转生产", ok(to_prod), to_prod.get("message"))
first_work = (to_prod.get("data") or [{}])[0]
work_no = first_work.get("workNo")
work_id = first_work.get("workId")
check("生成了工单", bool(work_no) and bool(work_id), to_prod.get("data"))

# ---------------------------------------------------------------- 2. 算料任务
section("2. 计划收到算料任务，而不是靠人想起来去跑 MRP")

mrp_task = find_task(planner, "MRP_RUN", order_no)
check("计划名下出现算料任务", mrp_task is not None,
      call("GET", "/tasks/my?status=OPEN", token=planner).get("data"))
if mrp_task:
    check("任务默认落在计划角色池（还没指到人）", not mrp_task.get("assigneeUsername"), mrp_task)
    check("任务带订单号，能直接跳单据", mrp_task.get("refNo") == order_no, mrp_task)

notices = call("GET", "/notices?limit=50", token=planner).get("data") or []
hit = [n for n in notices if order_no and order_no in str(n.get("title", "")) + str(n.get("content", ""))]
check("计划收到站内信", bool(hit), notices[:3])

# ---------------------------------------------------------------- 3. 改派到人
section("3. 管理员把算料任务指到具体某个人")

if mrp_task:
    assigned = call("POST", "/tasks/%d/assign" % mrp_task["id"], token=admin,
                    body={"username": "planner"})
    check("改派成功", ok(assigned), assigned.get("message"))
    data = assigned.get("data") or {}
    check("负责人换成 planner", data.get("assigneeUsername") == "planner", data)
    check("负责人姓名一并带出", bool(data.get("assigneeName")), data)

    assigned_notices = call("GET", "/notices?limit=50", token=planner).get("data") or []
    check("改派后本人收到提醒",
          any("任务已指派给你" in str(n.get("title", "")) for n in assigned_notices),
          assigned_notices[:3])

    # ------------------------------------------------------------ 4. 完成任务
    section("4. 计划处理完算料，任务收口")

    done = call("POST", "/tasks/%d/done" % mrp_task["id"], token=planner, body={"note": "已算料，缺口转采购"})
    check("计划能完成自己的任务", ok(done), done.get("message"))
    check("任务状态变成已完成", (done.get("data") or {}).get("status") == "DONE", done.get("data"))

    again = call("POST", "/tasks/%d/done" % mrp_task["id"], token=planner, body={"note": "重复点"})
    check("已完成的不能再点一次", not ok(again) and again.get("code") == 4001, again)

    other_role = call("POST", "/tasks/%d/done" % mrp_task["id"], token=warehouse, body={"note": "越权"})
    check("别的角色碰不到这条任务", not ok(other_role), other_role)

# ---------------------------------------------------------------- 5. 下达派工
section("5. 工单排产下达后，派工与领料也各自有人负责")

if work_id:
    call("POST", "/scheduling/%d/schedule" % work_id, token=planner, body={"date": TODAY_S})
    released = call("POST", "/work-orders/%d/release" % work_id, token=planner, body={})
    check("工单下达", ok(released), released.get("message"))

    assign_task = find_task(planner, "WO_ASSIGN", work_no)
    pick_task = find_task(warehouse, "WO_PICK", work_no)
    check("计划名下出现派工任务", assign_task is not None)
    check("仓库名下出现领料任务", pick_task is not None)

    # ------------------------------------------------------------ 6. 派工收口
    section("6. 工序全部派到人之后，派工任务自动关闭")

    detail = call("GET", "/work-orders/%d" % work_id, token=admin).get("data") or {}
    ops = detail.get("operations") or []
    check("工单有工序", bool(ops), detail.get("operations"))

    users = call("GET", "/system/users/options", token=admin).get("data") or []
    worker1 = next((u for u in users if u.get("username") == "worker1"), None)
    check("拿到演示工人", worker1 is not None)

    if worker1 and ops:
        # 有的工序要求持证，派工前先按工序取可选人员，取不到再退回演示工人
        for index, op in enumerate(ops):
            candidates = []
            if op.get("processId"):
                candidates = call("GET", "/processes/%d/skill-users" % op["processId"],
                                  token=admin).get("data") or []
            target = candidates[0] if candidates else worker1
            resp = call("POST", "/work-orders/%d/operations/%d/assignments" % (work_id, op["id"]),
                        token=planner, body={"items": [{"userId": target["id"]}]})
            if index == len(ops) - 1:
                check("最后一道工序派工成功", ok(resp), resp.get("message"))

        check("派工任务已自动关闭", find_task(planner, "WO_ASSIGN", work_no) is None)
        check("领料任务还在（料还没发）", find_task(warehouse, "WO_PICK", work_no) is not None)

print("\n================ 汇总 ================")
print("通过: %d 项" % PASSED)
print("失败: %d 项" % len(FAILED))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
