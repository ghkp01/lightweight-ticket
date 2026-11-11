# -*- coding: utf-8 -*-
"""
检验项与抽样专项回归：
1. 工序检验项维护（全量覆盖）
2. 抽样方案计算（全检 / 抽检按批量分档）
3. 配了检验项的工序必须逐项给结果，缺项被拒
4. 任一项不合格整单不合格，明细可查
5. 抽检按接收数判定
6. 不良项 Top 统计
用法：python scripts/e2e-items.py [base_url]
"""
import json
import sys
import time
import urllib.error
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
    qc = login("qc")
    if not admin or not qc:
        print("登录失败，请确认服务已启动且演示账号存在")
        sys.exit(1)

    processes = {p["code"]: p for p in call("GET", "/processes", admin)["data"]}
    assy = processes["ASSY"]
    products = call("GET", "/products", admin)["data"]
    p001 = [p for p in products if p["code"] == "P001"][0]

    print("== 1. 工序检验项维护 ==")
    items = [
        {"itemCode": "E2E-APPEAR", "itemName": "外观", "standard": "无划伤、无变形",
         "method": "目视", "valueType": "VISUAL", "required": True, "sortNo": 1, "enabled": True},
        {"itemCode": "E2E-DIM", "itemName": "装配尺寸", "standard": "180±0.5 mm",
         "method": "卡尺", "valueType": "MEASURE", "lowerLimit": 179.5, "upperLimit": 180.5,
         "unit": "mm", "required": True, "sortNo": 2, "enabled": True}
    ]
    payload = call("PUT", "/inspections/items", admin, {"processId": assy["id"], "items": items})
    check("工序检验项保存成功", ok(payload) and len(payload["data"]) == 2, payload)
    saved = call("GET", "/inspections/items?processId=%d" % assy["id"], admin)["data"]
    check("检验项按排序返回", len(saved) == 2 and saved[0]["itemName"] == "外观", saved)
    check("计量项带上下限", float(saved[1]["lowerLimit"]) == 179.5, saved[1])

    print("== 2. 抽样方案 ==")
    plan = call("GET", "/inspections/sample-plan?lotQty=500&plan=SAMPLE&aql=2.5", admin)["data"]
    check("批量 500 抽 50", float(plan["sampleQty"]) == 50, plan)
    check("AQL 2.5 接收数 1", plan["acceptQty"] == 1 and plan["rejectQty"] == 2, plan)
    full = call("GET", "/inspections/sample-plan?lotQty=500&plan=FULL", admin)["data"]
    check("全检样本量等于批量", float(full["sampleQty"]) == 500 and full["acceptQty"] == 0, full)
    small = call("GET", "/inspections/sample-plan?lotQty=50&plan=SAMPLE&aql=2.5", admin)["data"]
    check("批量 50 抽 8", float(small["sampleQty"]) == 8, small)

    print("== 3. 建工单 ==")
    wo_id = call("POST", "/work-orders", admin, {
        "productId": p001["id"], "planQty": 3, "planStart": TODAY, "planEnd": TODAY,
        "priority": "NORMAL", "remark": "检验项回归"
    })["data"]
    call("POST", "/scheduling/%d/schedule" % wo_id, admin, {"date": TODAY})
    call("POST", "/work-orders/%d/release" % wo_id, admin)
    detail = call("GET", "/work-orders/%d" % wo_id, admin)["data"]
    work_no = detail["workNo"]
    op2 = [o for o in detail["operations"] if o["seq"] == 2][0]

    print("== 4. 必检项不能漏 ==")
    body = {"type": "PROCESS", "refType": "WORK_ORDER", "refNo": work_no, "workOrderId": wo_id,
            "productId": p001["id"], "inspectQty": 3, "defectQty": 0, "inspector": "qc",
            "operationSeq": 3, "remark": "检验项回归"}
    payload = call("POST", "/inspections", qc, body)
    check("漏填必检项被拒(1002)", payload.get("code") == 1002, payload)
    check("提示缺了哪几项", "必检项" in str(payload.get("message")), payload.get("message"))

    print("== 5. 逐项录入与判定 ==")
    item_results = [
        {"itemId": saved[0]["id"], "itemName": "外观", "standard": "无划伤、无变形",
         "sampleQty": 3, "defectQty": 0, "measured": None, "result": "PASS"},
        {"itemId": saved[1]["id"], "itemName": "装配尺寸", "standard": "180±0.5 mm",
         "sampleQty": 3, "defectQty": 1, "measured": "181.2", "result": "FAIL", "remark": "超差"}
    ]
    payload = call("POST", "/inspections", qc,
                   dict({k: v for k, v in body.items() if k != "defectQty"}, items=item_results))
    check("逐项提交成功", ok(payload), payload)
    check("任一项不合格整单不合格", payload["data"].get("result") == "FAIL", payload.get("data"))
    check("回带检验项数量", payload["data"].get("itemCount") == 2, payload.get("data"))

    inspection_id = payload["data"]["id"]
    detail_data = call("GET", "/inspections/%d" % inspection_id, admin)["data"]
    check("详情带检验项明细", len(detail_data.get("items") or []) == 2, detail_data.get("items"))
    check("实测值已记录",
          any(str(i.get("measured")) == "181.2" for i in detail_data.get("items") or []), detail_data.get("items"))
    check("全检不良数取明细合计", float(detail_data.get("defectQty")) == 1, detail_data.get("defectQty"))

    print("== 6. 抽检按接收数判定 ==")
    sample_body = dict(body, inspectQty=500, samplePlan="SAMPLE", aql=2.5, operationSeq=2,
                       items=[dict(i, sampleQty=50, defectQty=0, result="PASS") for i in item_results])
    payload = call("POST", "/inspections", qc, dict(sample_body, defectQty=1))
    check("抽检不良 1 件判合格", ok(payload) and payload["data"].get("result") == "PASS", payload)
    check("回带 Ac/Re", payload["data"].get("acceptQty") == 1 and payload["data"].get("rejectQty") == 2,
          payload.get("data"))

    payload = call("POST", "/inspections", qc, dict(sample_body, defectQty=2))
    check("抽检不良 2 件判不合格", ok(payload) and payload["data"].get("result") == "FAIL", payload)

    print("== 7. 不良项 Top ==")
    top = call("GET", "/inspections/item-defects?days=30", admin)["data"]
    check("统计里能出现装配尺寸", any(row["itemName"] == "装配尺寸" for row in top), top[:2])
    check("统计带不合格次数与不良数",
          any(int(row["failCount"]) >= 1 and float(row["defectQty"]) >= 1 for row in top), top[:2])

    print("== 8. 收尾：清空该工序检验项 ==")
    payload = call("PUT", "/inspections/items", admin, {"processId": assy["id"], "items": []})
    check("检验项已清空", ok(payload) and len(payload["data"]) == 0, payload)

    print("\n结果：%d 项通过，%d 项失败" % (PASSED, len(FAILED)))
    if FAILED:
        print("失败项：" + "、".join(FAILED))
        sys.exit(1)


if __name__ == "__main__":
    main()
