import json
import os
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta

BASE = os.environ.get("MES_BASE", "http://localhost:8080/api")
TODAY = date.today()
TODAY_S = TODAY.isoformat()
DELIVERY = (TODAY + timedelta(days=5)).isoformat()
MONTH = TODAY.strftime("%Y-%m")
QC_SUFFIX = str(int.from_bytes(os.urandom(2), "big")).zfill(5)
QC_USER = "testqc" + QC_SUFFIX
QC_PHONE = "139" + QC_SUFFIX + "000"
PASS = 0
FAILS = []


def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path,
                                 data=json.dumps(body).encode("utf-8") if body is not None else None,
                                 method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8")
        try:
            payload = json.loads(text)
            payload["_http"] = e.code
            return payload
        except Exception:
            return {"code": e.code, "message": text, "_http": e.code}


def ok(payload):
    return payload.get("code") == 0


def denied(payload):
    return payload.get("_http") == 403 or payload.get("code") == 403


def check(name, cond, extra=""):
    global PASS
    if cond:
        PASS += 1
        print("  PASS  " + name)
    else:
        FAILS.append(name)
        print("  FAIL  " + name + "  " + str(extra))


def section(title):
    print("\n== " + title + " ==")


def login(username, password=None):
    # 强制改密之后 admin 的口令可能已经变了，用 MES_PASSWORD 传入
    password = password or os.environ.get("MES_PASSWORD", "123456")
    payload = call("POST", "/auth/login", {"username": username, "password": password})
    return payload["data"]["token"] if ok(payload) else None


def stock_of(token, code):
    data = call("GET", "/inventory?keyword=" + code, token=token)["data"]
    rows = data.get("records") if isinstance(data, dict) else data
    return sum(float(r["qty"]) for r in rows if r["productCode"] == code)


admin = login("admin")
check("管理员登录", bool(admin))

base_report = call("GET", f"/reports/production?from={TODAY_S}&to={TODAY_S}", token=admin)["data"]
base_payroll = call("GET", f"/reports/payroll?month={MONTH}", token=admin)["data"]
base_wages = {r["worker"]: float(r["wage"]) for r in base_payroll["rows"]}

# ================= 1. 基础数据 =================
section("1. 基础数据")
customers = call("GET", "/customers", token=admin)["data"]
check("客户主数据", len(customers) >= 3, len(customers))
products = call("GET", "/products", token=admin)["data"]
by_code = {p["code"]: p for p in products}
check("产品主数据含工艺路线数", len(products) >= 8 and by_code["P001"]["routingCount"] == 3
      and by_code["P003"]["routingCount"] == 0,
      {p["code"]: p["routingCount"] for p in products})
processes = call("GET", "/processes", token=admin)["data"]
check("工序主数据", len(processes) == 7, [p["name"] for p in processes])
routing = call("GET", "/products/1/routing", token=admin)["data"]
check("产品 1 工艺路线", [r["processName"] for r in routing] == ["下料", "机加工", "装配"],
      [r["processName"] for r in routing])
bom1 = call("GET", "/products/1/bom", token=admin)["data"]
check("BOM 关联投料工序", all(r["operationSeq"] for r in bom1),
      [(r["materialName"], r["operationSeq"]) for r in bom1])
warehouses = call("GET", "/warehouses", token=admin)["data"]
check("仓库主数据", len(warehouses) >= 3, len(warehouses))

def stock_of(token, code):
    data = call("GET", "/inventory?keyword=" + code, token=token)["data"]
    rows = data.get("records") if isinstance(data, dict) else data
    return sum(float(r["qty"]) for r in rows if r["productCode"] == code)


def raw_stock(code):
    """原料仓（WH-RAW）的结存。

    领料只从原料仓扣，全仓合计再多也不代表能领出来 —— 别的仓（比如半成品仓）
    堆着几万件时，按合计判断会跳过补料，然后整单领料报「库存不足」。
    """
    data = call("GET", "/inventory?keyword=%s&warehouseId=1" % code, token=admin)["data"]
    rows = data.get("records") if isinstance(data, dict) else data
    return sum(float(r["qty"]) for r in rows if r["productCode"] == code)


def ensure_stock(code, product_id, target):
    current = raw_stock(code)
    if current >= target:
        return
    oid = call("POST", "/inbound-orders", {
        "type": "OTHER", "warehouseId": 1, "supplier": "回归补料", "needInspection": False,
        "remark": "回归测试准备库存", "noOrderReason": "回归脚本准备测试库存，无采购单",
        "items": [{"productId": product_id, "qty": target - current, "batchNo": "B-REG"}]
    }, token=admin)["data"]
    call("POST", f"/inbound-orders/{oid}/submit", {}, token=admin)


