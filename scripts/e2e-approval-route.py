"""审批分流端到端回归：同一业务类型挂多条链，按采购类别/金额命中不同审批人。

覆盖：试算命中、外协单走外协链（质检审）、原辅料单落兜底链（计划审）、
以及「命不中就没有审批」这条兜底语义。

用法：python scripts/e2e-approval-route.py [http://localhost:8080]
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

# ---------------------------------------------------------------- 1. 备好现场
section("1. 清掉采购申请上的旧链，避免与本轮规则打架")

flows = call("GET", "/approvals/flows", token=admin).get("data") or []
for flow in flows:
    if flow.get("bizType") == "REQUISITION" and flow.get("enabled"):
        call("POST", f"/approvals/flows/{flow['id']}/toggle", token=admin, body={"enabled": False})
check("采购申请暂时没有启用中的审批链", True)

# ---------------------------------------------------------------- 2. 挂两条链
section("2. 外协走质检链、其余走计划兜底链")

outsource_flow = call("POST", "/approvals/flows", token=admin, body={
    "code": "REQ_OUT_" + tag,
    "name": "外协件审批（回归）",
    "bizType": "REQUISITION",
    "enabled": True,
    "remark": "e2e-approval-route 自动建",
    "priority": 300,
    "matchNote": "外协件要过质检",
    "matchRules": [{"field": "CATEGORY", "op": "IN", "value": "OUTSOURCE"}],
    "nodes": [{"seq": 1, "name": "质检审核", "approverType": "ROLE",
               "approverValues": ["QC"], "multiMode": "ANY"}]
})
check("外协链已创建", ok(outsource_flow), outsource_flow)
outsource_id = (outsource_flow.get("data") or {}).get("id")

fallback_flow = call("POST", "/approvals/flows", token=admin, body={
    "code": "REQ_DEF_" + tag,
    "name": "采购申请兜底（回归）",
    "bizType": "REQUISITION",
    "enabled": True,
    "remark": "e2e-approval-route 自动建",
    "priority": 100,
    "matchNote": "默认计划审",
    "nodes": [{"seq": 1, "name": "计划审核", "approverType": "ROLE",
               "approverValues": ["PLANNER"], "multiMode": "ANY"}]
})
check("兜底链已创建", ok(fallback_flow), fallback_flow)
fallback_id = (fallback_flow.get("data") or {}).get("id")

second_fallback = call("POST", "/approvals/flows", token=admin, body={
    "code": "REQ_DEF2_" + tag,
    "name": "第二条兜底（应被拒）",
    "bizType": "REQUISITION",
    "enabled": True,
    "priority": 200,
    "nodes": [{"seq": 1, "name": "计划审核", "approverType": "ROLE",
               "approverValues": ["PLANNER"], "multiMode": "ANY"}]
})
check("同一业务类型不允许第二条兜底", not ok(second_fallback), second_fallback)

# ---------------------------------------------------------------- 3. 试算
section("3. 试算：先看会命中哪条，再真提交")

sim_out = call("POST", "/approvals/flows/simulate", token=admin, body={
    "bizType": "REQUISITION",
    "variables": {"category": "OUTSOURCE", "amount": 8000, "urgent": False}
})
check("外协单试算命中外协链",
      (sim_out.get("data") or {}).get("flowCode") == "REQ_OUT_" + tag, sim_out.get("data"))

sim_mat = call("POST", "/approvals/flows/simulate", token=admin, body={
    "bizType": "REQUISITION",
    "variables": {"category": "MATERIAL", "amount": 800}
})
check("原辅料单试算命中兜底链",
      (sim_mat.get("data") or {}).get("flowCode") == "REQ_DEF_" + tag, sim_mat.get("data"))

traces = (sim_mat.get("data") or {}).get("traces") or []
check("试算给出了每条链的求值明细",
      len(traces) == 2 and all("conditions" in row for row in traces), traces)

# ---------------------------------------------------------------- 4. 打开审批
section("4. 开关切到走审批流")

on = call("PUT", "/approvals/biz-types/REQUISITION", token=admin, body={
    "name": "采购申请", "menuPath": "/purchase/requisitions", "mode": "FLOW"
})
check("开关切到走审批流", ok(on), on)

# ---------------------------------------------------------------- 5. 外协单
section("5. 外协单走质检链，计划看不到这条待办")

products = call("GET", "/products", token=admin).get("data") or []
product_id = products[0]["id"] if products else 1

outsourced = call("POST", "/purchase-requisitions", token=admin, body={
    "sourceType": "MANUAL",
    "department": "设备部",
    "needDate": NEED_DATE,
    "purpose": "外协分流回归 " + tag,
    "category": "OUTSOURCE",
    "urgent": False,
    "items": [{"productId": product_id, "qty": 2, "targetPrice": 4000}]
})
check("建外协采购申请", ok(outsourced), outsourced)
out_id = outsourced.get("data")
call("POST", f"/purchase-requisitions/{out_id}/submit", token=admin)

qc_tasks = call("GET", "/approvals/tasks", token=qc).get("data") or []
out_detail = call("GET", f"/purchase-requisitions/{out_id}", token=admin).get("data") or {}
check("外协单停在待审批", out_detail.get("status") == "SUBMITTED", out_detail.get("status"))
check("质检看到外协单待办",
      any(row.get("bizNo") == out_detail.get("reqNo") for row in qc_tasks), qc_tasks[:3])

planner_tasks = call("GET", "/approvals/tasks", token=planner).get("data") or []
check("计划看不到外协单待办",
      all(row.get("bizNo") != out_detail.get("reqNo") for row in planner_tasks))

# ---------------------------------------------------------------- 6. 原辅料单
section("6. 原辅料单落兜底链，计划来审")

material = call("POST", "/purchase-requisitions", token=admin, body={
    "sourceType": "MANUAL",
    "department": "生产部",
    "needDate": NEED_DATE,
    "purpose": "原辅料分流回归 " + tag,
    "category": "MATERIAL",
    "urgent": False,
    "items": [{"productId": product_id, "qty": 5, "targetPrice": 100}]
})
check("建原辅料采购申请", ok(material), material)
mat_id = material.get("data")
call("POST", f"/purchase-requisitions/{mat_id}/submit", token=admin)

mat_detail = call("GET", f"/purchase-requisitions/{mat_id}", token=admin).get("data") or {}
planner_tasks = call("GET", "/approvals/tasks", token=planner).get("data") or []
hit = next((row for row in planner_tasks if row.get("bizNo") == mat_detail.get("reqNo")), None)
check("计划看到原辅料单待办", hit is not None, planner_tasks[:3])
check("走的是兜底链", hit is not None and hit.get("flowCode") == "REQ_DEF_" + tag,
      hit.get("flowCode") if hit else None)

if hit:
    approved = call("POST", f"/approvals/tasks/{hit['id']}/approve", token=planner,
                    body={"comment": "同意"})
    check("计划审批通过", ok(approved), approved)
    after = call("GET", f"/purchase-requisitions/{mat_id}", token=admin).get("data") or {}
    check("单据回写为已批准", after.get("status") == "APPROVED", after.get("status"))

# ---------------------------------------------------------------- 7. 复原
section("7. 复原配置")

call("PUT", "/approvals/biz-types/REQUISITION", token=admin, body={
    "name": "采购申请", "menuPath": "/purchase/requisitions", "mode": "THRESHOLD"
})
for flow_id in [outsource_id, fallback_id, (second_fallback.get("data") or {}).get("id")]:
    if flow_id:
        call("POST", f"/approvals/flows/{flow_id}/toggle", token=admin, body={"enabled": False})
check("审批链已停用、开关恢复阈值模式", True)

print("\n================ 汇总 ================")
print("通过: %d 项" % PASSED)
print("失败: %d 项" % len(FAILED))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
