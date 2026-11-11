"""行级分流端到端回归：一张采购申请里的外协行与标准行各走各的审批链。

流程：建一张混合单（外协行 + 原辅料行）→ 提交 → 质检与计划各收到一条待办 →
质检先批（只有外协行通过，整单仍待审批）→ 计划再批（整单已批准）。

用法：python scripts/e2e-approval-line.py [http://localhost:8080]
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
NEED_DATE = (date.today() + timedelta(days=3)).isoformat()
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


admin = login("admin")
planner = login("planner")
qc = login("qc")
if not all([admin, planner, qc]):
    raise SystemExit("登录失败：admin / planner / qc 三个演示账号要能用初始口令登录")

tag = str(int(time.time()))[-6:]

# ---------------------------------------------------------------- 1. 备好两条链
section("1. 备好外协链（质检审）与兜底链（计划审）")

flows = call("GET", "/approvals/flows", token=admin).get("data") or []
for flow in flows:
    if flow.get("bizType") == "REQUISITION" and flow.get("enabled"):
        call("POST", f"/approvals/flows/{flow['id']}/toggle", token=admin, body={"enabled": False})

outsource = call("POST", "/approvals/flows", token=admin, body={
    "code": "LINE_OUT_" + tag, "name": "外协行审批（回归）", "bizType": "REQUISITION",
    "enabled": True, "priority": 300,
    "matchRules": [{"field": "CATEGORY", "op": "IN", "value": "OUTSOURCE"}],
    "nodes": [{"seq": 1, "name": "质检审核", "approverType": "ROLE",
               "approverValues": ["QC"], "multiMode": "ANY"}]
})
check("外协链已创建", ok(outsource), outsource)

fallback = call("POST", "/approvals/flows", token=admin, body={
    "code": "LINE_DEF_" + tag, "name": "采购兜底（回归）", "bizType": "REQUISITION",
    "enabled": True, "priority": 100,
    "nodes": [{"seq": 1, "name": "计划审核", "approverType": "ROLE",
               "approverValues": ["PLANNER"], "multiMode": "ANY"}]
})
check("兜底链已创建", ok(fallback), fallback)

on = call("PUT", "/approvals/biz-types/REQUISITION", token=admin, body={
    "name": "采购申请", "menuPath": "/purchase/requisitions", "mode": "FLOW"
})
check("开关切到走审批流", ok(on), on)

# ---------------------------------------------------------------- 2. 混合单
section("2. 一张单里两行不同类别，提交后各自找链")

products = call("GET", "/products", token=admin).get("data") or []
product_id = products[0]["id"] if products else 1

created = call("POST", "/purchase-requisitions", token=admin, body={
    "sourceType": "MANUAL",
    "department": "设备部",
    "needDate": NEED_DATE,
    "purpose": "行级分流回归 " + tag,
    "category": "MATERIAL",
    "items": [
        {"productId": product_id, "qty": 2, "targetPrice": 4000, "category": "OUTSOURCE"},
        {"productId": product_id, "qty": 5, "targetPrice": 100, "category": "MATERIAL"}
    ]
})
check("建混合采购申请", ok(created), created)
req_id = created.get("data")

detail = call("GET", f"/purchase-requisitions/{req_id}", token=admin).get("data") or {}
req_no = detail.get("reqNo")
items = detail.get("items") or []
check("明细行带上了各自类别",
      [row.get("category") for row in items] == ["OUTSOURCE", "MATERIAL"], items)

submitted = call("POST", f"/purchase-requisitions/{req_id}/submit", token=admin)
check("提交成功", ok(submitted), submitted.get("message"))

detail = call("GET", f"/purchase-requisitions/{req_id}", token=admin).get("data") or {}
items = detail.get("items") or []
check("单据停在待审批", detail.get("status") == "SUBMITTED", detail.get("status"))
check("两行都在等审批", all(row.get("approvalStatus") == "PENDING" for row in items),
      [row.get("approvalStatus") for row in items])

# ---------------------------------------------------------------- 3. 两条待办
section("3. 质检与计划各收到一条待办（一条链一个实例）")

qc_tasks = [row for row in (call("GET", "/approvals/tasks", token=qc).get("data") or [])
            if row.get("bizNo") == req_no]
plan_tasks = [row for row in (call("GET", "/approvals/tasks", token=planner).get("data") or [])
              if row.get("bizNo") == req_no]
check("质检看到外协行待办", len(qc_tasks) == 1, qc_tasks)
check("计划看到原辅料行待办", len(plan_tasks) == 1, plan_tasks)

bypass = call("POST", f"/purchase-requisitions/{req_id}/approve", token=admin)
check("审批中不能绕过审批直接批", not ok(bypass) and bypass.get("code") == 2015, bypass)

# ---------------------------------------------------------------- 4. 先批一组
section("4. 质检先批：只有外协行通过，整单还不能放行")

if qc_tasks:
    call("POST", f"/approvals/tasks/{qc_tasks[0]['id']}/approve", token=qc, body={"comment": "外协件合格"})

detail = call("GET", f"/purchase-requisitions/{req_id}", token=admin).get("data") or {}
items = {row["category"]: row for row in detail.get("items") or []}
check("外协行已通过", items.get("OUTSOURCE", {}).get("approvalStatus") == "APPROVED",
      items.get("OUTSOURCE"))
check("原辅料行仍在审", items.get("MATERIAL", {}).get("approvalStatus") == "PENDING",
      items.get("MATERIAL"))
check("整单仍在待审批", detail.get("status") == "SUBMITTED", detail.get("status"))

# ---------------------------------------------------------------- 5. 审批中先转已批的行
section("5. 审批中就能把已批准的行转采购单")

suppliers = call("GET", "/suppliers", token=admin).get("data") or []
warehouses = call("GET", "/warehouses", token=admin).get("data") or []
raw = next((row for row in warehouses if row.get("type") == "RAW"), (warehouses or [{}])[0])
material_line = items.get("MATERIAL", {}).get("id")

if suppliers and raw.get("id"):
    blocked = call("POST", f"/purchase-requisitions/{req_id}/to-order", token=admin, body={
        "supplierId": suppliers[0]["id"], "warehouseId": raw["id"],
        "items": [{"requisitionItemId": material_line, "qty": 5, "price": 100}]
    })
    check("未审批的行不能转采购单", not ok(blocked) and blocked.get("code") == 2014, blocked)

    converted = call("POST", f"/purchase-requisitions/{req_id}/to-order", token=admin, body={
        "supplierId": suppliers[0]["id"], "warehouseId": raw["id"], "needInspection": True
    })
    check("已批准的行可以转采购单", ok(converted), converted)
    check("生成了采购单号", bool((converted.get("data") or {}).get("orderNo")), converted)

    detail = call("GET", f"/purchase-requisitions/{req_id}", token=admin).get("data") or {}
    check("转单后整单仍是待审批（还有行没批）", detail.get("status") == "SUBMITTED",
          detail.get("status"))
else:
    check("没有供应商或仓库数据，跳过转单段", False, "演示数据缺失")

# ---------------------------------------------------------------- 6. 再批一组
section("6. 计划再批：全部行通过，已转部分 → 部分转单")

if plan_tasks:
    call("POST", f"/approvals/tasks/{plan_tasks[0]['id']}/approve", token=planner, body={"comment": "同意"})
    detail = call("GET", f"/purchase-requisitions/{req_id}", token=admin).get("data") or {}
    check("所有行都通过",
          all(row.get("approvalStatus") == "APPROVED" for row in detail.get("items") or []),
          [row.get("approvalStatus") for row in detail.get("items") or []])
    check("已转一部分 → 部分转单", detail.get("status") == "PARTIAL", detail.get("status"))
    check("审批来源标为审批流", detail.get("approvedBy") == "审批流", detail.get("approvedBy"))

# ---------------------------------------------------------------- 7. 转完剩余
section("7. 把剩余行也转掉，整单变成已转单")

if suppliers and raw.get("id"):
    rest = call("POST", f"/purchase-requisitions/{req_id}/to-order", token=admin, body={
        "supplierId": suppliers[0]["id"], "warehouseId": raw["id"], "needInspection": True
    })
    check("剩余行转单成功", ok(rest), rest)
    detail = call("GET", f"/purchase-requisitions/{req_id}", token=admin).get("data") or {}
    check("整单变成已转单", detail.get("status") == "CONVERTED", detail.get("status"))

# ---------------------------------------------------------------- 8. 复原
section("8. 复原配置")

call("PUT", "/approvals/biz-types/REQUISITION", token=admin, body={
    "name": "采购申请", "menuPath": "/purchase/requisitions", "mode": "THRESHOLD"
})
for row in [outsource, fallback]:
    flow_id = (row.get("data") or {}).get("id")
    if flow_id:
        call("POST", f"/approvals/flows/{flow_id}/toggle", token=admin, body={"enabled": False})
check("审批链已停用、开关恢复阈值模式", True)

print("\n================ 汇总 ================")
print("通过: %d 项" % PASSED)
print("失败: %d 项" % len(FAILED))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