# ================= 2. 销售 =================
section("2. 销售订单 -> 生产工单")
ensure_stock("P003", by_code["P003"]["id"], 1200)
ensure_stock("P004", by_code["P004"]["id"], 300)
ensure_stock("P005", by_code["P005"]["id"], 5000)
ensure_stock("P006", by_code["P006"]["id"], 2000)
ensure_stock("P009", by_code["P009"]["id"], 1000)
order_id = call("POST", "/sales-orders", {
    "customerId": 1, "orderDate": TODAY_S, "deliveryDate": DELIVERY,
    "remark": "全流程回归测试", "priority": "URGENT",
    "customerPoNo": "PO-REG-001", "contact": "王工 13800000000",
    "deliveryAddress": "苏州工业园区星湖街 1 号", "salesOwner": "张销售",
    "items": [{"productId": 1, "qty": 20, "price": 268}]
}, token=admin)["data"]
order = call("GET", f"/sales-orders/{order_id}", token=admin)["data"]
check("订单创建为草稿", order["status"] == "DRAFT", order["orderNo"])
check("订单金额计算", float(order["totalAmount"]) == 5360.0, order["totalAmount"])
check("订单优先级带入", order["priority"] == "URGENT", order.get("priority"))
check("订单记下客户 PO 号", order.get("customerPoNo") == "PO-REG-001", order.get("customerPoNo"))
check("订单记下联系人", order.get("orderContact") == "王工 13800000000", order.get("orderContact"))
check("订单记下交货地址", order.get("deliveryAddress") == "苏州工业园区星湖街 1 号",
      order.get("deliveryAddress"))
check("订单记下跟单人", order.get("salesOwner") == "张销售", order.get("salesOwner"))

payload = call("POST", f"/sales-orders/{order_id}/to-production", {}, token=admin)
check("未审核不能转生产", not ok(payload), payload.get("message"))

call("POST", f"/sales-orders/{order_id}/confirm", {}, token=admin)
order = call("GET", f"/sales-orders/{order_id}", token=admin)["data"]
check("订单审核", order["status"] == "CONFIRMED", order["status"])
payload = call("POST", f"/sales-orders/{order_id}/confirm", {}, token=admin)
check("重复审核拦截", not ok(payload), payload.get("message"))

created = call("POST", f"/sales-orders/{order_id}/to-production", {}, token=admin)
check("订单转生产", ok(created) and len(created["data"]) == 1, created.get("message"))
wo_a_no = created["data"][0]["workNo"]
wo_a = call("GET", f"/work-orders/by-no/{wo_a_no}", token=admin)["data"]
check("工单按路线生成工序", [o["name"] for o in wo_a["operations"]] == ["下料", "机加工", "装配"],
      [o["name"] for o in wo_a["operations"]])
check("工单携带订单交期", wo_a.get("deliveryDate") == DELIVERY, wo_a.get("deliveryDate"))
check("工单优先级随订单", wo_a["priority"] == "URGENT", wo_a["priority"])
bom_a = {r["materialName"]: float(r["needQty"]) for r in wo_a["bom"]}
check("工单 BOM 快照含损耗", abs(bom_a.get("铝合金型材", 0) - 16.16) < 0.001
      and abs(bom_a.get("标准螺栓", 0) - 121.2) < 0.001
      and abs(bom_a.get("密封圈", 0) - 40.4) < 0.001, bom_a)
payload = call("POST", f"/sales-orders/{order_id}/to-production", {}, token=admin)
check("重复转生产拦截", not ok(payload), payload.get("message"))
order = call("GET", f"/sales-orders/{order_id}", token=admin)["data"]
check("订单进入生产中", order["status"] == "IN_PRODUCTION", order["status"])
order_items = order.get("items") or order.get("lines") or []
check("订单明细可读", len(order_items) == 1, order_items)

# ================= 3. 计划 =================
section("3. 排产计划")
payload = call("POST", f"/work-orders/{wo_a['id']}/release", {}, token=admin)
check("未排产不能下达", payload.get("code") == 2005, payload.get("message"))

payload = call("POST", f"/scheduling/{wo_a['id']}/schedule", {"date": TODAY_S}, token=admin)
check("手工排产", ok(payload), payload.get("message"))
wo_a = call("GET", f"/work-orders/{wo_a['id']}", token=admin)["data"]
check("排产后状态", wo_a["status"] == "SCHEDULED" and wo_a["scheduleSeq"] > 0,
      (wo_a["status"], wo_a.get("scheduleSeq")))

