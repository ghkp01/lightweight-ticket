# -*- coding: utf-8 -*-
"""
发票与应付专项回归：
1. 采购入库完成自动生成暂估
2. 可开票行只列已入库未开票数量
3. 发票登记拆分不含税与税额、按账期算到期日
4. 三单匹配冲暂估，部分/全部付款
5. 单价差异需要强制匹配并记录差异
6. 超量开票、重复发票号被拒；作废回退暂估
7. 账龄、汇总、权限与操作日志
用法：python scripts/e2e-ap.py [base_url]
"""
import datetime
import json
import sys
import time
import urllib.error
import urllib.request
import os

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
TAG = time.strftime("%H%M%S")
TODAY = time.strftime("%Y-%m-%d")
DUE_30 = (datetime.date.today() + datetime.timedelta(days=30)).isoformat()
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
    if not all([admin, planner, warehouse, sales]):
        print("登录失败，请确认服务已启动且演示账号存在")
        sys.exit(1)

    suppliers = call("GET", "/suppliers/options", admin).get("data") or []
    supplier = next((s for s in suppliers if s.get("code") == "S001"), None)
    if not supplier:
        print("演示供应商 S001 不存在")
        sys.exit(1)

    print("== 采购入库生成暂估 ==")
    po = call("POST", "/purchase-orders", admin, {
        "supplierId": supplier["id"], "warehouseId": 1,
        "orderDate": TODAY, "needInspection": False,
        "remark": "AP E2E " + TAG,
        "items": [{"productId": 3, "qty": 10, "price": 10}]
    })
    po_id = po.get("data")
    check("建采购单", ok(po) and po_id, po)
    call("POST", "/purchase-orders/%s/confirm" % po_id, admin)
    receipt = call("POST", "/purchase-orders/%s/receive" % po_id, admin, {"needInspection": False})
    inbound_id = (receipt.get("data") or {}).get("inboundId")
    inbound_no = (receipt.get("data") or {}).get("inboundNo")
    call("POST", "/inbound-orders/%s/submit" % inbound_id, admin)

    accruals = call("GET", "/ap/accruals?keyword=%s" % inbound_no, admin).get("data") or {}
    accrual = (accruals.get("records") or [{}])[0]
    check("入库完成自动暂估", accrual.get("accrualNo") and abs(float(accrual.get("amount")) - 100.0) < 0.01,
          accrual)
    check("暂估初始未冲", accrual.get("status") == "OPEN", accrual.get("status"))

    lines = call("GET", "/ap/invoiceable-lines?supplierId=%s" % supplier["id"], admin).get("data") or []
    line = next((row for row in lines if row.get("purchaseOrderId") == po_id), None)
    check("可开票行带已入库未开票数量", line and abs(float(line.get("invoiceableQty")) - 10.0) < 0.01, line)
    poi_id = line["purchaseOrderItemId"]

    print("== 发票登记与税额 ==")
    invoice_no = "AP-E2E-" + TAG
    invoice = call("POST", "/ap/invoices", admin, {
        "invoiceNo": invoice_no, "invoiceType": "VAT_SPECIAL", "supplierId": supplier["id"],
        "purchaseOrderId": po_id, "invoiceDate": TODAY, "taxRate": 13,
        "remark": "E2E 发票",
        "items": [{"purchaseOrderItemId": poi_id, "qty": 8, "price": 10}]
    })
    invoice_id = invoice.get("data")
    check("登记发票", ok(invoice) and invoice_id, invoice)

    detail = call("GET", "/ap/invoices/%s" % invoice_id, admin).get("data") or {}
    check("价税合计正确", abs(float(detail.get("grossAmount")) - 80.0) < 0.01, detail.get("grossAmount"))
    check("不含税金额按税率拆分", abs(float(detail.get("netAmount")) - 70.80) < 0.01, detail.get("netAmount"))
    check("税额正确", abs(float(detail.get("taxAmount")) - 9.20) < 0.01, detail.get("taxAmount"))
    check("到期日按账期推算", detail.get("dueDate") == DUE_30, detail.get("dueDate"))

    duplicate = call("POST", "/ap/invoices", admin, {
        "invoiceNo": invoice_no, "supplierId": supplier["id"], "invoiceDate": TODAY,
        "items": [{"purchaseOrderItemId": poi_id, "qty": 1, "price": 10}]
    })
    check("发票号重复被拒", duplicate.get("code") == 2015, duplicate)

    print("== 三单匹配与付款 ==")
    matched = call("POST", "/ap/invoices/%s/match" % invoice_id, planner, {"force": False})
    check("匹配通过", ok(matched) and abs(float(matched["data"].get("differenceAmount"))) < 0.01, matched)
    detail = call("GET", "/ap/invoices/%s" % invoice_id, admin).get("data") or {}
    check("发票状态待付款", detail.get("status") == "MATCHED", detail.get("status"))
    check("匹配记录冲暂估", len(detail.get("matches") or []) == 1, detail.get("matches"))

    accrual_after = (call("GET", "/ap/accruals?keyword=%s" % inbound_no, admin).get("data") or {}).get("records") or [{}]
    check("暂估部分冲回", accrual_after[0].get("status") == "PARTIAL"
          and abs(float(accrual_after[0].get("openAmount")) - 20.0) < 0.01, accrual_after[0])

    pay1 = call("POST", "/ap/invoices/%s/payments" % invoice_id, admin, {
        "payDate": TODAY, "amount": 30, "method": "BANK", "referenceNo": "BANK-" + TAG
    })
    check("部分付款", ok(pay1) and pay1["data"].get("status") == "PARTIAL", pay1)
    too_much = call("POST", "/ap/invoices/%s/payments" % invoice_id, admin, {
        "payDate": TODAY, "amount": 60, "method": "BANK"
    })
    check("付款不能超过未付", too_much.get("code") == 1002, too_much)
    pay2 = call("POST", "/ap/invoices/%s/payments" % invoice_id, admin, {
        "payDate": TODAY, "amount": 50, "method": "BANK"
    })
    check("付清后状态已付清", ok(pay2) and pay2["data"].get("status") == "PAID", pay2)

    print("== 差异与作废 ==")
    diff_invoice = call("POST", "/ap/invoices", admin, {
        "invoiceNo": "AP-DIFF-" + TAG, "supplierId": supplier["id"], "purchaseOrderId": po_id,
        "invoiceDate": TODAY, "taxRate": 13,
        "items": [{"purchaseOrderItemId": poi_id, "qty": 2, "price": 11}]
    })
    diff_id = diff_invoice.get("data")
    check("剩余数量可以再开票", ok(diff_invoice), diff_invoice)
    need_force = call("POST", "/ap/invoices/%s/match" % diff_id, planner, {"force": False})
    check("单价偏离需要强制匹配", need_force.get("code") == 2020, need_force)
    forced = call("POST", "/ap/invoices/%s/match" % diff_id, planner, {"force": True})
    check("强制匹配成功并记录差异", ok(forced) and abs(float(forced["data"].get("differenceAmount")) - 2.0) < 0.01,
          forced)

    aging = call("GET", "/ap/aging", admin).get("data") or {}
    buckets = aging.get("buckets") or []
    check("账龄包含未到期金额", any(b.get("bucket") == "未到期" and float(b.get("amount")) >= 22.0 for b in buckets),
          buckets)

    cancelled = call("POST", "/ap/invoices/%s/cancel" % diff_id, planner)
    check("未付款发票可作废", ok(cancelled), cancelled)
    after_cancel = call("GET", "/ap/invoices/%s" % diff_id, admin).get("data") or {}
    check("作废后状态正确", after_cancel.get("status") == "CANCELLED", after_cancel.get("status"))
    accrual_final = (call("GET", "/ap/accruals?keyword=%s" % inbound_no, admin).get("data") or {}).get("records") or [{}]
    check("作废后暂估回退为部分冲", accrual_final[0].get("status") == "PARTIAL", accrual_final[0])

    over = call("POST", "/ap/invoices", admin, {
        "invoiceNo": "AP-OVER-" + TAG, "supplierId": supplier["id"], "invoiceDate": TODAY,
        "items": [{"purchaseOrderItemId": poi_id, "qty": 5, "price": 10}]
    })
    check("超量开票被拒", over.get("code") == 1002, over)

    print("== 汇总、权限与日志 ==")
    summary = call("GET", "/ap/summary", admin).get("data") or {}
    check("应付汇总字段齐全", "accrualOpen" in summary and "invoiceOpen" in summary and "overdue" in summary,
          summary)

    sales_list = call("GET", "/ap/invoices", sales)
    check("销售看不到应付", sales_list.get("code") == 403 or sales_list.get("_http") == 403, sales_list)
    warehouse_list = call("GET", "/ap/invoices", warehouse)
    check("仓库看不到应付", warehouse_list.get("code") == 403 or warehouse_list.get("_http") == 403,
          warehouse_list)

    logs = call("GET", "/system/logs?module=%E5%8F%91%E7%A5%A8%E4%B8%8E%E5%BA%94%E4%BB%98&limit=50",
                admin).get("data") or []
    match_log = next((row for row in logs if row.get("action") == "匹配"), None)
    pay_log = next((row for row in logs if row.get("action") == "付款"), None)
    check("匹配写入操作日志", match_log is not None, logs[:1])
    check("付款写入操作日志", pay_log is not None, logs[:1])

    print()
    print("通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
    if FAILED:
        print("失败项：" + " / ".join(FAILED))
        sys.exit(1)


if __name__ == "__main__":
    main()
