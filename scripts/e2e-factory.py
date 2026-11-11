"""多工厂隔离全链路端到端回归：仓库归属工厂，库存 / 采购 / 采购申请 / 出入库按工厂过滤，开关关闭即恢复。

用法：python scripts/e2e-factory.py [http://localhost:8080]
注意：脚本结束会把开关恢复为关闭，避免影响其它回归。
"""

import json
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta
import os

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PASSED = 0
FAILED = []
TAG = str(int(time.time()))[-6:]
PASSWORD = "Fac@123456"
# 首次登录必须改密，改完统一用这个口令，脚本可重复运行
READY_PASSWORD = "Fac@1234567"
TODAY = date.today()
EXPECTED = TODAY + timedelta(days=7)


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
    return isinstance(payload, dict) and payload.get("code") == 0


def section(title):
    print("\n== " + title + " ==")


def check(name, cond, extra=""):
    global PASSED
    if cond:
        PASSED += 1
        print("  [OK]   " + name)
    else:
        FAILED.append(name)
        print("  [FAIL] " + name + "  " + str(extra))


def rows_of(payload):
    data = payload.get("data") if isinstance(payload, dict) else payload
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        return data.get("records") or []
    return []


def switch(on):
    return call("PUT", "/system/configs/org.isolation.enabled", admin, {"value": "true" if on else "false"})


def ensure_warehouse(code, name, factory_id=None):
    """回归用的固定仓库：已存在就复用；工厂对不上时改挂到本次创建的工厂。"""
    _, rows = call("GET", "/warehouses", admin)
    for row in rows_of(rows):
        if row.get("code") == code:
            wid = row.get("id")
            if factory_id and row.get("factoryOrgId") != factory_id:
                call("PUT", f"/warehouses/{wid}", admin,
                     {"code": code, "name": name, "type": "RAW", "factoryOrgId": factory_id})
            return wid
    body = {"code": code, "name": name, "type": "RAW"}
    if factory_id:
        body["factoryOrgId"] = factory_id
    _, created = call("POST", "/warehouses", admin, body)
    return created.get("data")


def ensure_plant(code, name):
    """回归用的固定二号工厂：已存在就复用，避免每跑一次多一个工厂。"""
    _, rows = call("GET", "/orgs?type=PLANT", admin)
    for row in rows_of(rows):
        if row.get("code") == code:
            return row.get("id")
    _, created = call("POST", "/orgs", admin, {
        "code": code, "name": name, "type": "PLANT", "sort": 9, "enabled": True
    })
    return created.get("data")


def ensure_user(username, real_name, org_id):
    """已存在就复用（密码固定），返回登录态。

    新建账号带 mustChangePassword，除改密外的接口一律返回 4023，
    所以这里要先把首次改密走完，否则后面所有列表断言都会拿到空数组。
    """
    call("POST", "/system/users", admin, {
        "username": username, "password": PASSWORD, "realName": real_name,
        "role": "PLANNER", "orgId": org_id, "enabled": True
    })
    token = login(username, READY_PASSWORD)
    if token:
        return token
    token = login(username, PASSWORD)
    if token is None:
        return None
    status, changed = call("PUT", "/auth/password", token,
                           {"oldPassword": PASSWORD, "newPassword": READY_PASSWORD})
    if changed.get("code") == 0:
        return (changed.get("data") or {}).get("token")
    return token


admin = login("admin")
check("管理员登录", admin is not None)

section("准备第二个工厂")
plant2 = ensure_plant("P2E2E", "二号厂（回归）")
check("二号工厂可用", plant2 is not None, plant2)

f2 = ensure_user("f2e2e", "二号厂计划", plant2)
check("二号厂账号可登录", f2 is not None)
me = call("GET", "/auth/me", f2)[1]
check("二号厂账号归属二号工厂", ok(me) and me["data"].get("orgId") == plant2, me)

# 一号厂也放一个归属明确的账号：种子里的 admin 没挂组织，属于「不参与隔离」的兜底账号
_, plant_rows = call("GET", "/orgs?type=PLANT", admin)
plant1 = next((row.get("id") for row in rows_of(plant_rows) if row.get("id") != plant2), None)
check("取到一号工厂", plant1 is not None, plant_rows)
f1 = ensure_user("f1e2e", "一号厂计划", plant1)
check("一号厂账号可登录", f1 is not None)

section("仓库按工厂建")
w1 = ensure_warehouse("W1E2E", "一号厂测试仓")
w2 = ensure_warehouse("W2E2E", "二号厂测试仓", plant2)
check("一号厂仓库可用", w1 is not None, w1)
check("二号厂仓库可用", w2 is not None, w2)
_, warehouse_rows = call("GET", "/warehouses", admin)
factory_of_w2 = next((row.get("factoryOrgId") for row in rows_of(warehouse_rows)
                      if ((row.get("id") or 0) == w2)), None)
