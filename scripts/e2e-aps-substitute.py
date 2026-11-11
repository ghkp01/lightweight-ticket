import json
import urllib.request
import urllib.error
from datetime import date, timedelta

BASE = "http://localhost:8080/api"
TODAY = date.today()
TODAY_S = TODAY.isoformat()
DELIVERY = (TODAY + timedelta(days=7)).isoformat()
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


token = call("POST", "/auth/login", {"username": "admin", "password": "123456"})["data"]["token"]


def stock(code):
    data = call("GET", "/inventory?keyword=" + code, token=token)["data"]
    rows = data.get("records") if isinstance(data, dict) else data
    return sum(float(r["qty"]) for r in rows if r["productCode"] == code)


def outbound(product_id, qty):
    oid = call("POST", "/outbound-orders", {
        "type": "MANUAL", "warehouseId": 1, "remark": "压库存用于替代料验证",
        "items": [{"productId": product_id, "qty": qty}]
    }, token=token)["data"]
    return call("POST", f"/outbound-orders/{oid}/complete", {}, token=token)


def ensure_stock(code, product_id, target):
    current = stock(code)
    if current >= target:
        return
    oid = call("POST", "/inbound-orders", {
        "type": "OTHER", "warehouseId": 1, "supplier": "E2E 补料", "needInspection": False,
        "remark": "回归测试准备库存", "noOrderReason": "替代料脚本准备库存，无采购单",
        "items": [{"productId": product_id, "qty": target - current, "batchNo": "B-E2E"}]
    }, token=token)["data"]
    call("POST", f"/inbound-orders/{oid}/submit", {}, token=token)


def new_work_order(product_id, qty):
    wid = call("POST", "/work-orders", {
        "productId": product_id, "planQty": qty, "planStart": TODAY_S, "planEnd": DELIVERY
    }, token=token)["data"]
    call("POST", f"/scheduling/{wid}/schedule", {"date": TODAY_S}, token=token)
    call("POST", f"/work-orders/{wid}/release", {}, token=token)
    return wid


products = call("GET", "/products", token=token)["data"]
sub_id = [p["id"] for p in products if p["code"] == "P009"][0]

section("1. 替代料主数据")
bom = call("GET", "/products/1/bom", token=token)["data"]
seal = [row for row in bom if row["materialCode"] == "P006"][0]
check("BOM 行带替代料", seal.get("substituteMaterialId") == sub_id
      and "密封圈" in str(seal.get("substituteMaterialName")),
      (seal.get("substituteMaterialId"), seal.get("substituteMaterialName")))
saved = call("PUT", "/products/1/bom", {
    "items": [{"materialId": r["materialId"], "qty": float(r["qty"]),
               "operationSeq": r["operationSeq"], "substituteGroup": r["substituteGroup"],
               "isKey": r["isKey"], "remark": r["remark"],
               "substituteMaterialId": r.get("substituteMaterialId")} for r in bom]
}, token=token)
check("BOM 保存替代料", ok(saved), saved.get("message"))
bom2 = call("GET", "/products/1/bom", token=token)["data"]
check("替代料回读",
      [r.get("substituteMaterialId") for r in bom2 if r["materialCode"] == "P006"] == [sub_id],
      [(r["materialCode"], r.get("substituteMaterialId")) for r in bom2])

section("2. 主料不足自动用替代料")
ensure_stock("P006", 6, 5000)
ensure_stock("P009", sub_id, 2000)
p006 = stock("P006")
if p006 > 10:
    resp = outbound(6, p006 - 10)
    check("压减主料库存", ok(resp), resp.get("message"))
check("主料库存已压低", abs(stock("P006") - 10) < 0.01, stock("P006"))
p009_before = stock("P009")

wo_a = new_work_order(1, 20)
wo_a_data = call("GET", f"/work-orders/{wo_a}", token=token)["data"]
seal_line = [r for r in wo_a_data["bom"] if r["materialCode"] == "P006"][0]
check("工单 BOM 带替代料信息", seal_line.get("substituteMaterialId") == sub_id
      and seal_line.get("substituteMaterialName") is not None,
      (seal_line.get("substituteMaterialId"), seal_line.get("substituteStock")))
sub_lines = [r for r in wo_a_data["bom"] if r["source"] == "SUBSTITUTE"]
check("工单 BOM 生成替代行", len(sub_lines) == 1 and float(sub_lines[0]["needQty"]) == 0,
      [(r["materialName"], r["needQty"]) for r in sub_lines])

