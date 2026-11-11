# -*- coding: utf-8 -*-
"""
入库来源收紧与异常专项回归：
1. 手工建采购入库被拒，必须从采购订单收货
2. 无单入库缺原因被拒，填了原因后标记为手动来源且不生成暂估
3. 采购收货入库单来源为采购单、不允许改数量
4. 采购退货：可退明细、草稿、提交扣库存、回退实收、冲减暂估
5. 超量退货、已开票匹配后退货被拒
6. 入库异常汇总与菜单权限
用法：python scripts/e2e-inbound.py [base_url]
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


def stock(token, code):
    data = call("GET", "/inventory?keyword=" + code, token).get("data") or {}
    rows = data.get("records") if isinstance(data, dict) else data
    return sum(float(r["qty"]) for r in (rows or []) if r.get("productCode") == code)


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

    print("== 1. 手工建采购入库被拒 ==")
    rejected = call("POST", "/inbound-orders", admin, {
        "type": "PURCHASE", "warehouseId": 1, "supplier": supplier["name"],
        "needInspection": False, "noOrderReason": "想绕过采购单直接入库",
        "items": [{"productId": 3, "qty": 1}]
    })
    check("手工采购入库被拒", rejected.get("code") == 4006, rejected)

    print("== 2. 无单入库必须说明原因 ==")
    no_reason = call("POST", "/inbound-orders", admin, {
        "type": "OTHER", "warehouseId": 1, "needInspection": False,
        "items": [{"productId": 3, "qty": 5, "batchNo": "IN-E2E-" + TAG}]
    })
    check("无单入库缺原因被拒", no_reason.get("code") == 4007, no_reason)

    stock_before = stock(admin, "P003")
    manual = call("POST", "/inbound-orders", admin, {
        "type": "OTHER", "warehouseId": 1, "needInspection": False,
        "remark": "期初建账", "noOrderReason": "期初盘点建账，无采购单 " + TAG,
        "items": [{"productId": 3, "qty": 5, "batchNo": "IN-E2E-" + TAG}]
    })
    manual_id = manual.get("data")
    check("填了原因可以建单", ok(manual) and manual_id, manual)
    manual_detail = call("GET", "/inbound-orders/%s" % manual_id, admin).get("data") or {}
    check("来源标记为无单入库", manual_detail.get("source") == "MANUAL", manual_detail.get("source"))
    check("无单原因留痕", "期初盘点建账" in str(manual_detail.get("noOrderReason")),
          manual_detail.get("noOrderReason"))
    call("POST", "/inbound-orders/%s/submit" % manual_id, admin)
    check("无单入库可提交入库", abs(stock(admin, "P003") - stock_before - 5) < 0.01,
          (stock_before, stock(admin, "P003")))
    manual_accrual = call("GET", "/ap/accruals?keyword=%s" % manual_detail.get("inboundNo"), admin).get("data") or {}
    check("无单入库不生成暂估", len(manual_accrual.get("records") or []) == 0,
          manual_accrual.get("records"))

    print("== 3. 采购收货生成的入库单 ==")
    po = call("POST", "/purchase-orders", admin, {
        "supplierId": supplier["id"], "warehouseId": 1, "orderDate": TODAY,
        "needInspection": False, "remark": "入库收紧回归 " + TAG,
        "items": [{"productId": 3, "qty": 12, "price": 10}]
    })
    po_id = po.get("data")
    check("建采购单", ok(po) and po_id, po)
    call("POST", "/purchase-orders/%s/confirm" % po_id, admin)
    receipt = call("POST", "/purchase-orders/%s/receive" % po_id, admin, {"needInspection": False})
    inbound_id = (receipt.get("data") or {}).get("inboundId")
    inbound_no = (receipt.get("data") or {}).get("inboundNo")
    check("收货生成入库单", ok(receipt) and inbound_id, receipt)

    inbound_detail = call("GET", "/inbound-orders/%s" % inbound_id, admin).get("data") or {}
    check("来源为采购单收货", inbound_detail.get("source") == "PO", inbound_detail.get("source"))
    check("回链采购单号", inbound_detail.get("purchaseOrderNo"), inbound_detail.get("purchaseOrderNo"))

    edited = call("PUT", "/inbound-orders/%s" % inbound_id, admin, {
        "type": "OTHER", "warehouseId": 1, "needInspection": False, "noOrderReason": "想改数量",
        "items": [{"productId": 3, "qty": 1}]
    })
    check("采购收货入库单不允许改数量", edited.get("code") == 4006, edited)

    stock_po_before = stock(admin, "P003")
    call("POST", "/inbound-orders/%s/submit" % inbound_id, admin)
    check("收货入库完成加库存", abs(stock(admin, "P003") - stock_po_before - 12) < 0.01,
          (stock_po_before, stock(admin, "P003")))
    accruals = call("GET", "/ap/accruals?keyword=%s" % inbound_no, admin).get("data") or {}
    accrual = (accruals.get("records") or [{}])[0]
    check("采购入库生成暂估 120", abs(float(accrual.get("amount") or 0) - 120.0) < 0.01, accrual)

    print("== 4. 采购退货：可退明细与草稿 ==")
    candidates = call("GET", "/purchase-returns/returnable-inbounds?keyword=%s" % inbound_no, admin).get("data") or []
    check("可退入库单列表含该单", any(int(c.get("inboundOrderId")) == int(inbound_id) for c in candidates),
          candidates[:2])

    lines = call("GET", "/purchase-returns/inbound-orders/%s/returnable" % inbound_id, admin).get("data") or []
    line = lines[0] if lines else {}
    check("可退明细数量等于入库数量", abs(float(line.get("availableQty") or 0) - 12.0) < 0.01, line)
    check("可退单价取采购单价", abs(float(line.get("price") or 0) - 10.0) < 0.01, line)
    inbound_item_id = line.get("inboundItemId")

    too_many = call("POST", "/purchase-returns", admin, {
        "inboundOrderId": inbound_id, "returnDate": TODAY, "reason": "超量退货",
        "items": [{"inboundItemId": inbound_item_id, "qty": 13}]
    })
    check("超量退货被拒", too_many.get("code") == 4010, too_many)

    no_reason_return = call("POST", "/purchase-returns", admin, {
        "inboundOrderId": inbound_id, "returnDate": TODAY, "reason": "",
        "items": [{"inboundItemId": inbound_item_id, "qty": 1}]
    })
    check("退货必须填原因", no_reason_return.get("code") == 1002, no_reason_return)

    first_return = call("POST", "/purchase-returns", admin, {
        "inboundOrderId": inbound_id, "returnDate": TODAY, "reason": "来料尺寸超差退回 " + TAG,
        "items": [{"inboundItemId": inbound_item_id, "qty": 5, "batchNo": "IN-E2E-" + TAG}]
    })
    return_id = first_return.get("data")
    check("建退货草稿", ok(first_return) and return_id, first_return)
    return_detail = call("GET", "/purchase-returns/%s" % return_id, admin).get("data") or {}
    check("退货金额按采购单价算", abs(float(return_detail.get("totalAmount") or 0) - 50.0) < 0.01,
          return_detail.get("totalAmount"))
    check("退货单默认草稿", return_detail.get("status") == "DRAFT", return_detail.get("status"))

    print("== 5. 提交退货：扣库存、回退实收、冲减暂估 ==")
    stock_ret_before = stock(admin, "P003")
    done = call("POST", "/purchase-returns/%s/complete" % return_id, admin)
    check("提交退货", ok(done), done)
    check("退货扣减库存", abs(stock(admin, "P003") - stock_ret_before + 5) < 0.01,
          (stock_ret_before, stock(admin, "P003")))
    po_detail = call("GET", "/purchase-orders/%s" % po_id, admin).get("data") or {}
    check("采购单实收回退为 7", abs(float((po_detail.get("items") or [{}])[0].get("receivedQty") or 0) - 7) < 0.01,
          (po_detail.get("items") or [{}])[0])
    check("采购单退回部分到货", po_detail.get("status") == "PARTIAL", po_detail.get("status"))
    accrual_after = call("GET", "/ap/accruals?keyword=%s" % inbound_no, admin).get("data") or {}
    check("暂估冲减到 70", abs(float((accrual_after.get("records") or [{}])[0].get("amount") or 0) - 70.0) < 0.01,
          (accrual_after.get("records") or [{}])[0])

    lines = call("GET", "/purchase-returns/inbound-orders/%s/returnable" % inbound_id, admin).get("data") or []
    check("可退数量递减为 7", abs(float((lines[0] if lines else {}).get("availableQty") or 0) - 7.0) < 0.01,
          lines[:1])

    over_again = call("POST", "/purchase-returns", admin, {
        "inboundOrderId": inbound_id, "returnDate": TODAY, "reason": "再退八件",
        "items": [{"inboundItemId": inbound_item_id, "qty": 8}]
    })
    check("累计超量退货被拒", over_again.get("code") == 4010, over_again)

    second = call("POST", "/purchase-returns", admin, {
        "inboundOrderId": inbound_id, "returnDate": TODAY, "reason": "再退两件 " + TAG,
        "items": [{"inboundItemId": inbound_item_id, "qty": 2}]
    })
    call("POST", "/purchase-returns/%s/complete" % second.get("data"), admin)
    po_detail = call("GET", "/purchase-orders/%s" % po_id, admin).get("data") or {}
    check("两次退货后实收为 5", abs(float((po_detail.get("items") or [{}])[0].get("receivedQty") or 0) - 5) < 0.01,
          (po_detail.get("items") or [{}])[0])

    print("== 6. 已开票后退货自动红冲与供应商退款 ==")
    invoice = call("POST", "/ap/invoices", admin, {
        "invoiceNo": "IN-E2E-" + TAG, "invoiceType": "VAT_SPECIAL", "supplierId": supplier["id"],
        "purchaseOrderId": po_id, "invoiceDate": TODAY, "taxRate": 13, "remark": "入库收紧回归开票",
        "items": [{"purchaseOrderItemId": (po_detail.get("items") or [{}])[0].get("id"),
                   "qty": 5, "price": 10}]
    })
    invoice_id = invoice.get("data")
    check("登记发票", ok(invoice) and invoice_id, invoice)
    matched = call("POST", "/ap/invoices/%s/match" % invoice_id, planner, {"force": False})
    check("三单匹配通过", ok(matched), matched)
    paid = call("POST", "/ap/invoices/%s/payments" % invoice_id, admin,
                {"amount": 50, "method": "BANK", "payDate": TODAY})
    check("发票全额付款", ok(paid), paid)

    ret = call("POST", "/purchase-returns", admin, {
        "inboundOrderId": inbound_id, "returnDate": TODAY, "reason": "已开票后折让退回 " + TAG,
        "items": [{"inboundItemId": inbound_item_id, "qty": 1}]
    })
    ret_id = ret.get("data")
    check("已开票也可以发起退货", ok(ret) and ret_id, ret)
    done = call("POST", "/purchase-returns/%s/complete" % ret_id, admin)
    check("退货提交自动生成红字发票", ok(done) and len((done.get("data") or {}).get("redInvoiceIds") or []) == 1,
          done)
    ret_detail = call("GET", "/purchase-returns/%s" % ret_id, admin).get("data") or {}
    check("退货单带回红字发票号", bool(ret_detail.get("redInvoiceNo")), ret_detail.get("redInvoiceNo"))

    blue = call("GET", "/ap/invoices/%s" % invoice_id, admin).get("data") or {}
    check("蓝票金额红冲后剩 40", abs(float(blue.get("grossAmount") or 0) - 40.0) < 0.01,
          blue.get("grossAmount"))
    check("已付款差额转待供应商退款 10", abs(float(blue.get("refundableAmount") or 0) - 10.0) < 0.01,
          blue.get("refundableAmount"))
    accr = call("GET", "/ap/accruals?keyword=%s" % inbound_no, admin).get("data") or {}
    accr_row = (accr.get("records") or [{}])[0]
    check("暂估同步冲减到 40", abs(float(accr_row.get("amount") or 0) - 40.0) < 0.01, accr_row)
    check("暂估仍为已匹配", accr_row.get("status") == "MATCHED", accr_row.get("status"))

    credit_id = ((done.get("data") or {}).get("redInvoiceIds") or [None])[0]
    credit_cancel = call("POST", "/ap/invoices/%s/cancel" % credit_id, admin)
    check("退货生成的红票不能直接作废", credit_cancel.get("code") == 2014
          and "采购退货" in str(credit_cancel.get("message")), credit_cancel)

    rec = call("GET", "/ap/reconciliation?supplierId=%s" % supplier["id"], admin).get("data") or {}
    rec_row = next((row for row in (rec.get("records") or []) if row.get("inboundNo") == inbound_no), None)
    check("退货对账带该入库单", rec_row is not None, (rec.get("records") or [])[:1])
    check("对账已红冲金额 10", abs(float((rec_row or {}).get("creditedAmount") or 0) - 10.0) < 0.01,
          (rec_row or {}).get("creditedAmount"))
    check("对账累计已退货金额 80", abs(float((rec_row or {}).get("returnAmount") or 0) - 80.0) < 0.01,
          (rec_row or {}).get("returnAmount"))
    check("对账退货单数 3", int((rec_row or {}).get("returnCount") or 0) == 3,
          (rec_row or {}).get("returnCount"))
    check("对账带红字发票号", bool((rec_row or {}).get("redInvoiceNo")), (rec_row or {}).get("redInvoiceNo"))

    print("== 6.1 手工红字发票与作废回退 ==")
    blue_items = call("GET", "/ap/invoices/%s" % invoice_id, admin).get("data") or {}
    blue_item_id = ((blue_items.get("items") or [{}])[0]).get("id")
    credit2 = call("POST", "/ap/credit-invoices", admin, {
        "invoiceNo": "CR-E2E-" + TAG, "relatedInvoiceId": invoice_id, "invoiceDate": TODAY,
        "remark": "来料折让", "lines": [{"invoiceItemId": blue_item_id, "qty": 1}]
    })
    credit2_id = credit2.get("data")
    check("登记手工红字发票", ok(credit2) and credit2_id, credit2)
    credit2_detail = call("GET", "/ap/invoices/%s" % credit2_id, admin).get("data") or {}
    check("红票金额为负", float(credit2_detail.get("grossAmount") or 0) < 0, credit2_detail.get("grossAmount"))
    check("红票关联蓝票", credit2_detail.get("relatedInvoiceNo") == blue_items.get("invoiceNo"),
          credit2_detail.get("relatedInvoiceNo"))
    reversed_ok = call("POST", "/ap/credit-invoices/%s/match" % credit2_id, admin)
    check("红冲成功", ok(reversed_ok), reversed_ok)
    check("红冲后蓝票应付 30", abs(float((reversed_ok.get("data") or {}).get("blueGrossAmount") or 0) - 30.0) < 0.01,
          (reversed_ok.get("data") or {}).get("blueGrossAmount"))
    over_credit = call("POST", "/ap/credit-invoices", admin, {
        "invoiceNo": "CR-OVER-" + TAG, "relatedInvoiceId": invoice_id, "invoiceDate": TODAY,
        "lines": [{"invoiceItemId": blue_item_id, "qty": 9}]
    })
    check("超量红冲被拒", over_credit.get("code") == 2021, over_credit)
    credit2_cancel = call("POST", "/ap/invoices/%s/cancel" % credit2_id, admin)
    check("手工红票可以作废", ok(credit2_cancel), credit2_cancel)
    blue3 = call("GET", "/ap/invoices/%s" % invoice_id, admin).get("data") or {}
    check("作废红票后蓝票金额恢复 40", abs(float(blue3.get("grossAmount") or 0) - 40.0) < 0.01,
          blue3.get("grossAmount"))
    no_pay = call("POST", "/ap/invoices/%s/payments" % credit2_id, admin, {"amount": 1, "method": "BANK"})
    check("红字发票不能付款", no_pay.get("code") == 2014, no_pay)

    print("== 6.2 供应商退款 ==")
    refund = call("POST", "/ap/invoices/%s/refunds" % invoice_id, admin,
                  {"amount": 10, "method": "BANK", "refundDate": TODAY, "referenceNo": "RF-E2E"})
    check("登记供应商退款", ok(refund), refund)
    blue2 = call("GET", "/ap/invoices/%s" % invoice_id, admin).get("data") or {}
    check("退款后待退金额清零", abs(float(blue2.get("refundableAmount") or 0)) < 0.01,
          blue2.get("refundableAmount"))
    over = call("POST", "/ap/invoices/%s/refunds" % invoice_id, admin, {"amount": 1, "method": "BANK"})
    check("无待退款时不能再登记退款", over.get("code") == 2014, over)

    print("== 7. 汇总与权限 ==")
    summary = call("GET", "/inbound-orders/summary", admin).get("data") or {}
    check("本月无单入库有统计", int(summary.get("manualMonth") or 0) >= 1, summary)
    check("本月采购退货有统计", int(summary.get("returnMonth") or 0) >= 1, summary)
    check("本月退货金额大于 0", float(summary.get("returnAmount") or 0) > 0, summary)

    warehouse_list = call("GET", "/purchase-returns", warehouse)
    check("仓库角色可看退货单", ok(warehouse_list), warehouse_list)
    sales_list = call("GET", "/purchase-returns", sales)
    check("销售角色不可看退货单", not ok(sales_list), sales_list)
    sales_create = call("POST", "/inbound-orders", sales, {
        "type": "OTHER", "warehouseId": 1, "needInspection": False, "noOrderReason": "销售越权建单",
        "items": [{"productId": 3, "qty": 1}]
    })
    check("销售不能建入库单", not ok(sales_create), sales_create)

    returns_list = call("GET", "/purchase-returns?keyword=%s" % inbound_no, admin).get("data") or {}
    check("退货单列表可查", len(returns_list.get("records") or []) >= 2, returns_list.get("total"))


main()
print()
print("通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
