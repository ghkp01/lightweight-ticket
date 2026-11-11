# -*- coding: utf-8 -*-
"""
询价比价专项回归：
1. 从已批准申请发起询价，只带需询价且有剩余的行，重复发起被拒
2. 多家报价、覆盖报价、最低价与总价最低识别
3. 选定报价写价格库、回填申请行目标价与建议供应商
4. 选定后禁止改价、重选、取消
5. 删除报价回到待报价，取消询价后不能再报价
6. 权限与操作日志
用法：python scripts/e2e-rfq.py [base_url]
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


def approved_requisition(token, product_id, qty, price):
    req = call("POST", "/purchase-requisitions", token, {
        "sourceType": "STOCK", "department": "计划部", "needDate": TODAY,
        "purpose": "询价回归 " + TAG,
        "items": [{"productId": product_id, "qty": qty, "targetPrice": price, "needQuote": True}]
    })
    req_id = req.get("data")
    call("POST", "/purchase-requisitions/%s/submit" % req_id, token)
    call("POST", "/purchase-requisitions/%s/approve" % req_id, token)
    return req_id


def main():
    admin = login("admin")
    planner = login("planner")
    warehouse = login("warehouse")
    qc = login("qc")
    sales = login("sales")
    if not all([admin, planner, warehouse, qc, sales]):
        print("登录失败，请确认服务已启动且演示账号存在")
        sys.exit(1)

    suppliers = call("GET", "/suppliers/options", admin).get("data") or []
    supplier_a = next(s["id"] for s in suppliers if s["code"] == "S001")
    supplier_b = next(s["id"] for s in suppliers if s["code"] == "S002")

    print("== 演示与列表 ==")
    all_rfqs = call("GET", "/purchase-rfqs", admin).get("data") or {}
    records = all_rfqs.get("records") or []
    # 全新库里没有历史询价单，这里只校验列表口径（带报价家数），
    # 「多家报价」由下面自己发起的询价单验证，不再依赖演示数据。
    check("询价列表带报价家数", not records or any("quoteCount" in row for row in records), records[:1])

    summary = call("GET", "/purchase-rfqs/summary", admin).get("data") or {}
    check("汇总带待报价与价格库数量", "open" in summary and "prices" in summary, summary)

    print("== 从申请发起询价 ==")
    req_id = approved_requisition(planner, 6, 30, 1.2)
    created = call("POST", "/purchase-rfqs/from-requisition/%s" % req_id, planner, {
        "deadline": TODAY, "remark": "E2E 询价 " + TAG
    })
    rfq_id = created.get("data")
    check("从申请生成询价单", ok(created) and rfq_id, created)

    detail = call("GET", "/purchase-rfqs/%s" % rfq_id, admin).get("data") or {}
    check("询价单带来源申请", detail.get("requisitionId") == req_id, detail.get("requisitionId"))
    check("询价明细只有需询价的行", len(detail.get("items") or []) == 1, detail.get("items"))
    item_id = detail["items"][0]["id"]
    check("初始没有报价", detail.get("status") == "OPEN" and len(detail.get("quotes") or []) == 0)

    duplicate = call("POST", "/purchase-rfqs/from-requisition/%s" % req_id, planner, {})
    check("同一申请行不能重复询价", duplicate.get("code") == 1002, duplicate)

    print("== 报价与比价 ==")
    quote_a = call("POST", "/purchase-rfqs/%s/quotes" % rfq_id, planner, {
        "supplierId": supplier_a, "leadDays": 7, "paymentTerm": "月结 30 天",
        "validUntil": "2026-12-31", "remark": "含税到厂",
        "items": [{"rfqItemId": item_id, "price": 1.1}]
    })
    check("录入第一家报价", ok(quote_a), quote_a)
    quote_a_id = (quote_a.get("data") or {}).get("quoteId")
    check("报价合计按数量计算", abs(float((quote_a.get("data") or {}).get("totalAmount")) - 33.0) < 0.01,
          quote_a.get("data"))

    quote_b = call("POST", "/purchase-rfqs/%s/quotes" % rfq_id, planner, {
        "supplierId": supplier_b, "leadDays": 5, "paymentTerm": "月结 15 天",
        "items": [{"rfqItemId": item_id, "price": 1.25}]
    })
    check("录入第二家报价", ok(quote_b), quote_b)

    compared = call("GET", "/purchase-rfqs/%s" % rfq_id, admin).get("data") or {}
    check("报价后状态已报价", compared.get("status") == "QUOTED", compared.get("status"))
    check("两家报价都在", len(compared.get("quotes") or []) == 2, compared.get("quotes"))
    check("识别总价最低", compared.get("bestQuoteId") == quote_a_id, compared.get("bestQuoteId"))
    check("明细标出最低单价", abs(float(compared["items"][0].get("lowestPrice")) - 1.1) < 0.01,
          compared["items"][0])

    overwrite = call("POST", "/purchase-rfqs/%s/quotes" % rfq_id, planner, {
        "supplierId": supplier_a, "leadDays": 6,
        "items": [{"rfqItemId": item_id, "price": 1.05}]
    })
    check("同一供应商重新报价覆盖", ok(overwrite), overwrite)
    overwritten = call("GET", "/purchase-rfqs/%s" % rfq_id, admin).get("data") or {}
    check("覆盖后仍只有两家", len(overwritten.get("quotes") or []) == 2, overwritten.get("quotes"))
    check("覆盖后最低价更新", abs(float(overwritten["items"][0].get("lowestPrice")) - 1.05) < 0.01,
          overwritten["items"][0])

    print("== 选定与价格库 ==")
    selected = call("POST", "/purchase-rfqs/%s/select" % rfq_id, planner, {"quoteId": quote_a_id})
    check("选定报价成功", ok(selected) and selected["data"].get("priceCount") == 1, selected)

    after = call("GET", "/purchase-rfqs/%s" % rfq_id, admin).get("data") or {}
    check("询价单状态已选定", after.get("status") == "SELECTED", after.get("status"))
    check("选定供应商已记录", after.get("selectedSupplierName") is not None, after)

    req_detail = call("GET", "/purchase-requisitions/%s" % req_id, admin).get("data") or {}
    req_item = (req_detail.get("items") or [{}])[0]
    check("申请行目标价被回填", abs(float(req_item.get("targetPrice")) - 1.05) < 0.01, req_item)
    check("申请行建议供应商被回填", req_item.get("suggestSupplierId") == supplier_a, req_item)

    prices = call("GET", "/supplier-prices?supplierId=%s&productId=6&validOnly=true" % supplier_a,
                  admin).get("data") or []
    check("价格库写入选定价格", len(prices) >= 1 and abs(float(prices[0]["price"]) - 1.05) < 0.01, prices[:1])
    check("价格库带来源询价单号", prices and prices[0].get("sourceNo") == after.get("rfqNo"), prices[:1])

    reselect = call("POST", "/purchase-rfqs/%s/select" % rfq_id, planner, {"quoteId": quote_a_id})
    check("已选定不能重选", reselect.get("code") == 2014, reselect)
    quote_after = call("POST", "/purchase-rfqs/%s/quotes" % rfq_id, planner, {
        "supplierId": supplier_b, "items": [{"rfqItemId": item_id, "price": 1.0}]
    })
    check("已选定不能改报价", quote_after.get("code") == 2014, quote_after)
    cancel_selected = call("POST", "/purchase-rfqs/%s/cancel" % rfq_id, planner)
    check("已选定不能取消", cancel_selected.get("code") == 2014, cancel_selected)

    print("== 删除报价与取消 ==")
    req2 = approved_requisition(planner, 5, 50, 0.8)
    rfq2 = call("POST", "/purchase-rfqs/from-requisition/%s" % req2, planner, {}).get("data")
    detail2 = call("GET", "/purchase-rfqs/%s" % rfq2, admin).get("data") or {}
    item2 = detail2["items"][0]["id"]
    quote2 = call("POST", "/purchase-rfqs/%s/quotes" % rfq2, planner, {
        "supplierId": supplier_a, "items": [{"rfqItemId": item2, "price": 0.75}]
    })
    quote2_id = (quote2.get("data") or {}).get("quoteId")
    removed = call("DELETE", "/purchase-rfqs/%s/quotes/%s" % (rfq2, quote2_id), planner)
    check("可以删除报价", ok(removed), removed)
    back_open = call("GET", "/purchase-rfqs/%s" % rfq2, admin).get("data") or {}
    check("删完报价回到待报价", back_open.get("status") == "OPEN", back_open.get("status"))

    cancelled = call("POST", "/purchase-rfqs/%s/cancel" % rfq2, planner)
    check("待报价可以取消", ok(cancelled), cancelled)
    quote_cancelled = call("POST", "/purchase-rfqs/%s/quotes" % rfq2, planner, {
        "supplierId": supplier_b, "items": [{"rfqItemId": item2, "price": 0.8}]
    })
    check("已取消不能报价", quote_cancelled.get("code") == 2014, quote_cancelled)

    print("== 权限与日志 ==")
    sales_list = call("GET", "/purchase-rfqs", sales)
    check("销售看不到询价", sales_list.get("code") == 403 or sales_list.get("_http") == 403, sales_list)
    qc_list = call("GET", "/purchase-rfqs", qc)
    check("质检看不到询价", qc_list.get("code") == 403 or qc_list.get("_http") == 403, qc_list)
    check("仓库可以查看询价", call("GET", "/purchase-rfqs", warehouse).get("code") == 0)
    warehouse_select = call("POST", "/purchase-rfqs/%s/select" % rfq_id, warehouse, {"quoteId": quote_a_id})
    check("仓库不能选定报价", warehouse_select.get("code") == 403 or warehouse_select.get("_http") == 403,
          warehouse_select)

    logs = call("GET", "/system/logs?module=%E8%AF%A2%E4%BB%B7%E6%AF%94%E4%BB%B7&limit=50", admin).get("data") or []
    select_log = next((row for row in logs if row.get("action") == "选定报价"), None)
    check("选定报价写入操作日志", select_log is not None, logs[:1])

    print()
    print("通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
    if FAILED:
        print("失败项：" + " / ".join(FAILED))
        sys.exit(1)


if __name__ == "__main__":
    main()
