import json
import urllib.request
import urllib.parse
import urllib.error
from datetime import date, timedelta

BASE = "http://localhost:8080/api"
TODAY = date.today().isoformat()
DELIVERY = (date.today() + timedelta(days=7)).isoformat()
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


def ensure_stock(code, product_id, target):
    current = stock(code)
    if current >= target:
        return
    oid = call("POST", "/inbound-orders", {
        "type": "OTHER", "warehouseId": 1, "supplier": "初始化验证补料", "needInspection": False,
        "noOrderReason": "路由初始化脚本准备库存，无采购单",
        "items": [{"productId": product_id, "qty": target - current}]
    }, token=token)["data"]
    call("POST", f"/inbound-orders/{oid}/submit", {}, token=token)


products = call("GET", "/products", token=token)["data"]
by_code = {p["code"]: p for p in products}
ensure_stock("P003", by_code["P003"]["id"], 2000)
ensure_stock("P004", by_code["P004"]["id"], 500)
ensure_stock("P005", by_code["P005"]["id"], 5000)
ensure_stock("P006", by_code["P006"]["id"], 2000)

section("1. 工艺路线初始化与变更记录")
code = "TI" + str(int.from_bytes(__import__("os").urandom(2), "big"))
product_id = call("POST", "/products", {
    "code": code, "name": "工艺初始化测试件", "spec": "T", "category": "FINISHED",
    "unit": "件", "price": 10, "cost": 5, "safetyStock": 0, "lossRate": 0.01
}, token=token)["data"]
check("新建产品无工艺路线", call("GET", f"/products/{product_id}/routing", token=token)["data"] == [])

copied = call("POST", f"/products/{product_id}/routing/copy",
              {"sourceProductId": by_code["P001"]["id"], "overwrite": False}, token=token)
check("从其他产品复制路线", ok(copied) and copied["data"]["copied"] == 3
      and copied["data"]["summary"] == "下料→机加工→装配", copied.get("data") or copied.get("message"))
routing = call("GET", f"/products/{product_id}/routing", token=token)["data"]
check("复制后路线回读", [r["processName"] for r in routing] == ["下料", "机加工", "装配"],
      [r["processName"] for r in routing])

payload = call("POST", f"/products/{product_id}/routing/copy",
               {"sourceProductId": by_code["P001"]["id"], "overwrite": False}, token=token)
check("已有路线不覆盖时拦截", payload.get("code") == 2004, payload.get("message"))
payload = call("POST", f"/products/{product_id}/routing/copy",
               {"sourceProductId": product_id, "overwrite": True}, token=token)
check("不能从自身复制", payload.get("code") == 1002, payload.get("message"))

changes = call("GET", f"/products/{product_id}/routing-changes", token=token)["data"]
check("复制写入变更记录", len(changes) == 1 and changes[0]["action"] == "COPY"
      and changes[0]["newSummary"] == "下料→机加工→装配", changes)
call("PUT", f"/products/{product_id}/routing", {"items": [
    {"seq": 1, "processId": by_code and [r for r in call("GET", "/processes", token=token)["data"] if r["code"] == "CUT"][0]["id"]},
    {"seq": 2, "processId": [r for r in call("GET", "/processes", token=token)["data"] if r["code"] == "MACH"][0]["id"]}
]}, token=token)
changes = call("GET", f"/products/{product_id}/routing-changes", token=token)["data"]
check("调整路线写入变更记录", len(changes) == 2 and changes[0]["action"] == "UPDATE"
      and changes[0]["newSummary"] == "下料→机加工", changes[:2])

section("2. 按工序领料")
wid = call("POST", "/work-orders", {
    "productId": by_code["P001"]["id"], "planQty": 10, "planStart": TODAY, "planEnd": DELIVERY
}, token=token)["data"]
call("POST", f"/scheduling/{wid}/schedule", {"date": TODAY}, token=token)
call("POST", f"/work-orders/{wid}/release", {}, token=token)
detail = call("GET", f"/work-orders/{wid}", token=token)["data"]
op_start = {o["seq"]: o for o in detail["operations"]}
check("工序带齐套标识", op_start[1]["materialReady"] is False
      and op_start[1]["materialRequired"] is True
      and op_start[2]["materialRequired"] is False
      and op_start[3]["materialReady"] is False,
      [(o["name"], o["materialReady"], o["materialRequired"]) for o in detail["operations"]])

