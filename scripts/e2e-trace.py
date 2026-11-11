# -*- coding: utf-8 -*-
"""
批次追溯专项回归：
1. 入库带批次 -> 批次库存出现该批次
2. 工单整单领料 -> 用料批次记到工单（先进先出）
3. 报工 + 完工检合格 -> 成品批次=工单号，向上能查到原料批次
4. 发货 -> 发货明细记批次，按批次能查到客户
5. 权限：没有 trace 权限的角色查不到
用法：python scripts/e2e-trace.py [base_url]
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import os

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
TODAY = time.strftime("%Y-%m-%d")

PASSED = 0
FAILED = []


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode())
        except Exception:
            payload = {"code": e.code, "message": str(e)}
        payload["_http"] = e.code
        return payload


def login(username, password=None):
    # 强制改密之后 admin 的口令可能已经变了，用 MES_PASSWORD 传入
    password = password or os.environ.get("MES_PASSWORD", "123456")
    res = call("POST", "/auth/login", body={"username": username, "password": password})
    return res["data"]["token"] if res.get("code") == 0 else None


def check(name, cond, extra=""):
    global PASSED
    if cond:
        PASSED += 1
        print("  [OK] " + name)
    else:
        FAILED.append(name)
        print("  [FAIL] " + name + " " + str(extra))


def ok(payload):
    return payload.get("code") == 0


def main():
    admin = login("admin")
    worker = login("worker1")
    qc = login("qc")
    sales = login("sales")
    if not admin or not worker or not qc or not sales:
        print("登录失败，请确认服务已启动且演示账号存在")
        sys.exit(1)

    products = call("GET", "/products", admin)["data"]
    by_code = {p["code"]: p for p in products}

    print("== 1. 带批次入库 ==")
    batch_no = "E2E-B" + str(int(time.time()))
    p005 = by_code["P005"]
    inbound_id = call("POST", "/inbound-orders", admin, {
        "type": "OTHER", "warehouseId": 1, "supplier": "追溯回归供应商", "needInspection": False,
        "remark": "批次追溯回归", "noOrderReason": "批次追溯回归准备库存，无采购单",
        "items": [{"productId": p005["id"], "qty": 50, "batchNo": batch_no}]
    })["data"]
    payload = call("POST", "/inbound-orders/%d/submit" % inbound_id, admin, {})
    check("入库单提交成功", ok(payload), payload.get("message"))

    batches = call("GET", "/inventory/%d/batches?warehouseId=1" % p005["id"], admin)["data"]
    hit = [b for b in batches if b["batchNo"] == batch_no]
    check("批次库存出现新批次", len(hit) == 1 and float(hit[0]["qty"]) >= 50, batches[:3])
    check("批次带入库时间与来源", bool(hit and hit[0].get("createdAt")), hit[:1])

    print("== 2. 领料按批次先进先出 ==")
    p001 = by_code["P001"]
    wo_id = call("POST", "/work-orders", admin, {
        "productId": p001["id"], "planQty": 5, "planStart": TODAY, "planEnd": TODAY,
        "priority": "NORMAL", "remark": "批次追溯回归"
    })["data"]
    call("POST", "/scheduling/%d/schedule" % wo_id, admin, {"date": TODAY})
    call("POST", "/work-orders/%d/release" % wo_id, admin)
    detail = call("GET", "/work-orders/%d" % wo_id, admin)["data"]
    work_no = detail["workNo"]

    payload = call("POST", "/work-orders/%d/issue" % wo_id, admin, {})
    check("整单领料成功", ok(payload), payload.get("message"))

    work_trace = call("GET", "/trace/work-order/%s" % urllib.parse.quote(work_no), admin)["data"]
    materials = work_trace.get("materials") or []
    check("工单记录了用料批次", len(materials) > 0, materials[:2])
    check("用料批次带物料与数量", all(m.get("batchNo") and float(m["qty"]) > 0 for m in materials),
          materials[:2])

    print("== 3. 报工 + 完工检：成品批次=工单号 ==")
    for op in detail["operations"]:
        payload = call("POST", "/work-orders/%d/report" % wo_id, worker, {
            "operationId": op["id"], "qty": 5, "qualifiedQty": 5, "defectQty": 0,
            "worker": "worker1", "inspectionResult": "PASS" if op.get("inspectionMode") == "ATTACHED" else None
        })
        check("工序 %s 报工" % op["seq"], ok(payload), payload.get("message"))

    detail = call("GET", "/work-orders/%d" % wo_id, admin)["data"]
    check("工单已完工", detail["status"] == "FINISHED", detail["status"])

    payload = call("POST", "/inspections", qc, {
        "type": "FINAL", "refType": "WORK_ORDER", "refNo": work_no, "workOrderId": wo_id,
        "productId": p001["id"], "inspectQty": 5, "defectQty": 0, "inspector": "qc",
        "remark": "追溯回归完工检"
    })
    check("完工检合格入库", ok(payload), payload)

    fg_trace = call("GET", "/trace/batch/%s" % urllib.parse.quote(work_no), admin)["data"]
    stocks = fg_trace.get("stocks") or []
    check("成品批次有库存", len(stocks) > 0 and float(stocks[0]["qty"]) >= 5, stocks[:1])
    check("成品批次 = 工单号", fg_trace.get("batchNo") == work_no, fg_trace.get("batchNo"))
    check("成品能向上查到原料批次", len(fg_trace.get("materials") or []) > 0,
          (fg_trace.get("materials") or [])[:2])

    print("== 4. 成品发货：批次与客户 ==")
    order_id = call("POST", "/sales-orders", admin, {
        "customerId": 1, "orderDate": TODAY, "deliveryDate": TODAY, "remark": "批次追溯发货",
        "items": [{"productId": p001["id"], "qty": 5, "price": 268}]
    })["data"]
    call("POST", "/sales-orders/%d/confirm" % order_id, admin, {})
    created = call("POST", "/sales-orders/%d/to-production" % order_id, admin, {})
    check("销售订单转生产", ok(created), created.get("message"))
    ship_work_no = created["data"][0]["workNo"]
    ship_work = call("GET", "/work-orders/by-no/%s" % urllib.parse.quote(ship_work_no), admin)["data"]
    call("POST", "/scheduling/%d/schedule" % ship_work["id"], admin, {"date": TODAY})
    call("POST", "/work-orders/%d/release" % ship_work["id"], admin, {})
    check("发货工单领料", ok(call("POST", "/work-orders/%d/issue" % ship_work["id"], admin, {})),
          "领料失败")
    for op in ship_work["operations"]:
        call("POST", "/work-orders/%d/report" % ship_work["id"], worker, {
            "operationId": op["id"], "qty": 5, "qualifiedQty": 5, "defectQty": 0, "worker": "worker1",
            "inspectionResult": "PASS" if op.get("inspectionMode") == "ATTACHED" else None
        })
    payload = call("POST", "/inspections", qc, {
        "type": "FINAL", "refType": "WORK_ORDER", "refNo": ship_work_no,
        "workOrderId": ship_work["id"], "productId": p001["id"],
        "inspectQty": 5, "defectQty": 0, "inspector": "qc", "remark": "追溯回归完工检"
    })
    check("发货工单完工检合格", ok(payload), payload)

    order = call("GET", "/sales-orders/%d" % order_id, admin)["data"]
    check("订单进入待发货", order["status"] == "READY_TO_SHIP", order["status"])
    order_item_id = (order.get("items") or [])[0]["id"]
    shipment_id = call("POST", "/shipments", admin, {
        "salesOrderId": order_id, "carrier": "顺丰速运", "trackingNo": "SF-TRACE",
        "address": "上海市嘉定区工业园 88 号",
        "items": [{"orderItemId": order_item_id, "qty": 5}]
    })["data"]
    payload = call("POST", "/shipments/%d/ship" % shipment_id, admin, {})
    check("发货出库", ok(payload), payload.get("message"))

    no = call("GET", "/shipments/%d" % shipment_id, admin)["data"]["shipmentNo"]
    ship_trace = call("GET", "/trace/shipment/%s" % urllib.parse.quote(no), admin)["data"]
    items = ship_trace.get("items") or []
    check("发货单能查到明细", len(items) > 0, items[:1])
    check("发货明细记了批次", bool(items and (items[0].get("batches") or [])), items[:1])
    check("发货单能向上追到原料", bool(ship_trace.get("batches")), ship_trace.get("batches"))

    shipped_batch = items[0]["batches"][0]["batchNo"] if items and items[0].get("batches") else None
    check("发货按先进先出取批次", bool(shipped_batch), items[:1])
    if shipped_batch:
        btrace = call("GET", "/trace/batch/%s" % urllib.parse.quote(shipped_batch), admin)["data"]
        check("按实际发出批次能查到客户",
              any(row["shipmentNo"] == no and bool(row.get("customerName"))
                  for row in (btrace.get("shipments") or [])),
              (btrace.get("shipments") or [])[:1])

    print("== 5. 统一入口与权限 ==")
    dispatch = call("GET", "/trace/query?code=" + urllib.parse.quote(work_no), admin)["data"]
    check("统一入口识别工单", dispatch.get("kind") == "WORK_ORDER", dispatch.get("kind"))
    dispatch = call("GET", "/trace/query?code=" + urllib.parse.quote(batch_no), admin)["data"]
    check("统一入口识别批次", dispatch.get("kind") == "BATCH", dispatch.get("kind"))

    denied = call("GET", "/trace/batch/%s" % urllib.parse.quote(work_no), sales)
    check("销售没有追溯权限", denied.get("_http") == 403, (denied.get("_http"), denied.get("code")))

    print("\n结果：%d 项通过，%d 项失败" % (PASSED, len(FAILED)))
    if FAILED:
        print("失败项：" + "、".join(FAILED))
        sys.exit(1)


if __name__ == "__main__":
    main()
