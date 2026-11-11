# -*- coding: utf-8 -*-
"""
采购与供应商专项回归：
1. 供应商档案：新增、编码重复、类型校验、停用、被引用不可删
2. 采购订单：建单金额、确认、草稿不能收货、确认后才能收
3. 收货：部分收货 / 在途防重复 / 生成入库单并回链采购单
4. 入库完成回写实收与状态（部分到货 / 已到货）
5. 权限：销售看不到采购单、仓库能看能收不能建、质检不能维护供应商
6. 操作日志留痕与汇总口径
用法：python scripts/e2e-purchase.py [base_url]
"""
import json
import sys
import time
import urllib.error
import urllib.request
import os

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
TAG = time.strftime("%H%M%S")
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
    planner = login("planner")
    warehouse = login("warehouse")
    sales = login("sales")
    qc = login("qc")
    if not all([admin, planner, warehouse, sales, qc]):
        print("登录失败，请确认服务已启动且演示账号存在")
        sys.exit(1)

    print("== 供应商档案 ==")
    suppliers = call("GET", "/suppliers", admin).get("data") or []
    demo = next((s for s in suppliers if s.get("code") == "S001"), None)
    check("演示供应商存在", demo is not None, suppliers[:1])
    check("供应商带采购统计", demo is not None and "orderCount" in demo, demo)

    options = call("GET", "/suppliers/options", admin).get("data") or []
    check("供应商下拉可用", len(options) >= 3 and "leadDays" in options[0], options[:1])

    code = "E2E" + TAG
    created = call("POST", "/suppliers", admin, {
        "code": code, "name": "外协测试供应商" + TAG, "category": "OUTSOURCE",
        "contact": "测试", "phone": "13800007777", "paymentTerm": "现结",
        "leadDays": 3, "rating": "B", "enabled": True, "remark": "回归专用"
    })
    supplier_id = created.get("data")
    check("新增供应商", ok(created) and supplier_id, created)

    duplicate = call("POST", "/suppliers", admin, {
        "code": code, "name": "重复编码", "category": "MATERIAL"
    })
    check("供应商编码重复被拒", duplicate.get("code") == 2006, duplicate)

    bad_category = call("POST", "/suppliers", admin, {
        "code": code + "X", "name": "错误类型", "category": "FTP"
    })
    check("供应商类型校验", bad_category.get("code") == 1002, bad_category)

    updated = call("PUT", "/suppliers/%s" % supplier_id, admin, {
        "code": code, "name": "外协测试供应商" + TAG, "category": "OUTSOURCE",
        "contact": "测试", "phone": "13800007777", "paymentTerm": "月结 15 天",
        "leadDays": 10, "rating": "A", "enabled": True, "remark": "回归专用"
    })
    check("编辑供应商", ok(updated), updated)
    refreshed = next((s for s in call("GET", "/suppliers?keyword=" + code, admin).get("data") or []), None)
    check("交期与评级已更新", refreshed and refreshed.get("leadDays") == 10 and refreshed.get("rating") == "A",
          refreshed)

    qc_save = call("POST", "/suppliers", qc, {"code": code + "Q", "name": "质检不该能建"})
    check("质检不能维护供应商", qc_save.get("code") == 403 or qc_save.get("_http") == 403, qc_save)

    print("== 采购订单 ==")
    po = call("POST", "/purchase-orders", admin, {
        "supplierId": demo["id"], "warehouseId": 1,
        "orderDate": TODAY, "expectedDate": TODAY,
        "needInspection": False, "remark": "E2E 采购回归 " + TAG,
        "items": [
            {"productId": 3, "qty": 20, "price": 45.5, "remark": "型材"},
            {"productId": 5, "qty": 100, "price": 0.8, "remark": "螺栓"}
        ]
    })
    order_id = po.get("data")
    check("新建采购单", ok(po) and order_id, po)

    detail = call("GET", "/purchase-orders/%s" % order_id, admin).get("data") or {}
    check("草稿状态", detail.get("status") == "DRAFT", detail.get("status"))
    check("金额按数量×单价汇总", abs(float(detail.get("totalAmount") or 0) - 990.0) < 0.01,
          detail.get("totalAmount"))
    check("明细带待收数量", len(detail.get("items") or []) == 2
          and float(detail["items"][0]["availableQty"]) == 20.0, detail.get("items"))

    price = call("GET", "/purchase-orders/last-price?supplierId=%s&productId=3" % demo["id"], admin).get("data")
    check("参考价取最近采购价", price and abs(float(price.get("price")) - 45.5) < 0.01, price)

    check("仓库能看到采购单", call("GET", "/purchase-orders", warehouse).get("code") == 0)
    sales_list = call("GET", "/purchase-orders", sales)
    check("销售看不到采购单", sales_list.get("code") == 403 or sales_list.get("_http") == 403, sales_list)
    warehouse_create = call("POST", "/purchase-orders", warehouse, {
        "supplierId": demo["id"], "warehouseId": 1,
        "items": [{"productId": 3, "qty": 1, "price": 1}]
    })
    check("仓库不能新建采购单", warehouse_create.get("code") == 403 or warehouse_create.get("_http") == 403,
          warehouse_create)

    draft_receive = call("POST", "/purchase-orders/%s/receive" % order_id, admin, {})
    check("草稿不能收货", draft_receive.get("code") == 2014, draft_receive)

    confirmed = call("POST", "/purchase-orders/%s/confirm" % order_id, planner)
    check("计划可以确认采购单", ok(confirmed), confirmed)
    again = call("POST", "/purchase-orders/%s/confirm" % order_id, admin)
    check("重复确认被拒", again.get("code") == 2014, again)

    print("== 收货与到货回写 ==")
    items = (call("GET", "/purchase-orders/%s" % order_id, admin).get("data") or {}).get("items") or []
    first_item = items[0]["id"]
    first_receive = call("POST", "/purchase-orders/%s/receive" % order_id, warehouse, {
        "needInspection": False, "remark": "先到 5 件",
        "items": [{"itemId": first_item, "qty": 5, "batchNo": "E2E-LOT-" + TAG}]
    })
    inbound1 = (first_receive.get("data") or {}).get("inboundId")
    check("仓库可以收货并生成入库单", ok(first_receive) and inbound1, first_receive)

    after_first = call("GET", "/purchase-orders/%s" % order_id, admin).get("data") or {}
    row = (after_first.get("items") or [])[0]
    check("在途数量进入收货中", float(row.get("receivingQty") or 0) == 5.0, row)
    check("待收数量扣掉在途", float(row.get("availableQty") or 0) == 15.0, row)

    second_receive = call("POST", "/purchase-orders/%s/receive" % order_id, admin, {})
    inbound2 = (second_receive.get("data") or {}).get("inboundId")
    check("确认后剩余数量自动带出", ok(second_receive) and inbound2, second_receive)

    blocked = call("POST", "/purchase-orders/%s/receive" % order_id, admin, {})
    check("全部在途时不能重复收货", blocked.get("code") == 1002, blocked)

    submitted1 = call("POST", "/inbound-orders/%s/submit" % inbound1, admin)
    check("第一张入库单提交入库", ok(submitted1), submitted1)
    partial = call("GET", "/purchase-orders/%s" % order_id, admin).get("data") or {}
    check("部分入库后状态为部分到货", partial.get("status") == "PARTIAL", partial.get("status"))
    partial_row = (partial.get("items") or [])[0]
    check("实收数量已回写", float(partial_row.get("receivedQty") or 0) == 5.0, partial_row)

    submitted2 = call("POST", "/inbound-orders/%s/submit" % inbound2, admin)
    check("第二张入库单提交入库", ok(submitted2), submitted2)
    received = call("GET", "/purchase-orders/%s" % order_id, admin).get("data") or {}
    check("全部入库后状态为已到货", received.get("status") == "RECEIVED", received.get("status"))
    received_qty = sum(float(item.get("receivedQty") or 0) for item in received.get("items") or [])
    check("实收合计等于采购数量", abs(received_qty - 120.0) < 0.01, received_qty)

    inbounds = call("GET", "/inbound-orders?keyword=%s" % ((first_receive.get("data") or {}).get("inboundNo") or ""),
                    admin).get("data") or {}
    record = (inbounds.get("records") or [{}])[0]
    check("入库单回链采购单号", record.get("purchaseOrderNo") == detail.get("orderNo"), record)

    received_again = call("POST", "/purchase-orders/%s/receive" % order_id, admin, {})
    check("已到货不能继续收货", received_again.get("code") == 2014, received_again)
    cancel_received = call("POST", "/purchase-orders/%s/cancel" % order_id, admin)
    check("已到货不能取消", cancel_received.get("code") == 2014, cancel_received)

    print("== 删除与供应商护栏 ==")
    draft2 = call("POST", "/purchase-orders", admin, {
        "supplierId": demo["id"], "warehouseId": 1,
        "items": [{"productId": 6, "qty": 10, "price": 2.5}]
    })
    draft2_id = draft2.get("data")
    removed = call("DELETE", "/purchase-orders/%s" % draft2_id, admin)
    check("草稿采购单可删除", ok(removed), removed)
    gone = call("GET", "/purchase-orders/%s" % draft2_id, admin)
    check("删除后查不到", gone.get("code") == 2007, gone)

    used_supplier = call("DELETE", "/suppliers/%s" % demo["id"], admin)
    check("被采购单引用的供应商不能删除", used_supplier.get("code") == 2004, used_supplier)

    temp_delete = call("DELETE", "/suppliers/%s" % supplier_id, admin)
    check("未被引用的供应商可删除", ok(temp_delete), temp_delete)

    summary = call("GET", "/purchase-orders/summary", admin).get("data") or {}
    check("汇总带草稿与到货口径", "draft" in summary and "openAmount" in summary, summary)

    logs = call("GET", "/system/logs?module=%E9%87%87%E8%B4%AD%E8%AE%A2%E5%8D%95&limit=50", admin).get("data") or []
    receive_log = next((row for row in logs if row.get("action") == "收货"), None)
    check("采购收货写入操作日志", receive_log is not None, logs[:1])
    check("日志摘要带采购单号与入库单号",
          receive_log is not None and str(detail.get("orderNo")) in str(receive_log.get("summary")),
          receive_log)

    print()
    print("通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
    if FAILED:
        print("失败项：" + " / ".join(FAILED))
        sys.exit(1)


if __name__ == "__main__":
    main()
