"""齐套率与缺料联动端到端回归：
工单列表齐套字段与只看缺料、车间看板缺料预警、下达缺料自动报 MATERIAL 安灯、
到货入库后自动关安灯并提醒仓库可以领料。

脚本会造一张必然缺料的工单（新成品 + 大用量 BOM + 工艺路线）并入库补齐，
过程中留下测试成品、工单与一张入库单，方便演示查看。

用法：python scripts/e2e-readiness.py [http://localhost:8080]
"""

import json
import os
import sys
import urllib.error
import urllib.request
from datetime import date

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PASSED = 0
FAILED = []
TODAY = date.today().isoformat()


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as err:
        return json.loads(err.read().decode())


def login(username, password=None):
    # 强制改密之后 admin 的口令可能已经变了，用 MES_PASSWORD 传入
    password = password or os.environ.get("MES_PASSWORD", "123456")
    res = call("POST", "/auth/login", body={"username": username, "password": password})
    return res["data"]["token"] if res.get("code") == 0 else None


def ok(payload):
    return payload.get("code") == 0


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
    return float(value or 0)


def andon_calls(token, status):
    return call("GET", "/andon/calls?status=" + status, token).get("data") or []


def main():
    token = login("admin")
    if not token:
        print("登录失败，请确认服务已启动")
        sys.exit(1)

    section("1. 工单列表齐套字段")
    page = call("GET", "/work-orders?page=1&size=20", token).get("data") or {}
    rows = page.get("records") or []
    check("工单列表可查询", len(rows) > 0, page.get("total"))
    if rows:
        first = rows[0]
        check("列表带需求总量 materialNeedQty", "materialNeedQty" in first, list(first.keys())[:8])
        check("列表带待领量 materialPendingQty", "materialPendingQty" in first)
        check("列表带缺料行数 shortageLines", "shortageLines" in first)

    section("2. 只看缺料筛选")
    shortage_page = call("GET", "/work-orders?page=1&size=50&onlyShortage=true", token).get("data") or {}
    shortage_rows = shortage_page.get("records") or []
    check("只看缺料有返回结构", "total" in shortage_page, shortage_page)
    check("只看缺料结果都真的缺料",
          all(int(row.get("shortageLines") or 0) > 0 for row in shortage_rows),
          [row.get("workNo") for row in shortage_rows[:3]])
    check("只看缺料不会多于全部工单",
          int(shortage_page.get("total") or 0) <= int(page.get("total") or 0),
          (shortage_page.get("total"), page.get("total")))

    section("3. 车间看板缺料预警 KPI")
    board = call("GET", "/workshop/board", token).get("data") or {}
    kpi = board.get("kpi") or {}
    check("看板有 shortageOrders 指标", "shortageOrders" in kpi, list(kpi.keys()))
    check("缺料预警指标不小于 0", int(kpi.get("shortageOrders") or 0) >= 0, kpi.get("shortageOrders"))

    section("4. 造一张必然缺料的工单")
    products = call("GET", "/products", token).get("data") or []
    template = next((p for p in products if p.get("code") == "P001"), None)
    check("演示数据里有成品 P001 可复制路线", template is not None)
    if not template:
        return
    suffix = str(int.from_bytes(os.urandom(2), "big"))
    # 专用原料，库存为 0，保证一定是缺料；用量小，不干扰演示库存
    raw_created = call("POST", "/products", token, {
        "code": "RDYM" + suffix, "name": "齐套测试原料" + suffix, "spec": "RDY", "category": "RAW",
        "unit": "个", "price": 1, "cost": 1, "safetyStock": 0, "lossRate": 0
    })
    check("创建测试原料", ok(raw_created), raw_created.get("message"))
    raw_id = raw_created.get("data")
    created = call("POST", "/products", token, {
        "code": "RDY" + suffix, "name": "齐套测试件" + suffix, "spec": "RDY", "category": "FINISHED",
        "unit": "件", "price": 10, "cost": 5, "safetyStock": 0, "lossRate": 0
    })
    check("创建测试成品", ok(created), created.get("message"))
    product_id = created.get("data")
    bom = call("PUT", "/products/%s/bom" % product_id, token, {
        "items": [{"materialId": raw_id, "qty": 100, "operationSeq": 1}]
    })
    check("写入 BOM", ok(bom), bom.get("message"))
    routing = call("POST", "/products/%s/routing/copy" % product_id, token,
                   {"sourceProductId": template["id"], "overwrite": True})
    check("复制工艺路线", ok(routing), routing.get("message"))
    work_id = call("POST", "/work-orders", token, {
        "productId": product_id, "planQty": 1, "planStart": TODAY, "planEnd": TODAY
    }).get("data")
    call("POST", "/scheduling/%s/schedule" % work_id, token, {"date": TODAY})

    detail = call("GET", "/work-orders/%s" % work_id, token).get("data") or {}
    work_no = detail.get("workNo")
    shortage_lines = [line for line in (detail.get("bom") or [])
                      if str(line.get("source") or "") != "SUBSTITUTE"
                      and num(line.get("needQty")) > num(line.get("issuedQty"))]
    check("工单确实有待领物料", len(shortage_lines) > 0, work_no)
    pending_qty = num(shortage_lines[0].get("needQty")) if shortage_lines else 0

    section("5. 下达缺料自动报物料安灯")
    released = call("POST", "/work-orders/%s/release" % work_id, token, {})
    check("工单下达成功", ok(released), released.get("message"))
    open_calls = [c for c in andon_calls(token, "OPEN") if c.get("workOrderId") == work_id]
    check("下达后自动生成物料安灯", len(open_calls) == 1, len(open_calls))
    if open_calls:
        auto_call = open_calls[0]
        check("安灯类型是 MATERIAL", auto_call.get("type") == "MATERIAL", auto_call.get("type"))
        check("安灯发起人是系统", auto_call.get("caller") == "系统", auto_call.get("caller"))
        check("安灯描述说明库存不足",
              str(auto_call.get("description") or "").startswith("下达时库存不足"),
              auto_call.get("description"))
        check("安灯描述带物料名称", "齐套测试原料" + suffix in str(auto_call.get("description")),
              auto_call.get("description"))

    section("6. 重复下达不会刷屏")
    again = call("POST", "/work-orders/%s/release" % work_id, token, {})
    check("重复下达被拒绝(2005)", again.get("code") == 2005, again.get("message"))
    still = [c for c in andon_calls(token, "OPEN") if c.get("workOrderId") == work_id]
    check("同一工单只有一条未关闭物料安灯", len(still) == 1, len(still))

    section("7. 到货入库补齐物料")
    warehouses = call("GET", "/warehouses", token).get("data") or []
    raw_warehouse = next((w for w in warehouses if w.get("type") == "RAW"), None)
    check("存在原料仓", raw_warehouse is not None)
    if not raw_warehouse or pending_qty <= 0:
        return
    inbound = call("POST", "/inbound-orders", token, {
        "type": "OTHER", "warehouseId": raw_warehouse["id"], "supplier": "齐套测试供应商",
        "needInspection": False, "remark": "齐套联动回归",
        "noOrderReason": "齐套联动回归测试入库",
        "items": [{"productId": raw_id, "qty": pending_qty}],
    })
    check("创建入库单", ok(inbound), inbound.get("message"))
    inbound_id = inbound.get("data")
    submitted = call("POST", "/inbound-orders/%s/submit" % inbound_id, token, {})
    check("入库单提交并完成入库", ok(submitted), submitted.get("message"))

    section("8. 入库后自动关安灯并提醒可领料")
    resolved = [c for c in andon_calls(token, "RESOLVED") if c.get("workOrderId") == work_id]
    check("缺料安灯自动关闭", len(resolved) >= 1, len(resolved))
    if resolved:
        check("安灯由系统关闭", resolved[0].get("handler") == "系统", resolved[0].get("handler"))
    remaining_open = [c for c in andon_calls(token, "OPEN") if c.get("workOrderId") == work_id]
    check("该工单没有残留未关闭安灯", len(remaining_open) == 0, len(remaining_open))

    warehouse_token = login("warehouse")
    check("仓库账号可登录", warehouse_token is not None)
    if warehouse_token:
        notices = call("GET", "/notices?limit=100", warehouse_token).get("data") or []
        hit = [n for n in notices
               if "可以领料" in str(n.get("title") or "") and n.get("refNo") == work_no]
        check("仓库收到本工单的可领料提醒", len(hit) >= 1,
              [n.get("title") for n in notices[:3]])

    print("\n" + ("全部通过：%d 项" % PASSED if not FAILED else "失败 %d 项：%s" % (len(FAILED), FAILED)))
    if FAILED:
        sys.exit(1)


if __name__ == "__main__":
    main()
