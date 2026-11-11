"""默认供应商 + 无工艺路线手工加工序端到端回归：

1. 产品档案维护「默认供应商」，产品列表接口要带出 defaultSupplierId；
2. 采购单不选供应商时按明细物料的默认供应商兜底，明细指向多个供应商时报错要求手选，
   手工选了供应商则以手工为准；
3. 没有工艺路线的产品建单不再被拒，工序为空，可在工单里逐道手工添加与删除。

脚本会造测试产品 / 采购单 / 一张工单，结束时作废工单并删掉能删的单据；
产品仍被已作废工单引用时删不掉，会留在列表里，可手工清理。

用法：python scripts/e2e-product-supplier.py [http://localhost:8080]
"""

import json
import os
import sys
import time
import urllib.error
import urllib.parse
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
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as err:
        return json.loads(err.read().decode() or "{}")


def login(username, password=None):
    # 强制改密之后 admin 的口令可能已经变了，用 MES_PASSWORD 传入
    password = password or os.environ.get("MES_PASSWORD", "123456")
    res = call("POST", "/auth/login", body={"username": username, "password": password})
    return res["data"]["token"] if res.get("code") == 0 else None


def ok(payload):
    return payload.get("code") == 0


def check(name, condition, detail=""):
    global PASSED
    if condition:
        PASSED += 1
        print("  [OK] " + name)
    else:
        FAILED.append(name)
        print("  [FAIL] " + name + ("  " + str(detail) if detail else ""))


def rows_of(payload):
    data = payload.get("data")
    if isinstance(data, dict):
        return data.get("records") or data.get("rows") or []
    return data or []


token = login("admin")
if not token:
    raise SystemExit("登录失败：请确认 admin 口令（可先设置 MES_PASSWORD）")
check("管理员登录", True)

suffix = str(int(time.time()))[-6:]

suppliers = rows_of(call("GET", "/suppliers?enabled=true", token=token))
check("供应商下拉有数据", bool(suppliers))
default_supplier = suppliers[0]

warehouses = rows_of(call("GET", "/warehouses", token=token))
raw_warehouse = [w for w in warehouses if w.get("type") == "RAW"][0]

products = rows_of(call("GET", "/products", token=token))
check("产品列表带 defaultSupplierId 字段", bool(products) and "defaultSupplierId" in products[0])

created_products = []
for tag, supplier_id in (("甲", default_supplier["id"]), ("乙", None)):
    res = call("POST", "/products", token=token, body={
        "code": "PS" + suffix + ("A" if supplier_id else "B"),
        "name": "默认供应商测试" + tag + suffix, "spec": "PS-T", "category": "RAW", "unit": "件",
        "price": 10, "cost": 5, "safetyStock": 0, "lossRate": 0,
        "traceMode": "NONE", "defaultSupplierId": supplier_id,
    })
    check("建产品" + tag + "（默认供应商=" + ("有" if supplier_id else "无") + "）", ok(res), res)
    created_products.append(res["data"] if ok(res) else None)
with_default, without_default = created_products

if None in created_products:
    raise SystemExit("产品没建出来，后续用例无从验证")

listed = [p for p in rows_of(call("GET", "/products?keyword=PS" + suffix, token=token))
          if p["id"] == with_default]
check("产品列表回读默认供应商", bool(listed) and listed[0].get("defaultSupplierId") == default_supplier["id"], listed)

created_orders = []


def new_order(body):
    res = call("POST", "/purchase-orders", token=token, body=body)
    if ok(res):
        created_orders.append(res["data"])
    return res


res = new_order({"warehouseId": raw_warehouse["id"],
                 "items": [{"productId": with_default, "qty": 2, "price": 3}]})
check("不选供应商也能建采购单", ok(res), res)
if ok(res):
    detail = call("GET", "/purchase-orders/%s" % res["data"], token=token)["data"]
    check("自动带出默认供应商", detail.get("supplierId") == default_supplier["id"], detail.get("supplierId"))

res = new_order({"warehouseId": raw_warehouse["id"],
                 "items": [{"productId": without_default, "qty": 1, "price": 1}]})
check("物料没有默认供应商时要求手选", not ok(res) and "请选择供应商" in str(res), res)

res = new_order({"supplierId": default_supplier["id"], "warehouseId": raw_warehouse["id"],
                 "items": [{"productId": without_default, "qty": 1, "price": 1}]})
check("手工选供应商可正常建单", ok(res), res)
if ok(res):
    detail = call("GET", "/purchase-orders/%s" % res["data"], token=token)["data"]
    check("手工选的供应商不被默认值覆盖", detail.get("supplierId") == default_supplier["id"], detail.get("supplierId"))

work_id = None
res = call("POST", "/work-orders", token=token, body={"productId": without_default, "planQty": 3})
check("没有工艺路线也能建工单", ok(res), res)
if ok(res):
    work_id = res["data"]
    detail = call("GET", "/work-orders/%s" % work_id, token=token)["data"]
    check("工序为空，没有退回硬编码工序", len(detail.get("operations") or []) == 0, detail.get("operations"))

    processes = rows_of(call("GET", "/processes?enabled=true", token=token))
    added = call("POST", "/work-orders/%s/operations" % work_id, token=token,
                 body={"processId": processes[0]["id"]})
    check("手工加工序成功", ok(added), added)
    detail = call("GET", "/work-orders/%s" % work_id, token=token)["data"]
    check("工序已写入工单", len(detail.get("operations") or []) == 1, detail.get("operations"))

    if ok(added):
        second = call("POST", "/work-orders/%s/operations" % work_id, token=token,
                      body={"processId": processes[0]["id"], "planQty": 2,
                            "minutesPerPiece": 8, "price": 1.5, "needInspection": True})
        check("再加工序序号顺延", ok(second) and second["data"]["seq"] == 2, second)
        detail = call("GET", "/work-orders/%s" % work_id, token=token)["data"]
        check("工单有两道工序", len(detail.get("operations") or []) == 2)

        dropped = call("DELETE", "/work-orders/%s/operations/%s"
                       % (work_id, second["data"]["seq"]), token=token)
        check("未下达可以删工序", ok(dropped), dropped)

        bad = call("POST", "/work-orders/%s/operations" % work_id, token=token, body={})
        check("不选工序也不填名称时被拦", not ok(bad), bad)

# 清理：作废工单，删采购单与能删的产品
if work_id:
    call("POST", "/work-orders/%s/cancel?reason=%s" % (work_id, urllib.parse.quote("默认供应商回归清理")), token=token)
for order_id in created_orders:
    call("DELETE", "/purchase-orders/%s" % order_id, token=token)
for product_id in created_products:
    call("DELETE", "/products/%s" % product_id, token=token)

print("\n================ 汇总 ================")
print("通过：%d 项，失败：%d 项" % (PASSED, len(FAILED)))
sys.exit(0 if not FAILED else 1)