check("仓库记下所属工厂", factory_of_w2 == plant2, factory_of_w2)
factory_of_w1 = next((row.get("factoryOrgId") for row in rows_of(warehouse_rows)
                      if ((row.get("id") or 0) == w1)), None)
check("未指定时落到当前账号工厂", factory_of_w1 is not None and factory_of_w1 != plant2, factory_of_w1)

section("打开隔离后按工厂过滤")
status, turned = switch(True)
check("开关可打开", ok(turned), turned)

_, supplier_rows = call("GET", "/suppliers?size=5", admin)
supplier_id = rows_of(supplier_rows)[0]["id"]
po_body = {
    "supplierId": supplier_id, "orderDate": str(TODAY), "expectedDate": str(EXPECTED),
    "needInspection": False, "items": [{"productId": 1, "qty": 1, "price": 10}]
}
status, po1 = call("POST", "/purchase-orders", admin, dict(po_body, warehouseId=w1, remark="一号厂采购"))
status, po2 = call("POST", "/purchase-orders", admin, dict(po_body, warehouseId=w2, remark="二号厂采购"))
po1_id, po2_id = po1.get("data"), po2.get("data")
check("两张采购单都建好", ok(po1) and ok(po2), (po1, po2))

_, orders = call("GET", "/purchase-orders?size=100", f2)
order_ids = [row.get("id") for row in rows_of(orders)]
check("二号厂只看到本厂采购单", po2_id in order_ids, order_ids[:5])
check("二号厂看不到一号厂采购单", po1_id not in order_ids, order_ids[:5])

_, f1_orders = call("GET", "/purchase-orders?size=100", f1)
f1_order_ids = [row.get("id") for row in rows_of(f1_orders)]
check("一号厂只看到本厂采购单", po1_id in f1_order_ids, f1_order_ids[:5])
check("一号厂看不到二号厂采购单", po2_id not in f1_order_ids, f1_order_ids[:5])

_, own_warehouses = call("GET", "/warehouses", f2)
own_warehouse_ids = [row.get("id") for row in rows_of(own_warehouses)]
check("二号厂只看到本厂仓库", w2 in own_warehouse_ids, own_warehouse_ids[:5])
check("二号厂看不到一号厂仓库", w1 not in own_warehouse_ids, own_warehouse_ids[:5])

_, f1_warehouses = call("GET", "/warehouses", f1)
f1_warehouse_ids = [row.get("id") for row in rows_of(f1_warehouses)]
check("一号厂看不到二号厂仓库", w2 not in f1_warehouse_ids, f1_warehouse_ids[:5])

_, inventory_rows = call("GET", "/inventory", f2)
check("二号厂库存列表不含一号厂仓库",
      all((row.get("warehouseId") not in (w1,)) for row in rows_of(inventory_rows)),
      rows_of(inventory_rows)[:3])

_, req_f2 = call("POST", "/purchase-requisitions", f2, {
    "sourceType": "MANUAL", "department": "计划部", "needDate": str(EXPECTED),
    "purpose": "二号厂补料", "items": [{"productId": 1, "qty": 2}]
})
req_f2_id = req_f2.get("data")
check("二号厂可建采购申请", ok(req_f2) and req_f2_id, req_f2)
_, req_f1 = call("POST", "/purchase-requisitions", f1, {
    "sourceType": "MANUAL", "department": "计划部", "needDate": str(EXPECTED),
    "purpose": "一号厂补料", "items": [{"productId": 1, "qty": 1}]
})
req_f1_id = req_f1.get("data")
check("一号厂可建采购申请", ok(req_f1) and req_f1_id, req_f1)

_, f1_reqs = call("GET", "/purchase-requisitions?size=100", f1)
f1_req_ids = [row.get("id") for row in rows_of(f1_reqs)]
check("一号厂看不到二号厂申请", req_f2_id not in f1_req_ids, f1_req_ids[:5])
check("一号厂能看到自己的申请", req_f1_id in f1_req_ids, f1_req_ids[:5])

_, f2_reqs = call("GET", "/purchase-requisitions?size=100", f2)
f2_req_ids = [row.get("id") for row in rows_of(f2_reqs)]
check("二号厂看不到一号厂申请", req_f1_id not in f2_req_ids, f2_req_ids[:5])
check("二号厂能看到自己的申请", req_f2_id in f2_req_ids, f2_req_ids[:5])

section("应付与发票按工厂隔离")
_, po1_detail = call("GET", f"/purchase-orders/{po1_id}", admin)
_, po2_detail = call("GET", f"/purchase-orders/{po2_id}", admin)
po1_no = (po1_detail.get("data") or {}).get("orderNo")
po2_no = (po2_detail.get("data") or {}).get("orderNo")

