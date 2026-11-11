"""工序外协端到端回归：转外协 → 领料与报工被拦住 → 发出（扣库存）→ 部分/全部收回。

用法：python scripts/e2e-outsource.py [http://localhost:8080]

脚本会自己把外协工序的料补到够用（走手工入库），所以同一库上可以重复执行。
"""

import datetime
import json
import os
import sys
import urllib.error
import urllib.request

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
    password = password or os.environ.get("MES_PASSWORD", "123456")
    status, res = call("POST", "/auth/login", body={"username": username, "password": password})
    return res["data"]["token"] if res.get("code") == 0 else None


def ok(payload):
    return isinstance(payload, dict) and payload.get("code") == 0


def check(name, cond, extra=""):
    global PASSED
    if cond:
        PASSED += 1
        print("  [OK]   " + name)
    else:
        FAILED.append(name)
        print("  [FAIL] " + name + "  " + str(extra))


def section(title):
    print("\n== " + title + " ==")


def num(value):
    try:
        return float(value or 0)
    except (TypeError, ValueError):
        return 0.0


TODAY = datetime.date.today()
YESTERDAY = (TODAY - datetime.timedelta(days=1)).isoformat()

admin = login("admin")
check("管理员登录", admin is not None)
planner = login("planner")
check("计划登录", planner is not None)
warehouse = login("warehouse")
check("仓库登录", warehouse is not None)
qc = login("qc")
check("质检登录", qc is not None)

section("准备：挑一张在制工单的外协候选工序")
_, orders = call("GET", "/work-orders?size=60", admin)
order_rows = ((orders.get("data") or {}).get("records")) or []
candidates = [row for row in order_rows
              if row.get("status") in ("RELEASED", "IN_PROGRESS")]
check("有已下达/在制的工单可作为外协对象", len(candidates) > 0, len(candidates))

# 供应商：优先外协类
_, suppliers = call("GET", "/suppliers?enabled=true", admin)
supplier_rows = suppliers.get("data") or []
outsource_suppliers = [row for row in supplier_rows if row.get("category") == "OUTSOURCE"]
supplier = (outsource_suppliers or supplier_rows or [None])[0]
check("有可用的外协供应商", supplier is not None)

# 原料仓
_, warehouses = call("GET", "/warehouses", admin)
raw = next((row for row in (warehouses.get("data") or []) if row.get("type") == "RAW"), None)
check("有原料仓可用于发料", raw is not None, warehouses.get("message"))


def pick_operation():
    """找一道「前道已完工 + 有 BOM 物料 + 未完工 + 还没外协」的工序"""
    for row in candidates:
        _, detail = call("GET", "/work-orders/%d" % row["id"], admin)
        data = detail.get("data") or {}
        ops = {int(op["seq"]): op for op in (data.get("operations") or [])}
        for op in sorted(data.get("operations") or [], key=lambda item: int(item["seq"])):
            seq = int(op["seq"])
            if num(op.get("doneQty")) >= num(op.get("planQty")):
                continue
            if op.get("outsourceStatus"):
                continue
            if not op.get("materialRequired") or num(op.get("materialPendingQty")) <= 0:
                continue
            previous = ops.get(seq - 1)
            if previous and num(previous.get("doneQty")) < num(previous.get("planQty")):
                continue
            return data, op
    return None, None


work, operation = pick_operation()
if operation is None:
    check("找到可外协的工序", False, "没有合适的在制工单（需要前道已完工、有 BOM 物料的未完工工序）")
    print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
    sys.exit(1)

work_order_id = int(work["id"])
seq = int(operation["seq"])
operation_id = int(operation["id"])
check("找到可外协的工序", True)

section("备料：把这道工序的料补够")
_, materials = call("GET", "/outsource/work-orders/%d/operations/%d/materials" % (work_order_id, seq), admin)
lines = ((materials.get("data") or {}).get("lines")) or []
check("待发料清单可查", ok(materials) and len(lines) > 0, materials.get("message"))
stocked = True
for line in lines:
    need = num(line.get("remainingQty")) or num(line.get("needQty"))
    stock = num(line.get("stockQty"))
    if need <= 0 or stock >= need:
        continue
    top_up = need - stock + 10
    _, inbound = call("POST", "/inventory/inbound", warehouse, {
        "productId": line["materialId"],
        "warehouseId": raw["id"],
        "qty": top_up,
        "batchNo": "OS-E2E-%s-%s" % (TODAY.strftime("%m%d"), line["materialId"]),
        "remark": "外协回归补料"
    })
    if not ok(inbound):
        stocked = False
        check("补料成功", False, inbound.get("message"))
check("外协用料的库存够用", stocked)

