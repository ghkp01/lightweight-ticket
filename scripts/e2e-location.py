"""库位管理端到端回归：库位维护与唯一性、入库上架、整批与部分移库、跨仓与超量拦截、有库存不可删。

用法：python scripts/e2e-location.py [http://localhost:8080]
"""

import json
import sys
import time
import urllib.error
import urllib.request
import os

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PASSED = 0
FAILED = []
TAG = str(int(time.time()))[-6:]


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
    # 强制改密之后 admin 的口令可能已经变了，用 MES_PASSWORD 传入
    password = password or os.environ.get("MES_PASSWORD", "123456")
    status, res = call("POST", "/auth/login", body={"username": username, "password": password})
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


admin = login("admin")
check("管理员登录", admin is not None)

_, warehouses = call("GET", "/warehouses", admin)
raw = next((w for w in (warehouses.get("data") or []) if w.get("type") == "RAW"), None)
fin = next((w for w in (warehouses.get("data") or []) if w.get("type") == "FINISHED"), None)
check("找到原料仓与成品仓", raw is not None and fin is not None, [w.get("code") for w in (warehouses.get("data") or [])])

print("\n== 库位维护 ==")
status, loc_a = call("POST", "/locations", admin, {
    "warehouseId": raw["id"], "code": "A-" + TAG, "name": "A 区货位", "zone": "A 区", "enabled": True
})
a_id = (loc_a.get("data") or {}).get("id")
check("新增库位 A", ok(loc_a) and a_id, loc_a)
status, loc_b = call("POST", "/locations", admin, {
    "warehouseId": raw["id"], "code": "B-" + TAG, "name": "B 区货位", "zone": "B 区", "enabled": True
})
b_id = (loc_b.get("data") or {}).get("id")
check("新增库位 B", ok(loc_b) and b_id, loc_b)

status, dup = call("POST", "/locations", admin, {
    "warehouseId": raw["id"], "code": "A-" + TAG, "name": "重名库位"
})
check("同仓库库位编码重复被拒", dup.get("code") == 4002, dup)
status, no_code = call("POST", "/locations", admin, {"warehouseId": raw["id"], "code": " "})
check("库位编码必填", no_code.get("code") == 1002, no_code)

status, options = call("GET", f"/locations/options?warehouseId={raw['id']}", admin)
check("库位下拉可读", any(item.get("id") == a_id for item in (options.get("data") or [])), len(options.get("data") or []))

print("\n== 入库上架 ==")
_, products = call("GET", "/products?keyword=P003&size=5", admin)
product_rows = products.get("data")
if isinstance(product_rows, dict):
    product_rows = product_rows.get("records") or []
product = next((p for p in (product_rows or []) if p.get("code") == "P003"), None)
check("找到测试物料 P003", product is not None, products.get("message"))
batch_no = "LOC" + TAG
status, inbound = call("POST", "/inbound-orders", admin, {
    "type": "OTHER", "warehouseId": raw["id"], "supplier": None, "needInspection": False,
    "remark": "库位回归", "noOrderReason": "库位管理回归测试",
    "items": [{"productId": product["id"], "qty": 6, "batchNo": batch_no,
               "unitPrice": 20, "locationCode": "A-" + TAG}]
})
inbound_id = inbound.get("data")
check("建入库单（带库位）", ok(inbound) and inbound_id, inbound)
status, submitted = call("POST", f"/inbound-orders/{inbound_id}/submit", admin)
check("入库单提交入库", ok(submitted), submitted)

status, batch_rows = call("GET", f"/locations/batches?warehouseId={raw['id']}&keyword={batch_no}", admin)
rows = batch_rows.get("data") or []
target = next((row for row in rows if row.get("batchNo") == batch_no), None)
check("批次已上架到指定库位", target and target.get("locationCode") == "A-" + TAG, target)

status, inventory = call("GET", f"/inventory?keyword=P003", admin)
inv_row = next((row for row in (inventory.get("data") or []) if row.get("warehouseId") == raw["id"]), None)
stock_qty = float(inv_row.get("qty") or 0) if inv_row else 0
check("库存总量含本次入库", stock_qty >= 6, stock_qty)

print("\n== 移库 ==")
status, moved = call("POST", "/locations/move", admin, {
    "productId": product["id"], "warehouseId": raw["id"], "batchNo": batch_no,
    "qty": 2, "toLocationId": b_id, "remark": "分两件到 B 区"
})
check("部分移库成功", ok(moved), moved)

status, after_move = call("GET", f"/locations/batches?warehouseId={raw['id']}&keyword={batch_no}", admin)
after_rows = after_move.get("data") or []
origin = next((row for row in after_rows if row.get("batchNo") == batch_no), None)
split = next((row for row in after_rows if str(row.get("batchNo") or "").startswith(batch_no + "-")), None)
check("原批次剩 4 件仍在 A 区",
      origin and float(origin.get("qty") or 0) == 4 and origin.get("locationCode") == "A-" + TAG, origin)
check("拆出 2 件落到 B 区", split and float(split.get("qty") or 0) == 2 and split.get("locationCode") == "B-" + TAG,
      split)

status, inventory_after = call("GET", f"/inventory?keyword=P003", admin)
inv_after = next((row for row in (inventory_after.get("data") or []) if row.get("warehouseId") == raw["id"]), None)
check("移库不改变总库存", inv_after and float(inv_after.get("qty") or 0) == stock_qty,
      (stock_qty, inv_after.get("qty") if inv_after else None))

status, moves = call("GET", f"/locations/moves?warehouseId={raw['id']}", admin)
check("移库记录已留痕", any(row.get("batchNo") == batch_no for row in (moves.get("data") or [])), len(moves.get("data") or []))

print("\n== 拦截 ==")
status, too_many = call("POST", "/locations/move", admin, {
    "productId": product["id"], "warehouseId": raw["id"], "batchNo": batch_no,
    "qty": 99, "toLocationId": b_id
})
check("超量移库被拒", too_many.get("code") == 4005, too_many)

status, fin_loc = call("POST", "/locations", admin, {
    "warehouseId": fin["id"], "code": "FIN-" + TAG, "name": "成品库位"
})
fin_loc_id = (fin_loc.get("data") or {}).get("id")
status, cross = call("POST", "/locations/move", admin, {
    "productId": product["id"], "warehouseId": raw["id"], "batchNo": batch_no,
    "qty": 1, "toLocationId": fin_loc_id
})
check("跨仓库位被拒", cross.get("code") == 1002, cross)

status, in_use = call("DELETE", f"/locations/{a_id}", admin)
check("有库存的库位不能删", in_use.get("code") == 4003, in_use)

print("\n== 越权 ==")
qc = login("qc")
status, forbidden = call("GET", "/locations", qc)
check("质检无权维护库位", status == 403 or forbidden.get("code") not in (0, None), (status, forbidden))

print("\n== 清理 ==")
call("DELETE", f"/locations/{fin_loc_id}", admin)

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