payload = call("POST", "/work-orders", {
    "productId": 2, "planQty": 10, "planStart": TODAY_S, "planEnd": DELIVERY
}, token=admin)
wo_b_id = payload["data"]
wo_b = call("GET", f"/work-orders/{wo_b_id}", token=admin)["data"]
check("手动建单按路线生成", [o["name"] for o in wo_b["operations"]] == ["下料", "折弯", "装配"],
      [o["name"] for o in wo_b["operations"]])

# 建单字段：优先级 / 追溯方式 / 备注 都能在建单时带上，不用建完再进去改
payload = call("POST", "/work-orders", {
    "productId": 2, "planQty": 4, "planStart": TODAY_S, "planEnd": DELIVERY,
    "priority": "URGENT", "traceMode": "LOT", "remark": "建单字段回归：" + TODAY_S
}, token=admin)
wo_c_id = payload.get("data")
wo_c = call("GET", f"/work-orders/{wo_c_id}", token=admin)["data"] if wo_c_id else {}
check("建单可带优先级", wo_c.get("priority") == "URGENT", wo_c.get("priority"))
check("建单可带追溯方式", wo_c.get("traceMode") == "LOT", wo_c.get("traceMode"))
check("建单可带备注", str(wo_c.get("remark") or "").startswith("建单字段回归"), wo_c.get("remark"))
bad_priority = call("POST", "/work-orders", {
    "productId": 2, "planQty": 1, "priority": "ASAP"
}, token=admin)
check("非法优先级被拒", not ok(bad_priority), bad_priority.get("message"))
if wo_c_id:
    call("POST", f"/work-orders/{wo_c_id}/cancel?reason={urllib.parse.quote('建单字段回归清理')}",
         {}, token=admin)

payload = call("POST", f"/scheduling/{wo_b_id}/rush", {"date": TODAY_S}, token=admin)
wo_b = call("GET", f"/work-orders/{wo_b_id}", token=admin)["data"]
check("加急插队", ok(payload) and wo_b["priority"] == "RUSH"
      and wo_b["scheduleSeq"] < wo_a["scheduleSeq"],
      (wo_b["priority"], wo_b["scheduleSeq"], wo_a["scheduleSeq"]))

payload = call("POST", f"/scheduling/{wo_b_id}/move", {"direction": "DOWN"}, token=admin)
check("队列下移", ok(payload), payload.get("message"))
payload = call("POST", f"/scheduling/{wo_b_id}/unschedule", {}, token=admin)
wo_b = call("GET", f"/work-orders/{wo_b_id}", token=admin)["data"]
check("撤销排产", ok(payload) and wo_b["status"] == "DRAFT", wo_b["status"])

payload = call("POST", "/scheduling/auto", {"startDate": TODAY_S, "days": 7, "dailyCapacity": 480},
               token=admin)
auto = payload.get("data") or {}
if (auto.get("scheduledCount") or 0) < 1:
    # 回归库里历史工单会把 7 天窗口的产能占满，放宽到 30 天再排一次
    payload = call("POST", "/scheduling/auto", {"startDate": TODAY_S, "days": 30, "dailyCapacity": 480},
                   token=admin)
    auto = payload.get("data") or {}
check("一键智能排产", ok(payload) and auto.get("scheduledCount", 0) >= 1,
      (auto.get("scheduledCount"), auto.get("skippedCount")))
wo_b = call("GET", f"/work-orders/{wo_b_id}", token=admin)["data"]
check("智能排产后已排产", wo_b["status"] == "SCHEDULED", wo_b["status"])

payload = call("POST", f"/work-orders/{wo_a['id']}/release", {}, token=admin)
check("已排产可下达", ok(payload), payload.get("message"))
payload = call("POST", f"/work-orders/{wo_b_id}/release", {}, token=admin)
check("第二张工单下达", ok(payload), payload.get("message"))

# ================= 4. 生产执行 =================
section("4. 非标 BOM / 领料 / 报工")
payload = call("POST", f"/work-orders/{wo_a['id']}/bom", {
    "materialId": 4, "planQty": 3, "operationSeq": 2,
    "remark": "非标增加底板", "reason": "客户要求增加不锈钢底板"
}, token=admin)
check("非标新增物料", ok(payload), payload.get("message"))
payload = call("POST", f"/work-orders/{wo_a['id']}/bom", {
    "materialId": 6, "planQty": 60, "operationSeq": 3,
    "remark": "双排密封", "reason": "防水等级提高到 IP67"
}, token=admin)
check("非标调整用量", ok(payload), payload.get("message"))
payload = call("POST", f"/work-orders/{wo_a['id']}/bom", {
    "materialId": 5, "planQty": 200, "operationSeq": 3, "reason": ""
}, token=admin)
check("非标调整必须填原因", payload.get("code") == 1002, payload.get("message"))

