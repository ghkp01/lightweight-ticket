"""物料需求计算（MRP）端到端回归：缺口口径、只看缺料、建议下单日、一键生成采购申请、提交后抵扣。

脚本会先造一张「料不够」的工单（新成品 + 大用量 BOM + 工艺路线），
再验证系统算出的缺口、建议下单日与采购申请链路。
注意：会真实留下一个测试成品、一张工单与一张采购申请，供演示查看。

用法：python scripts/e2e-mrp.py [http://localhost:8080]
"""

import json
import os
import sys
import urllib.error
import urllib.request
from datetime import date, timedelta

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PASSED = 0
FAILED = []
TODAY = date.today().isoformat()


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
    return float(value or 0)


def prepare_demand(token, products):
    """造一张必然缺料的工单：新成品 1 件，BOM 里某原料用量远超库存"""
    section("0. 准备一张缺料工单")
    raw = next((p for p in products if p.get("code") == "P003"), None)
    template = next((p for p in products if p.get("code") == "P001"), None)
    check("演示数据里有原料 P003 与成品 P001", raw is not None and template is not None)
    if not raw or not template:
        return None
    suffix = str(int.from_bytes(os.urandom(2), "big"))
    created = call("POST", "/products", token, {
        "code": "MRP" + suffix, "name": "MRP 缺料测试件" + suffix, "spec": "MRP", "category": "FINISHED",
        "unit": "件", "price": 10, "cost": 5, "safetyStock": 0, "lossRate": 0
    })
    check("创建测试成品", ok(created), created.get("message"))
    product_id = created.get("data")
    bom = call("PUT", "/products/%s/bom" % product_id, token, {
        "items": [{"materialId": raw["id"], "qty": 5000, "operationSeq": 1}]
    })
    check("写入大用量 BOM", ok(bom), bom.get("message"))
    routing = call("POST", "/products/%s/routing/copy" % product_id, token,
                   {"sourceProductId": template["id"], "overwrite": True})
    check("复制工艺路线", ok(routing), routing.get("message"))
    wid = call("POST", "/work-orders", token, {
        "productId": product_id, "planQty": 1, "planStart": TODAY, "planEnd": TODAY
    }).get("data")
    call("POST", "/scheduling/%s/schedule" % wid, token, {"date": TODAY})
    released = call("POST", "/work-orders/%s/release" % wid, token, {})
    check("工单下达", ok(released), released.get("message"))
    return raw["id"]


def find_row(rows, product_id):
    return next((row for row in rows if row["productId"] == product_id), None)