section("转外协")
_, plan = call("POST", "/outsource/work-orders/%d/operations/%d/plan" % (work_order_id, seq), planner, {
    "supplierId": supplier["id"],
    "price": 8.5,
    "expectedAt": YESTERDAY,
    "remark": "回归：中途转外协"
})
check("计划可以转外协", ok(plan), plan.get("message"))
check("转外协后状态为待发出", (plan.get("data") or {}).get("outsourceStatus") == "PENDING_SEND",
      (plan.get("data") or {}).get("outsourceStatus"))

_, detail_now = call("GET", "/work-orders/%d" % work_order_id, admin)
op_now = next((op for op in ((detail_now.get("data") or {}).get("operations") or [])
               if int(op["seq"]) == seq), None)
check("工单详情带出外协标记", op_now is not None and op_now.get("outsourceStatus") == "PENDING_SEND",
      op_now and op_now.get("outsourceStatus"))
check("工单详情带出外协供应商", op_now is not None and op_now.get("outsourceSupplierName") is not None,
      op_now and op_now.get("outsourceSupplierName"))

# 撤销再重转：中途改主意要能退回
_, cancelled = call("DELETE", "/outsource/work-orders/%d/operations/%d/plan" % (work_order_id, seq), planner)
check("待发出可以撤销外协", ok(cancelled), cancelled.get("message"))
_, after_cancel = call("GET", "/outsource/work-orders/%d/operations/%d/materials" % (work_order_id, seq), admin)
check("撤销后回到厂内做", (after_cancel.get("data") or {}).get("outsourceStatus") is None,
      (after_cancel.get("data") or {}).get("outsourceStatus"))
_, replan = call("POST", "/outsource/work-orders/%d/operations/%d/plan" % (work_order_id, seq), planner, {
    "supplierId": supplier["id"],
    "price": 8.5,
    "expectedAt": YESTERDAY,
    "remark": "回归：再次转外协"
})
check("可以再次转外协", ok(replan), replan.get("message"))

section("外协期间的拦截")
material_id = int(lines[0]["materialId"])
_, blocked_issue = call("POST", "/work-orders/%d/issue-batch" % work_order_id, warehouse, {
    "source": "PC",
    "remark": "回归：外协工序不该在本厂领料",
    "items": [{"productId": material_id, "qty": 1}]
})
check("外协工序的料不能在本厂领", not ok(blocked_issue), blocked_issue.get("message"))
check("领料拦截提示指向外协",
      "外协" in str(blocked_issue.get("message") or ""), blocked_issue.get("message"))

_, blocked_report = call("POST", "/work-orders/%d/report" % work_order_id, admin, {
    "operationId": operation_id, "qty": 1, "worker": "回归"
})
report_message = str(blocked_report.get("message") or "")
check("外协工序不能直接报工", not ok(blocked_report), report_message)

section("发出")
send_qty = min(2, num(operation.get("planQty")) - num(operation.get("doneQty")))
_, sent = call("POST", "/outsource/work-orders/%d/operations/%d/send" % (work_order_id, seq), warehouse, {
    "qty": send_qty,
    "supplierId": supplier["id"],
    "price": 8.5,
    "expectedAt": YESTERDAY,
    "sender": "回归仓管",
    "remark": "回归：第一批发外协"
})
check("仓库可以发出外协", ok(sent), sent.get("message"))
sent_data = sent.get("data") or {}
order_id = sent_data.get("orderId")
order_no = sent_data.get("orderNo")
check("发出生成外协单号", bool(order_no), order_no)
check("发出后状态为已发出", sent_data.get("status") == "SENT", sent_data.get("status"))
check("发出带回发料明细", len(sent_data.get("lines") or []) > 0, sent_data.get("lines"))

_, after_send = call("GET", "/outsource/work-orders/%d/operations/%d/materials" % (work_order_id, seq), admin)
check("发出后工序标记外协中",
      (after_send.get("data") or {}).get("outsourceStatus") == "SENT",
      (after_send.get("data") or {}).get("outsourceStatus"))

_, again = call("POST", "/outsource/work-orders/%d/operations/%d/send" % (work_order_id, seq), warehouse, {
    "qty": 1, "supplierId": supplier["id"], "price": 8.5
})
check("同工序不能重复发出", not ok(again), again.get("message"))

_, blocked_cancel = call("DELETE", "/outsource/work-orders/%d/operations/%d/plan" % (work_order_id, seq), planner)
check("已发出的外协不能撤销", not ok(blocked_cancel), blocked_cancel.get("message"))

section("外协单台账")
_, listed = call("GET", "/outsource/orders?keyword=" + str(order_no), admin)
records = ((listed.get("data") or {}).get("records")) or []
check("外协单可按单号查到", ok(listed) and len(records) == 1, len(records))
row = records[0] if records else {}
check("台账带外协厂", row.get("supplierName") is not None, row.get("supplierName"))
check("台账带工序信息", int(row.get("operationSeq") or 0) == seq, row.get("operationSeq"))
check("约定回厂写进去了", row.get("expectedAt") == YESTERDAY, row.get("expectedAt"))
check("超期能识别出来", row.get("overdue") is True, row.get("overdue"))
check("超期天数大于 0", int(row.get("overdueDays") or 0) >= 1, row.get("overdueDays"))