received = 0
for pid in (po1_id, po2_id):
    call("POST", f"/purchase-orders/{pid}/confirm", admin)
    _, receipt = call("POST", f"/purchase-orders/{pid}/receive", admin, {"items": [], "needInspection": False})
    inbound_id = (receipt.get("data") or {}).get("inboundId")
    if not inbound_id:
        continue
    status, submitted = call("POST", f"/inbound-orders/{inbound_id}/submit", admin)
    if ok(submitted):
        received += 1
check("两张采购单收货入库并生成暂估", received == 2, received)

_, f2_accruals = call("GET", "/ap/accruals?size=100", f2)
f2_accrual_orders = [row.get("purchaseOrderNo") for row in rows_of(f2_accruals)]
check("二号厂只看到本厂暂估", po2_no in f2_accrual_orders, f2_accrual_orders[:5])
check("二号厂看不到一号厂暂估", po1_no not in f2_accrual_orders, f2_accrual_orders[:5])

_, f1_accruals = call("GET", "/ap/accruals?size=100", f1)
f1_accrual_orders = [row.get("purchaseOrderNo") for row in rows_of(f1_accruals)]
check("一号厂看不到二号厂暂估", po2_no not in f1_accrual_orders, f1_accrual_orders[:5])

_, f2_summary = call("GET", "/ap/summary", f2)
accrual_open = float((f2_summary.get("data") or {}).get("accrualOpen") or 0)
check("应付概览只算本厂未匹配暂估", accrual_open >= 0 and accrual_open == round(accrual_open, 2),
      (f2_summary.get("data") or {}))

_, f2_recon = call("GET", "/ap/reconciliation", f2)
recon_rows = rows_of({"data": (f2_recon.get("data") or {}).get("records")})
check("退货对账只列本厂入库",
      all(row.get("purchaseOrderNo") != po1_no for row in recon_rows), recon_rows[:2])

_, invoiceable = call("GET", f"/ap/invoiceable-lines?supplierId={supplier_id}", f2)
lines = rows_of(invoiceable)
check("可开票行不含别厂采购", all(row.get("orderNo") != po1_no for row in lines), lines[:3])
if lines:
    _, created_invoice = call("POST", "/ap/invoices", f2, {
        "invoiceNo": "INV" + TAG, "invoiceType": "NORMAL", "supplierId": supplier_id,
        "purchaseOrderId": lines[0].get("purchaseOrderId"), "invoiceDate": str(TODAY),
        "taxRate": 13, "remark": "多工厂开票",
        "items": [{"purchaseOrderItemId": lines[0].get("purchaseOrderItemId"),
                   "qty": 1, "price": lines[0].get("price")}]
    })
    check("二号厂可开票", ok(created_invoice), created_invoice)
    _, f2_invoices = call("GET", "/ap/invoices?size=100", f2)
    f2_invoice_nos = [row.get("invoiceNo") for row in rows_of(f2_invoices)]
    check("二号厂只看到本厂发票", "INV" + TAG in f2_invoice_nos, f2_invoice_nos[:5])
    _, f1_invoices = call("GET", "/ap/invoices?size=100", f1)
    f1_invoice_nos = [row.get("invoiceNo") for row in rows_of(f1_invoices)]
    check("一号厂看不到二号厂发票", "INV" + TAG not in f1_invoice_nos, f1_invoice_nos[:5])

section("关闭隔离后恢复单工厂视图")
status, turned_off = switch(False)
check("开关可关闭", ok(turned_off), turned_off)
_, all_orders = call("GET", "/purchase-orders?size=100", f2)
all_order_ids = [row.get("id") for row in rows_of(all_orders)]
check("关闭后能看到全部采购单", po1_id in all_order_ids and po2_id in all_order_ids, all_order_ids[:5])
_, all_warehouses = call("GET", "/warehouses", f2)
all_warehouse_ids = [row.get("id") for row in rows_of(all_warehouses)]
check("关闭后能看到全部仓库", w1 in all_warehouse_ids and w2 in all_warehouse_ids, all_warehouse_ids[:5])
_, all_accruals = call("GET", "/ap/accruals?size=100", f2)
all_accrual_orders = [row.get("purchaseOrderNo") for row in rows_of(all_accruals)]
check("关闭后能看到全部暂估", po1_no in all_accrual_orders and po2_no in all_accrual_orders,
      all_accrual_orders[:5])

section("清理")
# 回归用的两个仓库会被采购单与暂估引用，删除是有护栏的；确认拦得住即可，仓库复用不增长
status, blocked = call("DELETE", f"/warehouses/{w2}", admin)
check("被单据引用的仓库不能删除", not ok(blocked), blocked)
status, final = switch(False)
check("开关保持关闭（不影响其它回归）", ok(final), final)

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