def main():
    token = login("admin")
    if not token:
        print("登录失败，请确认服务已启动")
        sys.exit(1)
    products = call("GET", "/products", token).get("data") or []
    material_id = prepare_demand(token, products)
    if not material_id:
        print("准备缺料工单失败，终止")
        sys.exit(1)

    section("1. 物料需求计算")
    res = call("GET", "/mrp/run", token)
    check("计算接口可用", ok(res), res.get("message"))
    data = res.get("data") or {}
    rows = data.get("rows") or []
    summary = data.get("summary") or {}
    check("返回缺口行", len(rows) > 0, {"rows": len(rows)})
    check("汇总字段齐全", all(k in summary for k in
          ("materialCount", "shortageCount", "overdueCount", "totalGapQty")), summary)

    row = find_row(rows, material_id)
    check("刚造的缺料出现在清单里", row is not None, [r["productCode"] for r in rows][:5])
    if not row:
        print("未找到测试物料行，终止")
        sys.exit(1)

    section("2. 缺口口径自洽")
    required = ("productId", "productCode", "productName", "grossNeed", "available",
                "onWay", "pendingRequisition", "safetyStock", "gapQty",
                "needDate", "suggestOrderDate", "leadDays", "workOrderCount", "workOrders")
    check("行字段齐全", all(k in row for k in required), list(row.keys()))
    expect = (num(row["grossNeed"]) + num(row["safetyStock"]) - num(row["available"])
              - num(row["onWay"]) - num(row["pendingRequisition"]))
    check("缺口 = 毛需求 + 安全库存 − 可用 − 在途 − 已申请",
          abs(max(0.0, expect) - num(row["gapQty"])) < 0.001,
          {"expect": expect, "gap": row["gapQty"]})
    check("毛需求至少覆盖新工单的用量", num(row["grossNeed"]) >= 5000 - 0.001, row["grossNeed"])
    need = date.fromisoformat(row["needDate"])
    order = date.fromisoformat(row["suggestOrderDate"])
    check("建议下单日 = 需求日 − 交期", (need - order).days == int(row["leadDays"]),
          {"need": row["needDate"], "order": row["suggestOrderDate"], "lead": row["leadDays"]})
    check("能追溯到来源工单", int(row["workOrderCount"]) >= 1 and bool(row["workOrders"]),
          row.get("workOrders"))
    check("只看缺料时每行都有缺口", all(num(r["gapQty"]) > 0 for r in rows),
          [r["gapQty"] for r in rows[:5]])

    section("3. 全部需求（含已覆盖）")
    full = (call("GET", "/mrp/run?onlyShortage=false", token).get("data") or {})
    full_rows = full.get("rows") or []
    check("不筛缺料时行数不少于只看缺料", len(full_rows) >= len(rows),
          {"full": len(full_rows), "shortage": len(rows)})
    covered = [r for r in full_rows if num(r["gapQty"]) <= 0]
    check("包含被库存/在途覆盖的行", len(covered) > 0, len(covered))

    section("4. 含已排产")
    with_sched = (call("GET", "/mrp/run?includeScheduled=true", token).get("data") or {})
    check("含已排产时缺口不小于不含",
          num((with_sched.get("summary") or {}).get("totalGapQty"))
          >= num(summary.get("totalGapQty")) - 0.001,
          {"with": (with_sched.get("summary") or {}).get("totalGapQty"),
           "without": summary.get("totalGapQty")})

    section("5. 一键生成采购申请")
    qty = round(min(num(row["gapQty"]), 100.0), 2)
    before_gap = num(row["gapQty"])
    created = call("POST", "/mrp/requisitions", token, {
        "needDate": row["needDate"],
        "department": "采购部",
        "remark": "e2e MRP 回归",
        "items": [{
            "productId": row["productId"],
            "qty": qty,
            "suggestSupplierId": row.get("suggestSupplierId") or None,
            "targetPrice": row.get("suggestPrice") or 0,
            "remark": "需求日 " + row["needDate"],
        }],
    })
    check("生成采购申请成功", ok(created), created)
    req = created.get("data") or {}
    check("返回申请单号(PR)", str(req.get("reqNo") or "").startswith("PR"), req.get("reqNo"))
    check("申请数量正确", abs(num(req.get("totalQty")) - qty) < 0.001, req)

    after_draft = (call("GET", "/mrp/run", token).get("data") or {}).get("rows") or []
    draft_row = find_row(after_draft, material_id)
    check("草稿申请也算计划内供给，同一批料再跑不会重复申请",
          draft_row is None or abs(num(draft_row["gapQty"]) - (before_gap - qty)) < 0.001,
          {"before": before_gap, "after": draft_row and draft_row["gapQty"], "applied": qty})

    section("6. 草稿作废后缺口回来")
    cancelled = call("POST", "/purchase-requisitions/%s/cancel" % req.get("reqId"), token, {})
    check("草稿可以作废", ok(cancelled), cancelled)
    after_cancel = (call("GET", "/mrp/run", token).get("data") or {}).get("rows") or []
    cancel_row = find_row(after_cancel, material_id)
    check("作废后缺口恢复到作前",
          cancel_row is not None and abs(num(cancel_row["gapQty"]) - before_gap) < 0.001,
          {"before": before_gap, "after": cancel_row and cancel_row["gapQty"]})

    section("7. 参数校验")
    empty = call("POST", "/mrp/requisitions", token, {"items": []})
    check("空明细被拒(1002)", empty.get("code") == 1002, empty)
    zero = call("POST", "/mrp/requisitions", token, {
        "items": [{"productId": row["productId"], "qty": 0}]
    })
    check("数量为 0 被拒(1002)", zero.get("code") == 1002, zero)
    past = call("POST", "/mrp/requisitions", token, {
        "needDate": (date.today() - timedelta(days=10)).isoformat(),
        "items": [{"productId": row["productId"], "qty": 1}],
    })
    check("需求日过期自动按今天提", ok(past), past)

    section("8. 清理：取消本次生成的申请，避免影响下次计算")
    main_detail = call("GET", "/purchase-requisitions/%s" % req.get("reqId"), token)
    check("主申请已在第 6 步作废", (main_detail.get("data") or {}).get("status") == "CANCELLED", main_detail)
    past_id = (past.get("data") or {}).get("reqId")
    if past_id:
        cancel_past = call("POST", "/purchase-requisitions/%s/cancel" % past_id, token, {})
        check("取消过期需求申请", ok(cancel_past), cancel_past)
    final_rows = (call("GET", "/mrp/run", token).get("data") or {}).get("rows") or []
    final_row = find_row(final_rows, material_id)
    check("取消后缺口回到原值",
          final_row is not None and abs(num(final_row["gapQty"]) - before_gap) < 0.001,
          {"before": before_gap, "after": final_row and final_row["gapQty"]})

    print("\n" + "=" * 60)
    print("通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
    if FAILED:
        print("失败项：" + "; ".join(FAILED))
        sys.exit(1)


if __name__ == "__main__":
    main()
