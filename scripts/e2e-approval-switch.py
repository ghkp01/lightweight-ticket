"""审批开关端到端回归：配了就审批，不配就直接生效。

覆盖三档强度：FLOW 走审批流（审批中心批完回写单据）、NONE 提交即生效、
THRESHOLD 只按金额阈值；另验越权改开关被拦、在审批流里的单据不能绕过审批直接批。

用法：python scripts/e2e-approval-switch.py [http://localhost:8080]
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
NEED_DATE = (TODAY + timedelta(days=3)).isoformat()
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


def biz_row(token, biz_type="REQUISITION"):
    rows = call("GET", "/approvals/biz-types", token=token).get("data") or []
    return next((row for row in rows if row.get("bizType") == biz_type), None)


def set_mode(token, mode):
    row = biz_row(token) or {"name": "采购申请", "menuPath": "/purchase/requisitions"}
    return call("PUT", "/approvals/biz-types/REQUISITION", token=token, body={
        "name": row.get("name") or "采购申请",
        "menuPath": row.get("menuPath") or "/purchase/requisitions",
        "mode": mode,
        "remark": row.get("remark")
    })


def create_requisition(token, amount):
    products = call("GET", "/products?size=1", token=token).get("data") or {}
    records = products.get("records") if isinstance(products, dict) else products
    product_id = records[0]["id"] if records else 1
    created = call("POST", "/purchase-requisitions", token=token, body={
        "sourceType": "MANUAL",
        "department": "设备部",
        "needDate": NEED_DATE,
        "purpose": "审批开关回归 " + str(int(time.time()))[-6:],
        "remark": "e2e-approval-switch",
        "items": [{"productId": product_id, "qty": 2, "targetPrice": amount}]
    })
    return created.get("data") if ok(created) else None


admin = login("admin")
planner = login("planner")
warehouse = login("warehouse")
if not all([admin, planner, warehouse]):
    raise SystemExit("登录失败：admin / planner / warehouse 三个演示账号要能用初始口令登录")

# ---------------------------------------------------------------- 1. 开关一览
section("1. 开关一览：默认强度与菜单绑定")

original = biz_row(admin)
check("采购申请已登记在开关列表里", original is not None)
check("采购申请默认只按金额阈值", (original or {}).get("mode") == "THRESHOLD", original)
original_mode = (original or {}).get("mode") or "THRESHOLD"

# ---------------------------------------------------------------- 2. 越权
section("2. 非管理员不能改开关")

forbidden = call("PUT", "/approvals/biz-types/REQUISITION", token=warehouse, body={"mode": "NONE"})
check("仓库改开关被拦", not ok(forbidden), forbidden)

# ---------------------------------------------------------------- 3. 配一条流程
section("3. 给采购申请配一条审批流")

flows = call("GET", "/approvals/flows", token=admin).get("data") or []
# 分流用的链会带「只命中外协」这类条件，开关用例需要一条不限条件的链，所以自己建一条
for flow in flows:
    if flow.get("bizType") == "REQUISITION" and flow.get("enabled"):
        call("POST", f"/approvals/flows/{flow['id']}/toggle", token=admin, body={"enabled": False})
tag = str(int(time.time()))[-6:]
created = call("POST", "/approvals/flows", token=admin, body={
    "code": "REQ_SWITCH_" + tag,
    "name": "采购申请审批（开关回归）",
    "bizType": "REQUISITION",
    "enabled": True,
    "remark": "e2e-approval-switch 自动建",
    "priority": 50,
    "nodes": [{"seq": 1, "name": "计划审核", "approverType": "ROLE",
               "approverValues": ["PLANNER"], "multiMode": "ANY"}]
})
check("新建采购申请审批流", ok(created), created)
flow_id = (created.get("data") or {}).get("id")
created_flow_id = flow_id

turned_on = set_mode(admin, "FLOW")
check("开关切到走审批流", ok(turned_on), turned_on)
after = biz_row(admin) or {}
check("开关显示已绑定流程", after.get("mode") == "FLOW" and after.get("flowId"), after)
check("没有「开了但没流程」的提醒", "还没有可用流程" not in str(after.get("hint")), after.get("hint"))

# ---------------------------------------------------------------- 4. 提交进审批
section("4. 提交后进入审批流，计划收到待办")

req_id = create_requisition(admin, 1200)
check("建采购申请", req_id is not None)
detail = (call("GET", f"/purchase-requisitions/{req_id}", token=admin).get("data") or {})
req_no = detail.get("reqNo")
check("提交前是草稿", detail.get("status") == "DRAFT", detail.get("status"))

submitted = call("POST", f"/purchase-requisitions/{req_id}/submit", token=admin)
check("提交成功", ok(submitted), submitted.get("message"))
detail = (call("GET", f"/purchase-requisitions/{req_id}", token=admin).get("data") or {})
check("单据停在待审批", detail.get("status") == "SUBMITTED", detail.get("status"))

tasks = call("GET", "/approvals/tasks", token=planner).get("data") or []
task = next((row for row in tasks if row.get("bizNo") == req_no), None)
check("计划在审批中心看到待办", task is not None, tasks[:3])

# ---------------------------------------------------------------- 5. 绕过审批被拦
section("5. 在审批流里的单据不能绕过审批直接批")

bypass = call("POST", f"/purchase-requisitions/{req_id}/approve", token=admin)
check("直接调审批接口被拦", not ok(bypass) and bypass.get("code") == 2015, bypass)

# ---------------------------------------------------------------- 6. 审批中心批
section("6. 审批中心批完，状态回写单据")

if task:
    approved = call("POST", f"/approvals/tasks/{task['id']}/approve", token=planner,
                    body={"comment": "同意，按计划采购"})
    check("计划审批通过", ok(approved), approved)
    detail = (call("GET", f"/purchase-requisitions/{req_id}", token=admin).get("data") or {})
    check("单据变成已批准", detail.get("status") == "APPROVED", detail.get("status"))
    check("审批来源标为审批流", detail.get("approvedBy") == "审批流", detail.get("approvedBy"))

# ---------------------------------------------------------------- 7. 关掉审批
section("7. 关掉审批后提交即生效")

off = set_mode(admin, "NONE")
check("开关切到免审批", ok(off), off)

req2 = create_requisition(admin, 1200)
check("再建一张申请", req2 is not None)
call("POST", f"/purchase-requisitions/{req2}/submit", token=admin)
detail2 = (call("GET", f"/purchase-requisitions/{req2}", token=admin).get("data") or {})
check("提交即生效", detail2.get("status") == "APPROVED", detail2.get("status"))
check("来源标为免审批", detail2.get("approvedBy") == "免审批", detail2.get("approvedBy"))

tasks_after = call("GET", "/approvals/tasks", token=planner).get("data") or []
check("免审批的单不产生审批待办",
      all(row.get("bizNo") != detail2.get("reqNo") for row in tasks_after))

# ---------------------------------------------------------------- 8. 复原
section("8. 复原配置")

restored = set_mode(admin, original_mode)
check("开关恢复原强度", ok(restored), restored)
if created_flow_id:
    removed = call("DELETE", f"/approvals/flows/{created_flow_id}", token=admin)
    if ok(removed):
        check("清理本轮新建的流程", True)
    else:
        # 已经有审批记录时删不掉，停用即可：下次运行会复用并重新启用
        disabled = call("POST", f"/approvals/flows/{created_flow_id}/toggle", token=admin,
                        body={"enabled": False})
        check("清理本轮新建的流程（改为停用）", ok(disabled), disabled)

print("\n================ 汇总 ================")
print("通过: %d 项" % PASSED)
print("失败: %d 项" % len(FAILED))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
