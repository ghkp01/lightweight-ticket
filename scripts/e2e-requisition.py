# -*- coding: utf-8 -*-
"""
采购申请与审批专项回归：
1. 申请单建单、编辑、提交、审批、驳回、取消、删除护栏
2. 审批阈值：超阈值只有管理员能批
3. 部分转采购单 / 剩余转单，采购单回链申请
4. 采购单取消后已转数量退回申请，状态回退
5. 权限：销售看不到申请，质检不能审批，仓库能提申请
6. 操作日志与审批阈值配置
用法：python scripts/e2e-requisition.py [base_url]
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
    qc = login("qc")
    sales = login("sales")
    if not all([admin, planner, warehouse, qc, sales]):
        print("登录失败，请确认服务已启动且演示账号存在")
        sys.exit(1)

    print("== 申请单基础 ==")
    listed = call("GET", "/purchase-requisitions", admin).get("data") or {}
    records = listed.get("records") or []
    check("申请单列表非空", len(records) > 0, listed.get("total"))
    sample = next((row for row in records if row.get("department")), records[0] if records else {})
    check("列表带申请人、部门与金额",
          sample.get("requesterName") and sample.get("department") and "totalAmount" in sample, sample)
    pending = call("GET", "/purchase-requisitions?status=SUBMITTED", admin).get("data") or {}
    check("待审批筛选可用",
          all(row.get("status") == "SUBMITTED" for row in (pending.get("records") or [])),
          (pending.get("records") or [])[:1])

    summary = call("GET", "/purchase-requisitions/summary", admin).get("data") or {}
    check("汇总带待审批与本月金额", "pending" in summary and "monthAmount" in summary, summary)

    configs = call("GET", "/system/configs", admin).get("data") or []
    threshold = next((c for c in configs if c.get("key") == "purchase.approval.threshold"), None)
    check("审批阈值在系统配置里", threshold is not None and threshold.get("value") == "5000", threshold)

    print("== 建单、编辑与权限 ==")
    created = call("POST", "/purchase-requisitions", warehouse, {
        "sourceType": "STOCK", "department": "仓储部", "needDate": TODAY,
        "purpose": "E2E 补库申请 " + TAG,
        "items": [
            {"productId": 5, "qty": 1000, "targetPrice": 0.8, "suggestSupplierId": 2, "needQuote": False,
             "remark": "常用螺栓"},
            {"productId": 6, "qty": 200, "targetPrice": 1.2, "suggestSupplierId": 2, "needQuote": False}
        ]
    })
    req_id = created.get("data")
    check("仓库可以提申请", ok(created) and req_id, created)

    detail = call("GET", "/purchase-requisitions/%s" % req_id, admin).get("data") or {}
    check("草稿状态与金额汇总", detail.get("status") == "DRAFT"
          and abs(float(detail.get("totalAmount") or 0) - 1040.0) < 0.01, detail.get("totalAmount"))
    check("明细带剩余数量", len(detail.get("items") or []) == 2
          and float(detail["items"][0]["remainingQty"]) == 1000.0, detail.get("items"))

    sales_list = call("GET", "/purchase-requisitions", sales)
    check("销售看不到采购申请", sales_list.get("code") == 403 or sales_list.get("_http") == 403, sales_list)

    updated = call("PUT", "/purchase-requisitions/%s" % req_id, warehouse, {
        "sourceType": "STOCK", "department": "仓储部", "needDate": TODAY,
        "purpose": "E2E 补库申请（已改）" + TAG,
        "items": [{"productId": 5, "qty": 1000, "targetPrice": 0.85, "suggestSupplierId": 2}]
    })
    check("草稿可编辑", ok(updated), updated)
    after_edit = call("GET", "/purchase-requisitions/%s" % req_id, admin).get("data") or {}
    check("编辑后金额重算", abs(float(after_edit.get("totalAmount") or 0) - 850.0) < 0.01,
          after_edit.get("totalAmount"))

    submitted = call("POST", "/purchase-requisitions/%s/submit" % req_id, warehouse)
    check("提交进入审批", ok(submitted), submitted)
    edit_locked = call("PUT", "/purchase-requisitions/%s" % req_id, warehouse, {
        "sourceType": "STOCK", "items": [{"productId": 5, "qty": 1, "targetPrice": 1}]
    })
    check("已提交不能改", edit_locked.get("code") == 2014, edit_locked)

    qc_approve = call("POST", "/purchase-requisitions/%s/approve" % req_id, qc)
    check("质检不能审批", qc_approve.get("code") == 403 or qc_approve.get("_http") == 403, qc_approve)

    approved = call("POST", "/purchase-requisitions/%s/approve" % req_id, planner)
    check("计划可以审批阈值内申请", ok(approved), approved)
    check("审批后状态已批准",
          (call("GET", "/purchase-requisitions/%s" % req_id, admin).get("data") or {}).get("status") == "APPROVED")

    print("== 部分转单与回退 ==")
    items = (call("GET", "/purchase-requisitions/%s" % req_id, admin).get("data") or {}).get("items") or []
    item_id = items[0]["id"]
    first = call("POST", "/purchase-requisitions/%s/to-order" % req_id, planner, {
        "supplierId": 2, "warehouseId": 1, "needInspection": False,
        "remark": "先转 400 件",
        "items": [{"requisitionItemId": item_id, "qty": 400, "price": 0.85}]
    })
    first_order_id = (first.get("data") or {}).get("orderId")
    check("部分转采购单", ok(first) and first_order_id, first)
    check("转单后状态部分转单",
          (call("GET", "/purchase-requisitions/%s" % req_id, admin).get("data") or {}).get("status") == "PARTIAL")

    first_order = call("GET", "/purchase-orders/%s" % first_order_id, admin).get("data") or {}
    check("采购单回链申请单", first_order.get("requisitionNo") == detail.get("reqNo"), first_order.get("requisitionNo"))
    check("采购单数量按本次转单", abs(float(first_order.get("totalAmount") or 0) - 340.0) < 0.01,
          first_order.get("totalAmount"))

    second = call("POST", "/purchase-requisitions/%s/to-order" % req_id, planner, {
        "supplierId": 2, "warehouseId": 1, "needInspection": False,
        "items": [{"requisitionItemId": item_id, "qty": 600, "price": 0.85}]
    })
    second_order_id = (second.get("data") or {}).get("orderId")
    check("剩余数量再转单", ok(second) and second_order_id, second)
    check("全部转完状态已转单",
          (call("GET", "/purchase-requisitions/%s" % req_id, admin).get("data") or {}).get("status") == "CONVERTED")

    too_much = call("POST", "/purchase-requisitions/%s/to-order" % req_id, planner, {
        "supplierId": 2, "warehouseId": 1,
        "items": [{"requisitionItemId": item_id, "qty": 1, "price": 0.85}]
    })
    check("已转完不能再转", too_much.get("code") == 2014, too_much)

    call("DELETE", "/purchase-orders/%s" % first_order_id, admin)
    released = call("GET", "/purchase-requisitions/%s" % req_id, admin).get("data") or {}
    check("删除采购单退回已转数量", released.get("status") == "PARTIAL", released.get("status"))
    check("退回后剩余数量恢复", abs(float(released["items"][0]["remainingQty"]) - 400.0) < 0.01,
          released["items"][0])

    call("POST", "/purchase-orders/%s/cancel" % second_order_id, admin)
    released_all = call("GET", "/purchase-requisitions/%s" % req_id, admin).get("data") or {}
    check("取消采购单后退回全部", released_all.get("status") == "APPROVED", released_all.get("status"))
    check("已转数量归零", abs(float(released_all["items"][0]["convertedQty"])) < 0.01, released_all["items"][0])

    print("== 审批阈值与驳回 ==")
    high = call("POST", "/purchase-requisitions", planner, {
        "sourceType": "MANUAL", "department": "生产部", "needDate": TODAY,
        "purpose": "大额设备备件 " + TAG,
        "items": [{"productId": 3, "qty": 200, "targetPrice": 45.5}]
    })
    high_id = high.get("data")
    call("POST", "/purchase-requisitions/%s/submit" % high_id, planner)
    planner_approve = call("POST", "/purchase-requisitions/%s/approve" % high_id, planner)
    check("超阈值计划不能批", planner_approve.get("code") == 403, planner_approve)
    admin_approve = call("POST", "/purchase-requisitions/%s/approve" % high_id, admin)
    check("超阈值管理员可以批", ok(admin_approve), admin_approve)

    reject_target = call("POST", "/purchase-requisitions", warehouse, {
        "sourceType": "URGENT", "department": "仓储部", "purpose": "驳回测试 " + TAG,
        "items": [{"productId": 6, "qty": 10, "targetPrice": 1.2}]
    }).get("data")
    call("POST", "/purchase-requisitions/%s/submit" % reject_target, warehouse)
    empty_reason = call("POST", "/purchase-requisitions/%s/reject" % reject_target, planner, {"reason": "  "})
    check("驳回必须填原因", empty_reason.get("code") == 1002, empty_reason)
    rejected = call("POST", "/purchase-requisitions/%s/reject" % reject_target, planner, {"reason": "预算不足，下月再提"})
    check("可以驳回并记录原因", ok(rejected)
          and (call("GET", "/purchase-requisitions/%s" % reject_target, planner).get("data") or {}).get("status") == "REJECTED",
          rejected)

    print("== 取消与删除 ==")
    draft = call("POST", "/purchase-requisitions", warehouse, {
        "sourceType": "MANUAL", "purpose": "删除测试 " + TAG,
        "items": [{"productId": 6, "qty": 1, "targetPrice": 1}]
    }).get("data")
    removed = call("DELETE", "/purchase-requisitions/%s" % draft, warehouse)
    check("草稿可删除", ok(removed), removed)
    check("删除后查不到", call("GET", "/purchase-requisitions/%s" % draft, admin).get("code") == 2009)

    to_cancel = call("POST", "/purchase-requisitions", warehouse, {
        "sourceType": "MANUAL", "purpose": "取消测试 " + TAG,
        "items": [{"productId": 6, "qty": 1, "targetPrice": 1}]
    }).get("data")
    cancelled = call("POST", "/purchase-requisitions/%s/cancel" % to_cancel, warehouse)
    check("草稿可取消", ok(cancelled), cancelled)
    check("取消后状态已取消",
          (call("GET", "/purchase-requisitions/%s" % to_cancel, admin).get("data") or {}).get("status") == "CANCELLED")

    print("== 操作日志 ==")
    logs = call("GET", "/system/logs?module=%E9%87%87%E8%B4%AD%E7%94%B3%E8%AF%B7&limit=50", admin).get("data") or []
    convert_log = next((row for row in logs if row.get("action") == "转采购"), None)
    check("转采购单写入操作日志", convert_log is not None, logs[:1])
    approve_log = next((row for row in logs if row.get("action") == "审批"), None)
    check("审批动作有日志", approve_log is not None, logs[:1])

    print()
    print("通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
    if FAILED:
        print("失败项：" + " / ".join(FAILED))
        sys.exit(1)


if __name__ == "__main__":
    main()
