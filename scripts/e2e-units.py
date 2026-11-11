"""单件序列号（SN）端到端回归：下达生成件码、扫件码开工完工、自动累加工序完成数、
全件完工自动结束工单、单件工单禁止按数量报工、报废与异常拦截。

用法：python scripts/e2e-units.py [http://localhost:8080]
"""

import json
import os
import sys
import urllib.error
import urllib.request
from datetime import date

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
TODAY = date.today().isoformat()
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
        text = err.read().decode()
        try:
            return json.loads(text)
        except Exception:
            return {"code": err.code, "message": text}


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


def main():
    token = login("admin")
    if not token:
        print("登录失败，请确认服务已启动且演示账号存在")
        sys.exit(1)

    products = call("GET", "/products", token=token)["data"]
    by_code = {p["code"]: p for p in products}

    section("1. 建单件管理产品与工艺路线")
    code = "SN" + str(int.from_bytes(os.urandom(2), "big"))
    created = call("POST", "/products", token=token, body={
        "code": code, "name": "单件追溯测试件", "spec": "SN-T", "category": "FINISHED",
        "unit": "件", "price": 20, "cost": 10, "safetyStock": 0, "lossRate": 0.01,
        "serialManaged": True
    })
    check("创建单件管理产品", ok(created), created.get("message"))
    product_id = created.get("data")
    row = [p for p in call("GET", "/products", token=token)["data"] if p["id"] == product_id]
    check("产品回读带单件开关", bool(row) and row[0].get("serialManaged") is True,
          row[0] if row else None)
    copied = call("POST", f"/products/{product_id}/routing/copy", token=token,
                  body={"sourceProductId": by_code["P001"]["id"], "overwrite": True})
    check("复制三道工序路线", ok(copied), copied.get("message"))

    section("2. 工单下达时生成件码")
    wid = call("POST", "/work-orders", token=token, body={
        "productId": product_id, "planQty": 10, "planStart": TODAY, "planEnd": TODAY
    })["data"]
    call("POST", f"/scheduling/{wid}/schedule", token=token, body={"date": TODAY})
    released = call("POST", f"/work-orders/{wid}/release", token=token, body={})
    check("工单下达成功", ok(released), released.get("message"))

    units = call("GET", f"/work-order-units?workOrderId={wid}", token=token)["data"]
    unit_list = units.get("units") or []
    check("按计划数量生成 10 个件码", len(unit_list) == 10, len(unit_list))
    work_no = unit_list[0]["unitNo"].rsplit("-", 1)[0] if unit_list else ""
    check("件码规则为 工单号-三位流水",
          bool(unit_list) and unit_list[0]["unitNo"] == work_no + "-001"
          and unit_list[-1]["unitNo"] == work_no + "-010",
          [u["unitNo"] for u in unit_list[:2]] + [u["unitNo"] for u in unit_list[-1:]])
    check("初始全部待投产", units["summary"]["total"] == 10
          and units["summary"]["pending"] == 10
          and units["summary"]["finished"] == 0, units["summary"])
    check("位置分布为待投产一档",
          len(units["distribution"]) == 1 and units["distribution"][0]["key"] == "waiting"
          and units["distribution"][0]["count"] == 10, units["distribution"])

    section("2.5 追溯方式：产品给默认、工单做快照")
    product_row = [p for p in call("GET", "/products", token=token)["data"] if p["id"] == product_id]
    check("老接口只传单件开关也能折算成追溯方式",
          bool(product_row) and product_row[0].get("traceMode") == "SERIAL",
          product_row[0] if product_row else None)
    detail_now = call("GET", f"/work-orders/{wid}", token=token)["data"]
    check("工单带出追溯方式快照", detail_now.get("traceMode") == "SERIAL", detail_now.get("traceMode"))
    locked = call("POST", f"/work-orders/{wid}/trace-mode", token=token, body={"traceMode": "NONE"})
    check("已下达的工单不能改追溯方式", not ok(locked), locked.get("message"))

    # 把产品改成按数量：在制工单口径不许跟着变（这是改造前会卡死工单的坑）
    row = product_row[0]
    call("PUT", f"/products/{product_id}", token=token, body={
        "code": row["code"], "name": row["name"], "spec": row.get("spec"),
        "category": row["category"], "unit": row["unit"], "price": row["price"],
        "cost": row.get("cost"), "safetyStock": row.get("safetyStock"),
        "lossRate": row.get("lossRate"), "traceMode": "NONE"
    })
    after = call("GET", f"/work-orders/{wid}", token=token)["data"]
    check("改产品不影响在制工单的追溯口径", after.get("traceMode") == "SERIAL", after.get("traceMode"))
    new_order = call("POST", "/work-orders", token=token, body={
        "productId": product_id, "planQty": 3, "planStart": TODAY, "planEnd": TODAY
    })["data"]
    fresh = call("GET", f"/work-orders/{new_order}", token=token)["data"]
    check("新建工单继承产品默认（按数量）", fresh.get("traceMode") == "NONE", fresh.get("traceMode"))
    switched = call("POST", f"/work-orders/{new_order}/trace-mode", token=token,
                    body={"traceMode": "SERIAL"})
    check("下达前可以在工单上改成单件追溯", ok(switched), switched.get("message"))
    # 把产品默认改回单件，后面几节还要靠它生成件码
    call("PUT", f"/products/{product_id}", token=token, body={
        "code": row["code"], "name": row["name"], "spec": row.get("spec"),
        "category": row["category"], "unit": row["unit"], "price": row["price"],
        "cost": row.get("cost"), "safetyStock": row.get("safetyStock"),
        "lossRate": row.get("lossRate"), "traceMode": "SERIAL"
    })

    section("3. 扫件码识别")
    first = unit_list[0]["unitNo"]
    resolved = call("GET", f"/work-order-units/{first}", token=token)
    check("件码可解析", ok(resolved), resolved.get("message"))
    data = resolved.get("data") or {}
    check("解析带工单与产品", data.get("workNo") == work_no and data.get("productName"), data.get("workNo"))
    check("首次识别可开工、不可完工",
          data.get("canStart") is True and data.get("canFinish") is False, data)
    check("当前工序为第一道且带下一道",
          (data.get("currentOperation") or {}).get("seq") == 1
          and (data.get("nextOperation") or {}).get("seq") == 2,
          [data.get("currentOperation"), data.get("nextOperation")])
    missing = call("GET", "/work-order-units/" + work_no + "-999", token=token)
    check("不存在的件码报 2022", missing.get("code") == 2022, missing)

    section("4. 单件工单禁止按数量报工")
    blocked = call("POST", f"/work-orders/{wid}/report", token=token, body={
        "operationId": (data.get("currentOperation") or {}).get("id"),
        "qty": 1, "qualifiedQty": 1, "defectQty": 0, "worker": "测试员"
    })
    check("按数量报工被拦截 2023", blocked.get("code") == 2023, blocked)

    section("5. 件开工 / 完工流转")
    started = call("POST", f"/work-order-units/{first}/start", token=token, body={"worker": "张师傅"})
    check("件开工成功", ok(started) and started["data"]["status"] == "IN_PROGRESS", started.get("message"))
    check("开工后不可再开工、可完工",
          started["data"]["canStart"] is False and started["data"]["canFinish"] is True, started["data"])
    check("开工写入流转记录",
          (started["data"]["logs"] or [{}])[0].get("action") == "START"
          and (started["data"]["logs"] or [{}])[0].get("worker") == "张师傅", started["data"]["logs"])

    fin1 = call("POST", f"/work-order-units/{first}/finish", token=token,
                body={"worker": "张师傅", "qualified": True})
    check("第一道完工后流转到第二道",
          ok(fin1) and fin1["data"]["status"] == "PENDING"
          and (fin1["data"]["currentOperation"] or {}).get("seq") == 2, fin1.get("data") or fin1)
    detail = call("GET", f"/work-orders/{wid}", token=token)["data"]
    op1 = [o for o in detail["operations"] if o["seq"] == 1][0]
    check("工序完成数自动 +1", float(op1["doneQty"]) == 1, op1)

    walk_failed = None
    for unit_no in [first] + [u["unitNo"] for u in unit_list[1:]]:
        state = call("GET", f"/work-order-units/{unit_no}", token=token)["data"]
        guard = 0
        while state.get("status") not in ("FINISHED", "SCRAPPED") and guard < 8:
            guard += 1
            r = call("POST", f"/work-order-units/{unit_no}/start", token=token, body={"worker": "李师傅"})
            if not ok(r):
                walk_failed = ("start", unit_no, r)
                break
            r = call("POST", f"/work-order-units/{unit_no}/finish", token=token,
                     body={"worker": "李师傅", "qualified": True})
            if not ok(r):
                walk_failed = ("finish", unit_no, r)
                break
            state = r["data"]
        if walk_failed:
            break
    check("10 件全部走完所有工序", walk_failed is None, walk_failed)

    units = call("GET", f"/work-order-units?workOrderId={wid}", token=token)["data"]
    check("汇总：10 件全部完工", units["summary"]["finished"] == 10, units["summary"])
    check("位置分布归到已完工一档",
          len(units["distribution"]) == 1 and units["distribution"][0]["key"] == "done"
          and units["distribution"][0]["count"] == 10, units["distribution"])
    detail = call("GET", f"/work-orders/{wid}", token=token)["data"]
    check("全件完工后工单自动完成", detail["status"] == "FINISHED", detail["status"])
    check("工单完成数回写为 10", float(detail["doneQty"]) == 10, detail.get("doneQty"))
    check("末道工序完成数等于计划数",
          all(float(o["doneQty"]) == float(o["planQty"]) for o in detail["operations"]),
          [(o["seq"], o["doneQty"], o["planQty"]) for o in detail["operations"]])

    section("6. 报废与重复操作拦截")
    wid2 = call("POST", "/work-orders", token=token, body={
        "productId": product_id, "planQty": 2, "planStart": TODAY, "planEnd": TODAY
    })["data"]
    call("POST", f"/scheduling/{wid2}/schedule", token=token, body={"date": TODAY})
    call("POST", f"/work-orders/{wid2}/release", token=token, body={})
    units2 = call("GET", f"/work-order-units?workOrderId={wid2}", token=token)["data"]["units"]
    check("第二张工单生成 2 个件码", len(units2) == 2, len(units2))
    target = units2[0]["unitNo"]
    need_reason = call("POST", f"/work-order-units/{target}/scrap", token=token, body={"worker": "王师傅"})
    check("报废必须填原因", need_reason.get("code") != 0, need_reason)
    scrapped = call("POST", f"/work-order-units/{target}/scrap", token=token,
                    body={"worker": "王师傅", "reason": "孔位偏移无法返修"})
    check("报废成功", ok(scrapped) and scrapped["data"]["status"] == "SCRAPPED", scrapped.get("message"))
    again = call("POST", f"/work-order-units/{target}/start", token=token, body={"worker": "王师傅"})
    check("已报废件不能开工", again.get("code") == 2021, again)
    remain = call("GET", f"/work-order-units?workOrderId={wid2}", token=token)["data"]
    op_after = [o for o in call("GET", f"/work-orders/{wid2}", token=token)["data"]["operations"]
                if o["seq"] == 1][0]
    check("报废不影响工序完成数", float(op_after["doneQty"]) == 0, op_after)
    check("汇总记录报废件", remain["summary"]["scrapped"] == 1 and remain["summary"]["total"] == 2,
          remain["summary"])

    section("7. 非单件产品不生成件码")
    wid3 = call("POST", "/work-orders", token=token, body={
        "productId": by_code["P001"]["id"], "planQty": 3, "planStart": TODAY, "planEnd": TODAY
    })["data"]
    gen = call("POST", f"/work-orders/{wid3}/units/generate", token=token, body={})
    check("非单件产品生成 0 个件码", ok(gen) and int(gen["data"]) == 0, gen)
    empty = call("GET", f"/work-order-units?workOrderId={wid3}", token=token)["data"]
    check("非单件工单件列表为空", empty["summary"]["total"] == 0, empty["summary"])

    section("8. 跨工单件检索（单件追踪页）")
    search = call("GET", f"/work-order-units?keyword={work_no}&page=1&size=20", token=token)
    body = search.get("data") or {}
    check("按工单号可检索到件", ok(search) and body.get("total") == 10, body.get("total"))
    check("检索带状态计数", (body.get("summary") or {}).get("finished") == 10, body.get("summary"))
    first_rec = (body.get("records") or [{}])[0]
    check("检索行带工单与产品", first_rec.get("workNo") == work_no
          and bool(first_rec.get("productName")), first_rec)
    only_finished = call("GET", f"/work-order-units?keyword={work_no}&status=FINISHED", token=token)
    check("按状态筛选生效", (only_finished.get("data") or {}).get("total") == 10,
          (only_finished.get("data") or {}).get("total"))
    only_pending = call("GET", f"/work-order-units?keyword={work_no}&status=PENDING", token=token)
    check("筛在制外的状态返回空", (only_pending.get("data") or {}).get("total") == 0,
          (only_pending.get("data") or {}).get("total"))
    ops = call("GET", "/work-order-units/operations", token=token)
    check("工序下拉按序号去重", ok(ops) and [o["seq"] for o in (ops.get("data") or [])] == sorted(
        {o["seq"] for o in (ops.get("data") or [])}), ops.get("data"))
    bad_work = call("GET", "/work-orders/by-no/NOT-EXIST-WO", token=token)
    check("未知工单查询报错", bad_work.get("code") != 0, bad_work)

    print("\n" + "=" * 60)
    print("通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
    if FAILED:
        print("失败项：" + "; ".join(FAILED))
        sys.exit(1)


if __name__ == "__main__":
    main()