issued = call("POST", f"/work-orders/{wo_a}/issue", {}, token=token)
check("整单领料成功", ok(issued), issued.get("message"))
wo_a_data = call("GET", f"/work-orders/{wo_a}", token=token)["data"]
seal_line = [r for r in wo_a_data["bom"] if r["materialCode"] == "P006"][0]
sub_line = [r for r in wo_a_data["bom"] if r["source"] == "SUBSTITUTE"][0]
check("主料已领 40.4（含替代）", abs(float(seal_line["issuedQty"]) - 40.4) < 0.01,
      seal_line["issuedQty"])
check("主料库存清零", abs(stock("P006")) < 0.01, stock("P006"))
check("替代料补足缺口", abs(float(sub_line["issuedQty"]) - 30.4) < 0.01
      and abs(p009_before - stock("P009") - 30.4) < 0.01,
      (sub_line["issuedQty"], p009_before, stock("P009")))
# 库存流水接口只按物料 / 类型过滤，没有 refNo 参数；先按替代料拉流水，再按工单号筛
txns = call("GET", f"/inventory/txns?productId={sub_id}&txnType=ISSUE&page=1&size=100", token=token)
txn_data = txns.get("data") or {}
txn_rows = txn_data.get("records") if isinstance(txn_data, dict) else txn_data
wo_txn = [t for t in (txn_rows or []) if t.get("refNo") == wo_a_data["workNo"]]
sub_txn = [t for t in wo_txn if "替代发料" in str(t.get("remark"))]
check("替代发料有流水备注", len(sub_txn) >= 1,
      [t.get("remark") for t in (txn_rows or [])][:3])

section("3. 主料与替代料都不足")
p009_now = stock("P009")
if p009_now > 0:
    resp = outbound(sub_id, p009_now)
    check("清空替代料库存", ok(resp), resp.get("message"))
check("替代料已清空", abs(stock("P009")) < 0.01, stock("P009"))
wo_c = new_work_order(1, 10)
fail = call("POST", f"/work-orders/{wo_c}/issue", {}, token=token)
check("主料与替代料合计不足拦截", fail.get("code") == 3003
      and "合计仍不足" in str(fail.get("message")), fail.get("message"))

section("4. 按物料领料自动带替代料")
inbound = call("POST", "/inbound-orders", {
    "type": "OTHER", "warehouseId": 1, "supplier": "备件供应商", "needInspection": False,
    "noOrderReason": "替代料脚本补备件库存，无采购单",
    "items": [{"productId": sub_id, "qty": 100, "batchNo": "B-SUB"}]
}, token=token)["data"]
call("POST", f"/inbound-orders/{inbound}/submit", {}, token=token)
partial = call("POST", f"/work-orders/{wo_c}/issue-item", {"productId": 6, "qty": 10}, token=token)
data = partial.get("data") or {}
# issue-item 返回的是一张领料单，主料 / 替代料的数量在明细行上
slip_line = (data.get("lines") or [{}])[0]
check("主料为 0 时全部用替代料", ok(partial) and float(slip_line.get("mainQty", -1)) == 0
      and abs(float(slip_line.get("alternativeQty", 0)) - 10) < 0.01, slip_line)
check("领料返回替代料名称", "密封圈" in str(slip_line.get("alternativeMaterialName")),
      slip_line.get("alternativeMaterialName"))

section("5. 替代行不允许单独调整")
sub_bom_id = sub_line["bomId"]
sub_material_id = sub_line["materialId"]
resp = call("POST", f"/work-orders/{wo_a}/bom", {
    "materialId": sub_material_id, "planQty": 5, "operationSeq": 3, "reason": "尝试调整替代料"
}, token=token)
check("替代行禁止调整", resp.get("code") == 1002 and "替代料" in str(resp.get("message")),
      resp.get("message"))
resp = call("DELETE", f"/work-orders/{wo_a}/bom/{sub_bom_id}?reason=test", token=token)
check("替代行禁止删除", resp.get("code") == 1002 and "替代料" in str(resp.get("message")),
      resp.get("message"))

section("6. 有限产能排产")
draft_ids = []
for product_id, qty in ((1, 50), (2, 40), (1, 30)):
    draft_ids.append(call("POST", "/work-orders", {
        "productId": product_id, "planQty": qty, "planStart": TODAY_S, "planEnd": DELIVERY
    }, token=token)["data"])