wo_a = call("GET", f"/work-orders/{wo_a['id']}", token=admin)["data"]
check("BOM 变更记录", len(wo_a["bomChanges"]) == 2,
      [(c["action"], c["materialName"]) for c in wo_a["bomChanges"]])

payload = call("POST", f"/work-orders/{wo_a['id']}/issue", {}, token=admin)
check("整单领料", ok(payload), payload.get("message"))
wo_a = call("GET", f"/work-orders/{wo_a['id']}", token=admin)["data"]
check("领料后无待领物料", all(float(o["materialPendingQty"]) == 0 for o in wo_a["operations"]),
      [(o["name"], float(o["materialPendingQty"])) for o in wo_a["operations"]])
check("领料数量等于工单 BOM", all(float(r["issuedQty"]) == float(r["needQty"]) for r in wo_a["bom"]),
      [(r["materialName"], float(r["issuedQty"])) for r in wo_a["bom"]])
payload = call("POST", f"/work-orders/{wo_a['id']}/issue", {}, token=admin)
check("重复领料拦截", not ok(payload), payload.get("message"))

issued = call("POST", f"/work-orders/{wo_b['id']}/issue-item", {"productId": 4, "qty": 2}, token=admin)
check("按物料分批领料", ok(issued), issued.get("message"))
wo_b = call("GET", f"/work-orders/{wo_b['id']}", token=admin)["data"]
row4 = [r for r in wo_b["bom"] if r["materialName"] == "不锈钢板材"][0]
check("分批领料剩余量", abs(float(row4["needQty"]) - float(row4["issuedQty"]) - 4.06) < 0.01,
      (row4["needQty"], row4["issuedQty"]))

ops = wo_a["operations"]
payload = call("POST", f"/work-orders/{wo_a['id']}/report", {
    "operationId": ops[1]["id"], "qty": 5, "qualifiedQty": 5, "defectQty": 0, "worker": "张伟"
}, token=admin)
check("跳过前道被拦截", not ok(payload) and "前道" in str(payload.get("message")),
      payload.get("message"))
payload = call("POST", f"/work-orders/{wo_a['id']}/report", {
    "operationId": ops[0]["id"], "qty": 0, "qualifiedQty": 0, "defectQty": 0, "worker": "张伟"
}, token=admin)
check("报工数量校验", payload.get("code") == 1002, payload.get("message"))
payload = call("POST", f"/work-orders/{wo_a['id']}/report", {
    "operationId": ops[0]["id"], "qty": 20, "qualifiedQty": 18, "defectQty": 2, "worker": "张伟"
}, token=admin)
check("下料报工", ok(payload), payload.get("message"))
wo_a = call("GET", f"/work-orders/{wo_a['id']}", token=admin)["data"]
check("不良扣减后道计划", [float(o["planQty"]) for o in wo_a["operations"]] == [20, 18, 18],
      [float(o["planQty"]) for o in wo_a["operations"]])
payload = call("POST", f"/work-orders/{wo_a['id']}/report", {
    "operationId": ops[1]["id"], "qty": 19, "qualifiedQty": 19, "defectQty": 0, "worker": "张伟"
}, token=admin)
check("超量报工拦截", payload.get("code") == 2010, payload.get("message"))
payload = call("POST", f"/work-orders/{wo_a['id']}/report", {
    "operationId": ops[1]["id"], "qty": 18, "qualifiedQty": 18, "defectQty": 0, "worker": "张伟"
}, token=admin)
check("机加工报工", ok(payload), payload.get("message"))
payload = call("POST", f"/work-orders/{wo_a['id']}/report", {
    "operationId": ops[2]["id"], "qty": 18, "qualifiedQty": 17, "defectQty": 1, "worker": "李娜"
}, token=admin)
check("装配报工", ok(payload) and payload["data"].get("finished") is True, payload.get("data"))
wo_a = call("GET", f"/work-orders/{wo_a['id']}", token=admin)["data"]
check("工单自动完工", wo_a["status"] == "FINISHED" and float(wo_a["qualifiedQty"]) == 17,
      (wo_a["status"], wo_a["qualifiedQty"]))