issued1 = call("POST", f"/work-orders/{wid}/issue-operation", {"operationSeq": 1}, token=token)
check("按工序领料(下料)", ok(issued1) and float(issued1["data"]["totalQty"]) > 0, issued1.get("data"))
detail = call("GET", f"/work-orders/{wid}", token=token)["data"]
op_map = {o["seq"]: o for o in detail["operations"]}
check("下料已齐套", op_map[1]["materialReady"] is True, op_map[1]["materialPendingQty"])
check("后道仍未领", op_map[3]["materialReady"] is False, op_map[3]["materialPendingQty"])

payload = call("POST", f"/work-orders/{wid}/issue-operation", {"operationSeq": 2}, token=token)
check("无投料工序报错", payload.get("code") == 2008, payload.get("message"))

issued3 = call("POST", f"/work-orders/{wid}/issue-operation", {"operationSeq": 3}, token=token)
check("按工序领料(装配)", ok(issued3)
      and {line["materialName"] for line in issued3["data"]["lines"]} == {"标准螺栓", "密封圈"},
      issued3.get("data"))
detail = call("GET", f"/work-orders/{wid}", token=token)["data"]
check("全部工序齐套", all(o["materialReady"] is True for o in detail["operations"]),
      [(o["name"], o["materialReady"]) for o in detail["operations"]])

section("3. 非标 BOM 差异")
diff = call("GET", f"/work-orders/{wid}/bom-diff", token=token)["data"]
check("未调整时全部一致", all(row["changeType"] == "SAME" for row in diff),
      [(row["materialName"], row["changeType"]) for row in diff])

adjust = call("POST", f"/work-orders/{wid}/bom", {
    "materialId": by_code["P003"]["id"], "planQty": 25, "operationSeq": 1,
    "reason": "客户要求加厚，型材用量上调"
}, token=token)
check("调整返回需补领数量", ok(adjust) and abs(float(adjust["data"]["needIssueQty"]) - (25 - 8.08)) < 0.01,
      adjust.get("data"))

call("POST", f"/work-orders/{wid}/bom", {
    "materialId": by_code["P004"]["id"], "planQty": 2, "operationSeq": 2,
    "remark": "非标加装底板", "reason": "非标结构增加底板"
}, token=token)
diff = call("GET", f"/work-orders/{wid}/bom-diff", token=token)["data"]
by_material = {row["materialName"]: row for row in diff}
check("差异识别改量", by_material["铝合金型材"]["changeType"] == "CHANGED"
      and abs(float(by_material["铝合金型材"]["diffQty"]) - (25 - 8.08)) < 0.01,
      {key: by_material["铝合金型材"][key] for key in ("changeType", "standardQty", "currentQty", "diffQty")})
check("差异识别新增", by_material["不锈钢板材"]["changeType"] == "ADDED"
      and float(by_material["不锈钢板材"]["standardQty"]) == 0,
      by_material["不锈钢板材"])

new_bom = [row for row in call("GET", f"/work-orders/{wid}", token=token)["data"]["bom"]
           if row["materialCode"] == "P004"][0]
payload = call("DELETE", f"/work-orders/{wid}/bom/{new_bom['bomId']}?reason="
               + urllib.parse.quote("客户取消底板"), token=token)
check("删除非标行", ok(payload), payload.get("message"))
diff = call("GET", f"/work-orders/{wid}/bom-diff", token=token)["data"]
check("删除后差异不再包含该物料",
      "不锈钢板材" not in {row["materialName"] for row in diff},
      [row["materialName"] for row in diff])

main_line = [row for row in call("GET", f"/work-orders/{wid}", token=token)["data"]["bom"]
             if row["materialCode"] == "P003"][0]
payload = call("DELETE", f"/work-orders/{wid}/bom/{main_line['bomId']}?reason=test", token=token)
check("已领料行不能删除", payload.get("code") == 2016, payload.get("message"))

print()
print("================ 汇总 ================")
print("通过: %d 项" % PASS)
print("失败: %d 项" % len(FAILS))
for name in FAILS:
    print("  - " + name)
