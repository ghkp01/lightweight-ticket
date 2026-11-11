"""成本核算端到端回归：采购收货带价刷新移动平均成本、工单完工自动结算料工费、
成本报表/构成/重算、产品成本差异、越权与参数校验。

用法：python scripts/e2e-cost.py [http://localhost:8080]
"""

import json
import sys
import urllib.error
import urllib.request
import os

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


def section(title):
    print("\n== " + title + " ==")


def num(value):
    try:
        return float(value or 0)
    except (TypeError, ValueError):
        return 0.0


def rows_of(payload):
    data = payload.get("data") if isinstance(payload, dict) else payload
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        return data.get("records") or []
    return []


admin = login("admin")
check("管理员登录", admin is not None)

section("采购收货带价 → 移动平均成本")
_, before = call("GET", "/cost/products?keyword=P003", admin)
before_avg = num((before.get("data") or [{}])[0].get("avgCost"))

_, suppliers = call("GET", "/suppliers?size=5", admin)
supplier_rows = rows_of(suppliers)
supplier_id = supplier_rows[0].get("id") if supplier_rows else None
_, warehouses = call("GET", "/warehouses", admin)
warehouse_rows = rows_of(warehouses)
raw_wh = next((w["id"] for w in warehouse_rows if w.get("type") == "RAW"), None)
_, products = call("GET", "/products?keyword=P003&size=5", admin)
p003 = next((p["id"] for p in rows_of(products) if p.get("code") == "P003"), None)
check("找到供应商 / 原料仓 / P003", supplier_id and raw_wh and p003, (supplier_id, raw_wh, p003))

_, po = call("POST", "/purchase-orders", admin, {
    "supplierId": supplier_id,
    "warehouseId": raw_wh,
    "orderDate": "2026-10-23",
    "expectedDate": "2026-10-30",
    "needInspection": False,
    "remark": "成本回归收货",
    "items": [{"productId": p003, "qty": 100, "price": 46}]
})
po_id = po.get("data")
check("采购单创建", ok(po) and po_id, po)
call("POST", f"/purchase-orders/{po_id}/confirm", admin)
status, receipt = call("POST", f"/purchase-orders/{po_id}/receive", admin,
                       {"items": [], "needInspection": False, "remark": "成本回归收货"})
inbound_id = (receipt.get("data") or {}).get("inboundId")
check("采购收货生成入库单", status < 400 and inbound_id, receipt)
status, submitted = call("POST", f"/inbound-orders/{inbound_id}/submit", admin)
check("入库单提交入库", status < 400 and ok(submitted), submitted)

_, after = call("GET", "/cost/products?keyword=P003", admin)
after_row = (after.get("data") or [{}])[0]
after_avg = num(after_row.get("avgCost"))
check("采购价进入移动平均成本", after_avg > before_avg, (before_avg, after_avg))
check("库存金额 = 数量 × 均价",
      abs(num(after_row.get("stockAmount")) - num(after_row.get("stockQty")) * after_avg) < 1,
      after_row)

section("工单完工 → 自动结算料工费")
_, created = call("POST", "/work-orders", admin, {
    "productId": 2, "planQty": 2, "planStart": "2026-10-23", "planEnd": "2026-10-30"
})
work_id = created.get("data")
check("工单创建", ok(created) and work_id, created)
status, scheduled = call("POST", f"/scheduling/{work_id}/schedule", admin, {"date": "2026-10-24"})
check("工单排产", status < 400 and ok(scheduled), scheduled)
status, released = call("POST", f"/work-orders/{work_id}/release", admin)
check("工单下达", status < 400 and ok(released), released)
status, issued = call("POST", f"/work-orders/{work_id}/issue", admin)
check("生产领料", status < 400 and ok(issued), issued)

_, detail = call("GET", f"/work-orders/{work_id}", admin)
work = detail.get("data") or {}
work_no = work.get("workNo")
operations = work.get("operations") or []
check("工单有工序与 BOM", operations and (work.get("bom") or []), len(operations))

for op in operations:
    status, result = call("POST", f"/work-orders/{work_id}/report", admin, {
        "operationId": op["id"], "qty": 2, "qualifiedQty": 2, "defectQty": 0
    })
    if status >= 400 or not ok(result):
        print("      报工失败：", result)
        break

_, finished = call("GET", f"/work-orders/{work_id}", admin)
check("报工后工单完工", (finished.get("data") or {}).get("status") == "FINISHED",
      (finished.get("data") or {}).get("status"))

_, page = call("GET", f"/cost/work-orders?keyword={work_no}", admin)
rows = (page.get("data") or {}).get("records") or []
row = rows[0] if rows else {}
check("成本报表能查到该工单", len(rows) == 1, rows)
check("完工自动写入材料成本", num(row.get("materialCost")) > 0, row)
check("完工自动写入人工成本", num(row.get("laborCost")) > 0, row)
check("制造费用 = 人工 × 10%",
      abs(num(row.get("overheadCost")) - num(row.get("laborCost")) * 0.1) < 0.02, row)
check("总成本 = 料 + 工 + 费",
      abs(num(row.get("totalCost")) - (num(row.get("materialCost")) + num(row.get("laborCost"))
                                       + num(row.get("overheadCost")))) < 0.02, row)
check("单位成本 = 总成本 ÷ 合格数量",
      abs(num(row.get("unitCost")) - num(row.get("totalCost")) / 2) < 0.01, row)
check("结算时间已写入", bool(row.get("costedAt")), row)

section("成本构成与重算")
_, cost_detail = call("GET", f"/cost/work-orders/{work_id}", admin)
cd = cost_detail.get("data") or {}
check("材料明细非空", len(cd.get("materials") or []) >= 1, cd.get("materials"))
check("人工明细按工序", len(cd.get("labors") or []) == len(operations), cd.get("labors"))
check("制造费用比例回传", num(cd.get("overheadRate")) == 10, cd.get("overheadRate"))

_, recalc = call("POST", f"/cost/work-orders/{work_id}/recalc", admin)
rd = recalc.get("data") or {}
check("重算结果与快照一致",
      abs(num(rd.get("totalCost")) - num(row.get("totalCost"))) < 0.02, (rd, row))

_, summary = call("GET", "/cost/summary", admin)
sm = summary.get("data") or {}
check("成本概览有合计", num(sm.get("totalCost")) > 0, sm)
check("成本结构占比合计约 100%",
      abs(num(sm.get("materialRate")) + num(sm.get("laborRate")) + num(sm.get("overheadRate"))
          + num(sm.get("outsourceRate")) - 100) < 0.5,
      sm)

_, bulk = call("POST", "/cost/recalc", admin, {"status": "FINISHED"})
check("按状态批量重算", ok(bulk) and (bulk.get("data") or {}).get("count") is not None, bulk)

section("参数校验与越权")
status, bad = call("GET", "/cost/work-orders?from=2026-13-01", admin)
check("非法日期被拒", status >= 400 or bad.get("code") == 1002, bad)

qc = login("qc")
status, forbidden = call("GET", "/cost/work-orders", qc)
check("质检无权查看成本", status == 403 or forbidden.get("code") not in (0, None), (status, forbidden))
sales = login("sales")
status, forbidden2 = call("GET", "/cost/summary", sales)
check("销售无权查看成本", status == 403 or forbidden2.get("code") not in (0, None), (status, forbidden2))

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