# ================= 5. 质量 =================
section("5. 质量检验与自动入库")
finished_before = stock_of(admin, "P001")
payload = call("POST", "/inspections", {
    "type": "FINAL", "refType": "WORK_ORDER", "refNo": wo_a["workNo"],
    "workOrderId": wo_a["id"], "productId": 1, "inspectQty": 17, "defectQty": 0,
    "inspector": "周敏", "remark": "完工全检"
}, token=admin)
check("完工检验", ok(payload), payload.get("message"))
wo_a = call("GET", f"/work-orders/{wo_a['id']}", token=admin)["data"]
check("完工检后工单关闭", wo_a["status"] == "CLOSED", wo_a["status"])
order = call("GET", f"/sales-orders/{order_id}", token=admin)["data"]
check("订单进入待发货", order["status"] == "READY_TO_SHIP", order["status"])
finished_after = stock_of(admin, "P001")
check("完工检合格入成品仓", abs(finished_after - finished_before - 17) < 0.01,
      (finished_before, finished_after))
payload = call("POST", "/inspections", {
    "type": "FINAL", "refType": "WORK_ORDER", "refNo": wo_a["workNo"],
    "workOrderId": wo_a["id"], "productId": 1, "inspectQty": 17, "defectQty": 0, "inspector": "周敏"
}, token=admin)
check("重复完工检拦截", payload.get("code") in (2022, 2023), payload.get("message"))
payload = call("POST", "/inspections", {
    "type": "PROCESS", "productId": 1, "inspectQty": 5, "defectQty": 9, "inspector": "周敏"
}, token=admin)
check("不良数超过检验数拦截", payload.get("code") == 1002, payload.get("message"))

# ================= 6. 仓储 =================
section("6. 仓储：入库 / 出库 / 调拨 / 盘点")
stock_p5 = stock_of(admin, "P005")
inbound_id = call("POST", "/inbound-orders", {
    "type": "OTHER", "warehouseId": 1, "supplier": "宁波紧固件",
    "needInspection": False, "remark": "免检补货", "noOrderReason": "全流程回归补免检库存，无采购单",
    "items": [{"productId": 5, "qty": 500, "batchNo": "B20261021"}]
}, token=admin)["data"]
payload = call("POST", f"/inbound-orders/{inbound_id}/submit", {}, token=admin)
check("免检入库提交", ok(payload), payload.get("message"))
check("免检入库直接增加库存", abs(stock_of(admin, "P005") - stock_p5 - 500) < 0.01,
      (stock_p5, stock_of(admin, "P005")))

stock4_before = stock_of(admin, "P004")
inbound2 = call("POST", "/inbound-orders", {
    "type": "OTHER", "warehouseId": 1, "supplier": "江苏板材",
    "needInspection": True, "remark": "来料需检验", "noOrderReason": "全流程回归补待检库存，无采购单",
    "items": [{"productId": 4, "qty": 100, "batchNo": "B20261022"}]
}, token=admin)["data"]
call("POST", f"/inbound-orders/{inbound2}/submit", {}, token=admin)
check("需检验入库暂不入库", abs(stock_of(admin, "P004") - stock4_before) < 0.01,
      (stock4_before, stock_of(admin, "P004")))
payload = call("POST", "/inspections", {
    "type": "INCOMING", "refType": "INBOUND", "refNo": "B20261022",
    "productId": 4, "inspectQty": 100, "defectQty": 0, "inspector": "周敏",
    "inboundOrderId": inbound2
}, token=admin)
check("来料检验", ok(payload), payload.get("message"))
check("来料检合格自动入库", abs(stock_of(admin, "P004") - stock4_before - 100) < 0.01,
      (stock4_before, stock_of(admin, "P004")))

outbound_id = call("POST", "/outbound-orders", {
    "type": "MANUAL", "warehouseId": 1, "remark": "领用出库",
    "items": [{"productId": 4, "qty": 30}]
}, token=admin)["data"]
payload = call("POST", f"/outbound-orders/{outbound_id}/complete", {}, token=admin)
check("出库完成", ok(payload), payload.get("message"))
check("出库扣减库存", abs(stock_of(admin, "P004") - (stock4_before + 100 - 30)) < 0.01,
      stock_of(admin, "P004"))
over_id = call("POST", "/outbound-orders", {
    "type": "MANUAL", "warehouseId": 1, "items": [{"productId": 4, "qty": 999999}]
}, token=admin).get("data")
payload = call("POST", f"/outbound-orders/{over_id}/complete", {}, token=admin)
check("超库存出库拦截", not ok(payload), payload.get("message"))