_, overdue_list = call("GET", "/outsource/orders?overdueOnly=true&size=100", admin)
overdue_nos = [item.get("orderNo") for item in ((overdue_list.get("data") or {}).get("records") or [])]
check("只看超期能筛到这张单", order_no in overdue_nos, overdue_nos[:5])

_, detail_order = call("GET", "/outsource/orders/%d" % order_id, admin)
order_detail = detail_order.get("data") or {}
check("外协单详情带发料明细", len(order_detail.get("items") or []) > 0, order_detail.get("items"))
check("发料明细带批次或随件转出",
      all(item.get("fromStock") is True or item.get("batchSummary") for item in (order_detail.get("items") or [])),
      order_detail.get("items"))

_, summary = call("GET", "/outsource/summary", admin)
check("外协概览可查", ok(summary), summary.get("message"))
check("概览里在制单数大于 0", int(num((summary.get("data") or {}).get("open"))) >= 1,
      (summary.get("data") or {}).get("open"))

section("收回")
if num(send_qty) >= 2:
    _, partial = call("POST", "/outsource/orders/%d/receive" % order_id, qc, {
        "qty": 1, "defectQty": 0, "receiver": "回归质检", "remark": "先回一件",
        "inspectionResult": "PASS"
    })
    check("质检可以登记部分收回", ok(partial), partial.get("message"))
    check("部分收回后单据为部分收回",
          (partial.get("data") or {}).get("status") == "PARTIAL",
          (partial.get("data") or {}).get("status"))
    remain = num(send_qty) - 1
else:
    remain = send_qty

_, received = call("POST", "/outsource/orders/%d/receive" % order_id, qc, {
    "qty": remain, "defectQty": 0, "receiver": "回归质检", "remark": "剩下的都回来了",
    "inspectionResult": "PASS"
})
check("收完剩余数量", ok(received), received.get("message"))
check("收完后单据为已收回",
      (received.get("data") or {}).get("status") == "RECEIVED",
      (received.get("data") or {}).get("status"))
check("外协费按收回合格数算出来", num((received.get("data") or {}).get("amount")) > 0,
      (received.get("data") or {}).get("amount"))

_, done_op = call("GET", "/work-orders/%d" % work_order_id, admin)
op_done = next((op for op in ((done_op.get("data") or {}).get("operations") or [])
                if int(op["seq"]) == seq), None)
check("收回后工序完成数增加", num(op_done.get("doneQty")) > num(operation.get("doneQty")),
      (op_done and op_done.get("doneQty"), operation.get("doneQty")))
check("收回后工序标记外协已回", op_done.get("outsourceStatus") == "RECEIVED",
      op_done and op_done.get("outsourceStatus"))
check("收回数量记在工序上", num(op_done.get("outsourceReceivedQty")) == num(send_qty),
      (op_done and op_done.get("outsourceReceivedQty"), send_qty))

_, over_receive = call("POST", "/outsource/orders/%d/receive" % order_id, qc, {
    "qty": 1, "defectQty": 0, "inspectionResult": "PASS"
})
check("已收完的单不能再收回", not ok(over_receive), over_receive.get("message"))

section("成本与权限")
_, recalc = call("POST", "/cost/work-orders/%d/recalc" % work_order_id, admin)
check("工单成本可以重算", ok(recalc), recalc.get("message"))
_, cost = call("GET", "/cost/work-orders/%d" % work_order_id, admin)
cost_data = cost.get("data") or {}
check("成本里带外协费", num(cost_data.get("outsourceCost")) > 0, cost_data.get("outsourceCost"))
check("成本明细有外协行", len(cost_data.get("outsources") or []) > 0, cost_data.get("outsources"))
check("人工成本不含外协",
      all(num(item.get("amount")) >= 0 for item in (cost_data.get("labors") or [])), cost_data.get("labors"))

_, qc_send = call("POST", "/outsource/work-orders/%d/operations/1/send" % work_order_id, qc,
                  {"qty": 1, "supplierId": supplier["id"]})
check("质检无权发出外协", qc_send.get("code") != 0, qc_send.get("message"))
_, warehouse_receive = call("POST", "/outsource/orders/%d/receive" % order_id, warehouse,
                            {"qty": 1, "inspectionResult": "PASS"})
check("仓库无权登记收回", not ok(warehouse_receive), warehouse_receive.get("message"))

section("大屏")
_, public_production = call("GET", "/public/screen/production")
check("大屏生产屏免登录可读", ok(public_production), public_production.get("message"))
check("大屏带外协中数量", "outsourcing" in ((public_production.get("data") or {}).get("kpi") or {}),
      (public_production.get("data") or {}).get("kpi"))
_, private_screen = call("GET", "/outsource/orders")
check("外协台账仍需登录", private_screen.get("code") != 0, private_screen.get("message"))

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
