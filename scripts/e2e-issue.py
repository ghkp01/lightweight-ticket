"""生产领料与退料端到端回归：批量领料、单据查询、超领拦截、非 BOM 拦截、出库单挂工单、
领多了开退料单按原批次回库、退料单查询与超退拦截。

用法：python scripts/e2e-issue.py [http://localhost:8080]
"""

import json
import sys
import urllib.error
import urllib.request
from datetime import date
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
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as err:
        return json.loads(err.read().decode())


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


def pending_lines(detail):
    """待领量口径与后端一致：需求 − 已领。

    退料不会把已领量减回去（后端只在领料时累加 issued_qty），所以这里不能把
    returnedQty 加进待领量，否则会挑到「看起来缺料、其实已经领足」的工单，
    批量领料直接报「已领足」。
    """
    lines = []
    for line in detail.get("bom") or []:
        if str(line.get("source") or "") == "SUBSTITUTE":
            continue
        need = float(line.get("needQty") or 0)
        issued = float(line.get("issuedQty") or 0)
        remaining = max(0.0, need - issued)
        if remaining > 0:
            lines.append((line, remaining))
    return lines


def main():
    token = login("admin")
    if not token:
        print("登录失败，请确认服务已启动且演示账号存在")
        sys.exit(1)

    print("== 1. 找到可领料工单 ==")
    target = None
    for status in ("IN_PROGRESS", "RELEASED"):
        page = call("GET", "/work-orders?status=%s&page=1&size=50" % status, token)
        for work in (page.get("data") or {}).get("records") or []:
            detail = call("GET", "/work-orders/%s" % work["id"], token).get("data") or {}
            lines = pending_lines(detail)
            if lines:
                target = (detail, lines)
                break
        if target:
            break
    check("存在待领物料的工单", target is not None)
    if not target:
        print("没有可领料工单，跳过后续领料用例")
        return
    detail, lines = target
    work_id = detail["id"]
    work_no = detail["workNo"]
    print("  工单 %s，待领 %d 行" % (work_no, len(lines)))

    print("== 2. 批量领料生成领料单 ==")
    line, remaining = lines[0]
    qty = round(min(remaining, 1.0), 2)
    warehouses = call("GET", "/warehouses", token).get("data") or []
    raw = next((w for w in warehouses if w.get("type") == "RAW" and w.get("enabled") is not False), None)
    ware_id = raw["id"] if raw else (warehouses[0]["id"] if warehouses else None)
    body = {
        "warehouseId": ware_id,
        "issuer": "e2e 测试员",
        "team": "装配一组",
        "source": "PC",
        "remark": "端到端回归",
        "items": [{"productId": line["materialId"], "qty": qty}],
    }
    created = call("POST", "/work-orders/%s/issue-batch" % work_id, token, body)
    check("批量领料成功", created.get("code") == 0, created)
    issue_no = (created.get("data") or {}).get("issueNo")
    check("返回领料单号", bool(issue_no) and str(issue_no).startswith("PI"), issue_no)
    check("返回领料人", (created.get("data") or {}).get("issuer") == "e2e 测试员", created.get("data"))

    print("== 3. 领料单可查询 ==")
    issues = call("GET", "/work-orders/%s/issues" % work_id, token).get("data") or []
    matched = next((item for item in issues if item.get("issueNo") == issue_no), None)
    check("工单详情能查到领料单", matched is not None, issues[:2] if issues else [])
    check("领料单带仓库与班组", bool(matched) and matched.get("team") == "装配一组", matched)
    check("领料单明细非空", bool(matched) and int(matched.get("itemCount") or 0) >= 1, matched)

    print("== 4. 超领与非 BOM 拦截 ==")
    over = call("POST", "/work-orders/%s/issue-batch" % work_id, token, {
        "warehouseId": ware_id, "issuer": "e2e 测试员", "source": "PC",
        "items": [{"productId": line["materialId"], "qty": float(remaining) + 100}],
    })
    check("超领被拒(2010)", over.get("code") == 2010, over)

    outside = call("POST", "/work-orders/%s/issue-batch" % work_id, token, {
        "warehouseId": ware_id, "issuer": "e2e 测试员", "source": "PC",
        "items": [{"productId": 99999999, "qty": 1}],
    })
    check("非 BOM 物料被拒(2008)", outside.get("code") == 2008, outside)

    print("== 5. 出库单挂工单 ==")
    if warehouses and line.get("materialId"):
        bad = call("POST", "/outbound-orders", token, {
            "type": "ISSUE_OTHER", "warehouseId": ware_id,
            "items": [{"productId": line["materialId"], "qty": 1}],
        })
        check("车间领用不选工单被拒(1002)", bad.get("code") == 1002, bad)

    print("== 6. 领多了要能退料入库 ==")
    # 退料用一张新工单做，避免历史领退记录累积影响可退量
    today = date.today().isoformat()
    products = call("GET", "/products", token).get("data") or []
    finished = next((item for item in products if item.get("code") == "P001"), None)
    check("找到演示成品 P001", finished is not None)
    if not finished:
        return
    new_id = call("POST", "/work-orders", token, {
        "productId": finished["id"], "planQty": 5, "planStart": today, "planEnd": today,
    }).get("data")
    call("POST", "/scheduling/%s/schedule" % new_id, token, {"date": today})
    call("POST", "/work-orders/%s/release" % new_id, token, {})
    new_detail = call("GET", "/work-orders/%s" % new_id, token).get("data") or {}
    new_lines = pending_lines(new_detail)
    check("新工单有 BOM 需求", bool(new_lines), new_lines[:2])
    if not new_lines:
        return
    r_line, r_need = new_lines[0]
    r_material = r_line["materialId"]
    r_qty = round(min(r_need, 2.0), 2)
    r_stock_before = current_stock(token, r_material, ware_id)
    call("POST", "/work-orders/%s/issue-batch" % new_id, token, {
        "warehouseId": ware_id, "issuer": "e2e 仓管", "source": "PC",
        "items": [{"productId": r_material, "qty": r_qty}],
    })

    returnable = call("GET", "/work-orders/%s/returnable" % new_id, token).get("data") or []
    row = next((item for item in returnable if item.get("materialId") == r_material), None)
    check("可退明细能查到刚领的物料", row is not None, returnable[:2] if returnable else [])
    issued_qty = float((row or {}).get("issuedQty") or 0)
    returned_qty = float((row or {}).get("returnedQty") or 0)
    returnable_qty = float((row or {}).get("returnableQty") or 0)
    check("已领量等于本次领料量", abs(issued_qty - r_qty) < 0.001, row)
    check("可退量 = 已领 - 已退", abs(issued_qty - returned_qty - returnable_qty) < 0.001, row)

    over_return = call("POST", "/work-orders/%s/return-material" % new_id, token, {
        "returner": "e2e 仓管",
        "reason": "测试超退",
        "items": [{"productId": r_material, "qty": r_qty + 1}],
    })
    check("超退被拒(2013)", over_return.get("code") == 2013, over_return)

    back = call("POST", "/work-orders/%s/return-material" % new_id, token, {
        "warehouseId": ware_id,
        "returner": "e2e 仓管",
        "reason": "领多了退回",
        "source": "PC",
        "items": [{"productId": r_material, "qty": r_qty}],
    })
    check("退料成功", back.get("code") == 0, back)
    data = back.get("data") or {}
    check("返回退料单号(MR)", str(data.get("returnNo") or "").startswith("MR"), data.get("returnNo"))
    check("退料单带退料人与原因", data.get("returner") == "e2e 仓管", data)
    r_stock_after = current_stock(token, r_material, ware_id)
    check("退料后库存回到原领料仓", abs(r_stock_after - r_stock_before) < 0.001,
          {"before": r_stock_before, "after": r_stock_after, "qty": r_qty})

    returns = call("GET", "/work-orders/%s/returns" % new_id, token).get("data") or []
    check("工单能查到退料单", any(item.get("returnNo") == data.get("returnNo") for item in returns), returns[:2])
    detail_after = call("GET", "/work-orders/%s" % new_id, token).get("data") or {}
    check("工单详情带退料记录", bool(detail_after.get("returns")), detail_after.get("returns"))
    bom_after = next((b for b in detail_after.get("bom") or [] if b.get("materialId") == r_material), None)
    check("退料后工单已退量等于退货量", bom_after is not None
          and abs(float(bom_after.get("returnedQty") or 0) - r_qty) < 0.001, bom_after)

    again = call("POST", "/work-orders/%s/return-material" % new_id, token, {
        "returner": "e2e 仓管",
        "items": [{"productId": r_material, "qty": 1}],
    })
    check("退完再退被拒(2013)", again.get("code") == 2013, again)
    after_all = call("GET", "/work-orders/%s/returnable" % new_id, token).get("data") or []
    final_row = next((item for item in after_all if item.get("materialId") == r_material), None)
    check("退完后可退量归零", final_row is None or float(final_row.get("returnableQty") or 0) == 0, final_row)


def current_stock(token, product_id, warehouse_id):
    rows = (call("GET", "/inventory", token).get("data") or {})
    records = rows.get("records") if isinstance(rows, dict) else rows
    for rec in records or []:
        if rec.get("productId") == product_id and rec.get("warehouseId") == warehouse_id:
            return float(rec.get("qty") or 0)
    return 0.0


main()
print()
print("通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