transfer_id = call("POST", "/transfers", {
    "fromWarehouseId": 1, "toWarehouseId": 3, "remark": "备料移库",
    "items": [{"productId": 5, "qty": 1000}]
}, token=admin)["data"]
payload = call("POST", f"/transfers/{transfer_id}/complete", {}, token=admin)
check("调拨完成", ok(payload), payload.get("message"))
inv5 = call("GET", "/inventory?keyword=P005", token=admin)["data"]
rows5 = inv5.get("records") if isinstance(inv5, dict) else inv5
wh5 = {r["warehouseName"]: float(r["qty"]) for r in rows5}
check("调拨两仓变化", wh5.get("半成品仓", 0) >= 1000, wh5)

stocktake_id = call("POST", "/stocktakes", {"warehouseId": 1, "remark": "月末盘点"}, token=admin)["data"]
form = call("GET", f"/stocktakes/{stocktake_id}", token=admin)["data"]
items = form.get("items") or []
target = [i for i in items if i.get("productCode") == "P006"] or items[:1]
new_actual = float(target[0]["bookQty"]) - 5
payload = call("PUT", f"/stocktakes/{stocktake_id}", {
    "items": [{"id": i["id"], "actualQty": new_actual if i["id"] == target[0]["id"] else float(i["bookQty"])}
              for i in items]
}, token=admin)
check("盘点录入", ok(payload), payload.get("message"))
payload = call("POST", f"/stocktakes/{stocktake_id}/complete", {}, token=admin)
check("盘点完成", ok(payload), payload.get("message"))
check("盘点差异已调整", abs(stock_of(admin, "P006") - new_actual) < 0.01,
      (new_actual, stock_of(admin, "P006")))

# ================= 7. 发货 =================
section("7. 发货与签收")
if order_items:
    item_id = order_items[0]["id"]
    shipment_id = call("POST", "/shipments", {
        "salesOrderId": order_id, "carrier": "顺丰速运", "trackingNo": "SF20261021001",
        "address": "上海市嘉定区工业园 88 号", "items": [{"orderItemId": item_id, "qty": 20}]
    }, token=admin)["data"]
    payload = call("POST", f"/shipments/{shipment_id}/ship", {}, token=admin)
    check("发货出库", ok(payload), payload.get("message"))
    payload = call("POST", f"/shipments/{shipment_id}/sign", {}, token=admin)
    check("签收", ok(payload), payload.get("message"))
    order = call("GET", f"/sales-orders/{order_id}", token=admin)["data"]
    check("订单履约完成", order["status"] == "COMPLETED", order["status"])

    # ================= 8. 退货 =================
    section("8. 退货与回补库存")
    p1_before = stock_of(admin, "P001")
    ret_id = call("POST", "/returns", {
        "salesOrderId": order_id, "reason": "运输外观划伤", "disposition": "RESTOCK",
        "items": [{"productId": 1, "qty": 1}]
    }, token=admin)["data"]
    check("创建退货单", bool(ret_id), ret_id)
    payload = call("POST", f"/returns/{ret_id}/process", {}, token=admin)
    check("处理退货回补库存", ok(payload) and abs(stock_of(admin, "P001") - p1_before - 1) < 0.01,
          (p1_before, stock_of(admin, "P001")))
    payload = call("POST", "/returns", {
        "salesOrderId": order_id, "reason": "超量退货", "disposition": "RESTOCK",
        "items": [{"productId": 1, "qty": 999}]
    }, token=admin)
    check("超量退货拦截", not ok(payload), payload.get("message"))
else:
    check("订单明细可读", False, "订单明细为空，跳过发货与退货")

# ================= 9. 看板 / 安灯 / 报表 =================
section("9. 车间看板 / 安灯 / 报表")
board = call("GET", "/workshop/board", token=admin)["data"]
check("车间看板 KPI", "inProgress" in board["kpi"] and "openAndon" in board["kpi"], board["kpi"])
check("看板工序分布", len(board.get("operations", [])) >= 3,
      [o.get("name") for o in board.get("operations", [])])