payload = call("POST", "/scheduling/aps", {"startDate": TODAY_S, "days": 7}, token=token)
aps = payload.get("data") or {}
if (aps.get("scheduledCount") or 0) < len(draft_ids):
    # 回归库里历史工单会把 7 天窗口占满，放宽到 60 天再排一次
    payload = call("POST", "/scheduling/aps", {"startDate": TODAY_S, "days": 60}, token=token)
    aps = payload.get("data") or {}
check("有限产能排产执行", ok(payload) and aps.get("scheduledCount", 0) >= len(draft_ids),
      (aps.get("scheduledCount"), aps.get("skippedCount")))
assignments = aps.get("assignments") or []
check("工序级排程返回", len(assignments) >= len(draft_ids) * 3, len(assignments))
check("排程含工作中心与起止时间",
      all(a.get("workCenterName") and a.get("startAt") and a.get("capacity") for a in assignments),
      assignments[:1])
loads = aps.get("dailyLoad") or []
check("本次排入工序不超工作中心日产能",
      all(int(a["minutes"]) <= int(a["capacity"]) for a in assignments if a.get("capacity")),
      [a for a in assignments if int(a["minutes"]) > int(a["capacity"] or 0)][:3])
check("超载负荷行带标记",
      all((int(r["minutes"]) > int(r["capacity"])) == bool(r.get("overloaded")) for r in loads),
      [(r["date"], r.get("workCenterName"), r["minutes"], r["capacity"]) for r in loads][:5])
by_work = {}
for row in assignments:
    by_work.setdefault(row["workNo"], []).append(row)
ordered = True
for rows in by_work.values():
    rows.sort(key=lambda r: r["seq"])
    for i in range(1, len(rows)):
        if str(rows[i]["startAt"]) < str(rows[i - 1]["endAt"]):
            ordered = False
check("工序按顺序衔接", ordered,
      {k: [(r["seq"], r["startAt"], r["endAt"]) for r in v] for k, v in list(by_work.items())[:2]})

for wid in draft_ids:
    detail = call("GET", f"/work-orders/{wid}", token=token)["data"]
    if detail["status"] != "SCHEDULED":
        check("工单进入已排产", False, (detail["workNo"], detail["status"]))
        break
else:
    check("工单进入已排产", True)

board = call("GET", "/scheduling/board", token=token)["data"]
check("排产看板刷新", "scheduled" in board and "unscheduled" in board,
      (len(board.get("scheduled", [])), len(board.get("unscheduled", []))))

# ---------------------------------------------------------------- 甘特兜底：整单排产
section("甘特：只做了整单排产的单用灰条兜底")
gantt = call("GET", f"/aps/gantt?from={TODAY_S}&to={DELIVERY}", token=token)["data"]
gantt_rows = gantt.get("centers") or []
gantt_summary = gantt.get("summary") or {}
order_rows = [row for row in gantt_rows if row.get("orderLevel")]
check("甘特里有整单排产行", bool(order_rows), [row.get("name") for row in gantt_rows])
check("summary 给出未到工序级的单数", int(gantt_summary.get("orderLevelCount") or 0) > 0, gantt_summary)

order_level_bars = []
for row in order_rows:
    for cell in row.get("cells") or []:
        order_level_bars.extend(cell.get("bars") or [])
check("整单条带 orderLevel 标记",
      bool(order_level_bars) and all(bar.get("orderLevel") for bar in order_level_bars),
      order_level_bars[:3])
check("整单行不算负荷率",
      all(cell.get("rate") is None for row in order_rows for cell in row.get("cells") or []),
      [cell.get("rate") for row in order_rows for cell in row.get("cells") or []][:3])
check("整单行不计入工作中心数",
      int(gantt_summary.get("centers") or 0) == len(gantt_rows) - (1 if order_rows else 0),
      (gantt_summary.get("centers"), len(gantt_rows)))

order_level_nos = {bar.get("workNo") for bar in order_level_bars}
aps_nos = {row.get("workNo") for row in assignments}
check("已做有限产能排产的单不再出现整单灰条", not (order_level_nos & aps_nos),
      sorted(order_level_nos & aps_nos))

print()
print("================ 汇总 ================")
print("通过: %d 项" % PASS)
print("失败: %d 项" % len(FAILS))
for name in FAILS:
    print("  - " + name)