payload = call("POST", "/andon/calls", {
    "workOrderId": wo_b_id, "operationName": "折弯", "type": "EQUIPMENT",
    "description": "折弯机液压油温过高"
}, token=admin)
call_row = payload["data"]
check("安灯呼叫", ok(payload) and call_row["callNo"].startswith("AD"), call_row.get("callNo"))
payload = call("POST", f"/andon/calls/{call_row['id']}/ack", {}, token=admin)
check("安灯接单", ok(payload) and payload["data"]["status"] == "ACKED", payload.get("data"))
payload = call("POST", f"/andon/calls/{call_row['id']}/ack", {}, token=admin)
check("重复接单拦截", payload.get("code") == 2014, payload.get("message"))
payload = call("POST", f"/andon/calls/{call_row['id']}/resolve", {}, token=admin)
check("安灯解决", ok(payload) and payload["data"]["status"] == "RESOLVED", payload.get("data"))
payload = call("POST", "/andon/calls", {"workOrderId": wo_b_id, "type": "BAD", "description": "x"},
               token=admin)
check("非法安灯类型拦截", payload.get("code") == 1002, payload.get("message"))

report = call("GET", f"/reports/production?from={TODAY_S}&to={TODAY_S}", token=admin)["data"]
s = report["summary"]
base_s = base_report["summary"]
check("产量报表汇总",
      abs(float(s["totalQty"]) - float(base_s["totalQty"]) - 56) < 0.01
      and abs(float(s["qualifiedQty"]) - float(base_s["qualifiedQty"]) - 53) < 0.01
      and abs(float(s["defectQty"]) - float(base_s["defectQty"]) - 3) < 0.01
      and int(s["reports"]) - int(base_s["reports"]) == 3, s)
check("产量按工序分组",
      {"下料", "机加工", "装配"}.issubset({r["name"] for r in report["byOperation"]}),
      [r["name"] for r in report["byOperation"]])
check("产量按人员分组", {"张伟", "李娜"}.issubset({r["worker"] for r in report["byWorker"]}),
      [r["worker"] for r in report["byWorker"]])
payroll = call("GET", f"/reports/payroll?month={MONTH}", token=admin)["data"]
wages = {r["worker"]: float(r["wage"]) for r in payroll["rows"]}
wage_delta = {k: wages.get(k, 0) - base_wages.get(k, 0) for k in set(wages) | set(base_wages)}
check("计件工资", abs(wage_delta.get("张伟", 0) - 66.60) < 0.01
      and abs(wage_delta.get("李娜", 0) - 34.00) < 0.01, wage_delta)
wage_total_delta = float(payroll["summary"]["totalWage"]) - float(base_payroll["summary"]["totalWage"])
check("工资合计", abs(wage_total_delta - 100.60) < 0.01, wage_total_delta)
delivery_rows = call("GET", "/reports/delivery", token=admin)["data"]
check("交期预警返回", isinstance(delivery_rows, list), len(delivery_rows))
summary = call("GET", "/andon/summary", token=admin)["data"]
check("安灯汇总", "open" in summary and "resolvedToday" in summary, summary)

# ================= 10. 系统管理 =================
section("10. 系统管理")
users = call("GET", "/system/users", token=admin)["data"]
user_rows = users.get("records") if isinstance(users, dict) else users
check("用户列表", len(user_rows) >= 5, len(user_rows))
payload = call("POST", "/system/users", {
    "username": QC_USER, "password": "Qc@123456", "realName": "测试质检",
    "phone": QC_PHONE, "email": "qc@mes.local", "role": "QC", "enabled": True
}, token=admin)
check("新建用户", ok(payload), payload.get("message"))
qc_token = login(QC_PHONE, "Qc@123456")
check("手机号登录", bool(qc_token))
payload = call("PUT", "/auth/password", {"oldPassword": "Qc@123456", "newPassword": "Qc@654321"},
               token=qc_token)
check("修改密码", ok(payload), payload.get("message"))
check("新密码登录", bool(login(QC_PHONE, "Qc@654321")))
check("旧密码失效", login(QC_PHONE, "Qc@123456") is None)

roles = call("GET", "/system/roles", token=admin)["data"]
planner_role = [r for r in roles if r["code"] == "PLANNER"][0]
original_perms = list(planner_role["permissions"])
payload = call("PUT", f"/system/roles/{planner_role['id']}", {
    "code": planner_role["code"], "name": planner_role["name"],
    "description": planner_role.get("description"), "dataScope": planner_role.get("dataScope"),
    "permissions": original_perms + ["process:save"]
}, token=admin)
check("角色增加工序权限", ok(payload), payload.get("message"))
planner = login("planner")
payload = call("POST", "/processes", {
    "code": "TMP", "name": "临时工序", "category": "PACKING"
}, token=planner)
check("新权限即时生效", ok(payload), payload.get("message"))
tmp_id = payload.get("data")
call("PUT", f"/system/roles/{planner_role['id']}", {
    "code": planner_role["code"], "name": planner_role["name"],
    "description": planner_role.get("description"), "dataScope": planner_role.get("dataScope"),
    "permissions": original_perms
}, token=admin)
payload = call("POST", "/processes", {
    "code": "TMP2", "name": "临时工序2", "category": "PACKING"
}, token=planner)
check("撤销权限即时失效", denied(payload), (payload.get("_http"), payload.get("code")))
if tmp_id:
    call("DELETE", f"/processes/{tmp_id}", token=admin)

rules = call("GET", "/system/code-rules", token=admin).get("data") or []
check("编码规则可读", len(rules) > 0, len(rules))
so_rule = [r for r in rules if r["prefix"] == "SO"][0]
call("PUT", f"/system/code-rules/{so_rule['id']}", {
    "prefix": "SOX", "datePattern": so_rule["datePattern"], "separator": so_rule["separator"],
    "seqLength": so_rule["seqLength"], "resetCycle": so_rule["resetCycle"], "enabled": True
}, token=admin)
tmp_order = call("POST", "/sales-orders", {
    "customerId": 1, "orderDate": TODAY_S, "deliveryDate": DELIVERY,
    "items": [{"productId": 2, "qty": 1, "price": 158}]
}, token=admin)
tmp_order_detail = call("GET", f"/sales-orders/{tmp_order['data']}", token=admin)["data"]
check("自定义编码规则生效", str(tmp_order_detail["orderNo"]).startswith("SOX"),
      tmp_order_detail["orderNo"])
call("PUT", f"/system/code-rules/{so_rule['id']}", {
    "prefix": "SO", "datePattern": so_rule["datePattern"], "separator": so_rule["separator"],
    "seqLength": so_rule["seqLength"], "resetCycle": so_rule["resetCycle"], "enabled": True
}, token=admin)

# ================= 11. 权限与数据范围 =================
section("11. 权限与数据范围")
sales_token = login("sales")
payload = call("POST", f"/work-orders/{wo_a['id']}/issue", {}, token=sales_token)
check("销售不能领料", denied(payload), (payload.get("_http"), payload.get("code")))
payload = call("POST", "/inspections", {
    "type": "PROCESS", "productId": 1, "inspectQty": 1, "defectQty": 0
}, token=sales_token)
check("销售不能检验", denied(payload), (payload.get("_http"), payload.get("code")))
qc_token2 = login("qc")
payload = call("POST", f"/work-orders/{wo_b['id']}/release", {}, token=qc_token2)
check("质检不能下达工单", denied(payload), (payload.get("_http"), payload.get("code")))
payload = call("GET", "/work-orders")
check("未登录返回 401", payload.get("_http") == 401 or payload.get("code") == 401,
      (payload.get("_http"), payload.get("code")))

sales_order_id = call("POST", "/sales-orders", {
    "customerId": 2, "orderDate": TODAY_S, "deliveryDate": DELIVERY,
    "items": [{"productId": 2, "qty": 2, "price": 158}]
}, token=sales_token)["data"]
sales_list = call("GET", "/sales-orders?page=1&size=50", token=sales_token)["data"]
sales_rows = sales_list.get("records") if isinstance(sales_list, dict) else sales_list
ids = [r["id"] for r in sales_rows]
check("销售数据范围只看本人", sales_order_id in ids and order_id not in ids,
      {"mine": sales_order_id, "admin": order_id, "seen": ids})
admin_list = call("GET", "/sales-orders?page=1&size=50", token=admin)["data"]
admin_rows = admin_list.get("records") if isinstance(admin_list, dict) else admin_list
check("管理员可见全部订单", len(admin_rows) >= len(sales_rows), (len(admin_rows), len(sales_rows)))

# ================= 12. 移动端接口 =================
section("12. 移动端共用接口")
mobile = login(QC_PHONE, "Qc@654321")
check("移动端手机号登录", bool(mobile))
payload = call("GET", f"/work-orders/by-no/{wo_a['workNo']}", token=mobile)
check("移动端查询工单", ok(payload) and payload["data"]["workNo"] == wo_a["workNo"],
      payload.get("message"))
payload = call("GET", "/work-orders?page=1&size=10", token=mobile)
check("移动端工单列表", ok(payload), payload.get("message"))

# ================= 汇总 =================
print()
print("================ 汇总 ================")
print("通过: %d 项" % PASS)
print("失败: %d 项" % len(FAILS))
for name in FAILS:
    print("  - " + name)
