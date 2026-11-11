"""撤单链路端到端回归：工单作废、订单取消、作废后重新转生产。

场景是「客户下单后又改主意」：订单已经转了生产，但车间还没开工。
要求还没动过料和人的工单能干净撤掉，已经领料/报工的拦住，订单要等工单收尾后才能取消。

账号分工按真实岗位来：销售建单/确认/取消，计划转生产/排产/下达/领料/报工/作废，质检只用来验权限。
三个账号都用初始口令，不依赖 admin（演示库里 admin 的口令可能已经被改过）。

用法：python scripts/e2e-cancel.py [http://localhost:8080]
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta
from urllib.parse import quote

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PASSED = 0
FAILED = []

TODAY = date.today()
DELIVERY = (TODAY + timedelta(days=7)).isoformat()
TAG = str(int(time.time()))[-6:]


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as err:
        text = err.read().decode()
        try:
            return err.code, json.loads(text)
        except json.JSONDecodeError:
            return err.code, {"code": -1, "message": text}


def login(username, password=None):
    # 强制改密之后口令可能已经变了，用 MES_PASSWORD 传入
    password = password or os.environ.get("MES_PASSWORD", "123456")
    _, res = call("POST", "/auth/login", body={"username": username, "password": password})
    return (res.get("data") or {}).get("token") if res.get("code") == 0 else None


def ok(payload):
    return isinstance(payload, dict) and payload.get("code") == 0


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


def new_order(token, remark, qty=2):
    _, created = call("POST", "/sales-orders", token, {
        "customerId": 1, "orderDate": TODAY.isoformat(), "deliveryDate": DELIVERY,
        "remark": remark + " " + TAG, "priority": "NORMAL",
        "items": [{"productId": 1, "qty": qty, "price": 100}]
    })
    if not ok(created):
        raise SystemExit("建销售订单失败：" + str(created))
    return created["data"]


def new_two_item_order(token, remark):
    """两行明细：明细级撤单要验「只砍一行、另一行照做」，也用来验部分数量取消"""
    _, created = call("POST", "/sales-orders", token, {
        "customerId": 1, "orderDate": TODAY.isoformat(), "deliveryDate": DELIVERY,
        "remark": remark + " " + TAG, "priority": "NORMAL",
        "items": [{"productId": 1, "qty": 4, "price": 100},
                  {"productId": 2, "qty": 2, "price": 50}]
    })
    if not ok(created):
        raise SystemExit("建两行销售订单失败：" + str(created))
    return created["data"]


def main():
    sale = login("sales")
    ops = login("planner")
    qc = login("qc")
    if not all([sale, ops, qc]):
        print("登录失败：请确认服务已启动，且 sales / planner / qc 三个演示账号可用初始口令登录")
        sys.exit(1)

    # ================= 1. 造一张已转生产的订单 =================
    section("1. 造单并转生产")
    order_id = new_order(sale, "撤单回归")
    _, confirmed = call("POST", "/sales-orders/%d/confirm" % order_id, sale, {})
    check("销售审核订单", ok(confirmed), confirmed.get("message"))
    _, produced = call("POST", "/sales-orders/%d/to-production" % order_id, ops, {})
    check("计划转生产", ok(produced), produced.get("message"))
    work_no = ((produced.get("data") or [{}])[0]).get("workNo")
    _, work = call("GET", "/work-orders/by-no/%s" % work_no, ops)
    work_id = (work.get("data") or {}).get("id")
    check("工单为草稿态", (work.get("data") or {}).get("status") == "DRAFT",
          (work.get("data") or {}).get("status"))

    # ================= 2. 有在制工单时不允许取消订单 =================
    section("2. 有在制工单时取消订单")
    status, blocked = call("POST", "/sales-orders/%d/cancel" % order_id, sale, {})
    check("在制工单挡住订单取消", not ok(blocked), blocked.get("message"))
    check("提示里点名了挡路的工单号", work_no in str(blocked.get("message")), blocked.get("message"))

    # ================= 3. 作废工单 =================
    section("3. 作废未开工的工单")
    status, forbidden = call(
        "POST", "/work-orders/%d/cancel?reason=%s" % (work_id, quote("不该成功")), qc, {})
    check("质检无权作废工单", status == 403, (status, forbidden.get("message")))

    _, cancelled = call(
        "POST", "/work-orders/%d/cancel?reason=%s" % (work_id, quote("客户取消订单")), ops, {})
    check("计划可作废工单", ok(cancelled), cancelled.get("message"))
    _, detail = call("GET", "/work-orders/%d" % work_id, ops)
    detail = detail.get("data") or {}
    check("工单状态变为已作废", detail.get("status") == "CANCELLED", detail.get("status"))
    check("作废原因已留痕", detail.get("cancelReason") == "客户取消订单", detail.get("cancelReason"))
    check("作废人已留痕", bool(detail.get("cancelledBy")), detail.get("cancelledBy"))
    op_status = [op.get("status") for op in detail.get("operations") or []]
    check("工序一起作废", bool(op_status) and all(s == "CANCELLED" for s in op_status), op_status)

    status, again = call("POST", "/work-orders/%d/cancel?reason=x" % work_id, ops, {})
    check("重复作废被拒", not ok(again), again.get("message"))

    # ================= 4. 订单此时可以取消 =================
    section("4. 工单收尾后取消订单")
    _, order_cancel = call(
        "POST", "/sales-orders/%d/cancel?reason=%s" % (order_id, quote("客户改需求")), sale, {})
    check("订单取消成功", ok(order_cancel), order_cancel.get("message"))
    _, order = call("GET", "/sales-orders/%d" % order_id, sale)
    order = order.get("data") or {}
    check("订单状态为已取消", order.get("status") == "CANCELLED", order.get("status"))
    check("订单取消原因已留痕", order.get("cancelReason") == "客户改需求", order.get("cancelReason"))
    status, twice = call("POST", "/sales-orders/%d/cancel" % order_id, sale, {})
    check("重复取消被拒", not ok(twice), twice.get("message"))

    # ================= 5. 作废后可以重新转生产 =================
    section("5. 作废后重新转生产")
    order2 = new_order(sale, "作废重转")
    call("POST", "/sales-orders/%d/confirm" % order2, sale, {})
    _, first = call("POST", "/sales-orders/%d/to-production" % order2, ops, {})
    check("再次转生产", ok(first), first.get("message"))
    wo2_no = ((first.get("data") or [{}])[0]).get("workNo")
    _, wo2 = call("GET", "/work-orders/by-no/%s" % wo2_no, ops)
    wo2_id = (wo2.get("data") or {}).get("id")
    _, cancelled2 = call("POST", "/work-orders/%d/cancel?reason=%s" % (wo2_id, quote("下错单")), ops, {})
    check("作废第二张工单", ok(cancelled2), cancelled2.get("message"))
    status, retry = call("POST", "/sales-orders/%d/to-production" % order2, ops, {})
    check("作废后允许重新转生产", ok(retry), retry.get("message"))
    retry_no = ((retry.get("data") or [{}])[0]).get("workNo")
    check("重新转产生成了新工单", bool(retry_no) and retry_no != wo2_no, retry_no)
    if not retry_no:
        # 没有新工单就没法继续验「已开工不许作废」，提前收尾并给出失败原因
        print("\n================ 汇总 ================")
        print("通过: %d 项" % PASSED)
        print("失败: %d 项" % len(FAILED))
        print("失败项：" + "、".join(FAILED))
        sys.exit(1)

    # ================= 6. 已领料 / 已开工的工单不许作废 =================
    section("6. 已领料、已开工的工单不许作废")
    _, work3 = call("GET", "/work-orders/by-no/%s" % retry_no, ops)
    work3 = work3.get("data") or {}
    work3_id = work3.get("id")
    call("POST", "/scheduling/%d/schedule" % work3_id, ops, {"date": TODAY.isoformat()})
    call("POST", "/work-orders/%d/release" % work3_id, ops, {})
    first_line = next((line for line in work3.get("bom") or []
                       if str(line.get("source") or "") != "SUBSTITUTE"
                       and float(line.get("needQty") or 0) > 0), None)
    if first_line:
        call("POST", "/work-orders/%d/issue-batch" % work3_id, ops, {
            "warehouseId": 1, "issuer": "撤单回归", "team": "装配一组", "source": "PC",
            "items": [{"productId": first_line["materialId"], "qty": 1}]
        })
    status, issued_blocked = call(
        "POST", "/work-orders/%d/cancel?reason=x" % work3_id, ops, {})
    check("已领料的工单作废被拒", not ok(issued_blocked), issued_blocked.get("message"))
    # 领料即开工：工单会从已下达进到生产中，所以这里命中的是「未开工才能作废」，
    # 退料那条校验（2016）是给「状态还没变但已领料」的历史数据兜底的
    _, after_issue = call("GET", "/work-orders/%d" % work3_id, ops)
    after_status = (after_issue.get("data") or {}).get("status")
    check("领料后工单进入生产中", after_status == "IN_PROGRESS", after_status)
    check("拒绝码是已开工(2014)或已领料(2016)",
          issued_blocked.get("code") in (2014, 2016), issued_blocked.get("code"))
    check("拒绝提示说清了原因",
          ("退料" in str(issued_blocked.get("message")))
          or ("已开工" in str(issued_blocked.get("message"))), issued_blocked.get("message"))

    first_op = (work3.get("operations") or [{}])[0]
    if first_op.get("id"):
        call("POST", "/work-orders/%d/report" % work3_id, ops, {
            "operationId": first_op["id"], "qty": 1, "defectQty": 0, "worker": "worker1"
        })
    status, started_blocked = call(
        "POST", "/work-orders/%d/cancel?reason=x" % work3_id, ops, {})
    check("已开工的工单作废被拒", not ok(started_blocked), started_blocked.get("message"))
    check("拒绝码是已开工(2014)或已报工(2015)", started_blocked.get("code") in (2014, 2015),
          started_blocked.get("code"))

    # ================= 7. 明细级撤单：只砍一行，其余照做 =================
    section("7. 明细级撤单")
    _, order2_detail = call("GET", "/sales-orders/%d" % order2, sale)
    order2_item = ((order2_detail.get("data") or {}).get("items") or [{}])[0]
    if order2_item.get("id"):
        _, preview = call(
            "GET", "/sales-orders/%d/items/%d/cancel-preview" % (order2, order2_item["id"]), sale)
        preview = preview.get("data") or {}
        check("已领料/已报工的明细行不能撤", preview.get("cancellable") is False, preview.get("cancellable"))
        check("预检给出了阻塞原因", bool(preview.get("blockers")), preview.get("blockers"))
        pending = preview.get("pendingReturns") or []
        check("预检带出待退料清单", bool(pending), pending)
        check("待退量大于 0", any(float(line.get("returnableQty") or 0) > 0 for line in pending), pending)

    two = new_two_item_order(sale, "两行单")
    call("POST", "/sales-orders/%d/confirm" % two, sale, {})
    _, two_produced = call("POST", "/sales-orders/%d/to-production" % two, ops, {})
    check("两行单转生产生成两张工单", ok(two_produced) and len(two_produced.get("data") or []) == 2,
          two_produced.get("data"))
    _, two_detail = call("GET", "/sales-orders/%d" % two, sale)
    two_items = (two_detail.get("data") or {}).get("items") or []
    check("订单有两行明细", len(two_items) == 2, len(two_items))

    if len(two_items) == 2:
        item_a, item_b = two_items[0], two_items[1]
        status, qc_preview = call(
            "GET", "/sales-orders/%d/items/%d/cancel-preview" % (two, item_a["id"]), qc)
        check("质检无权撤明细行", status == 403, (status, qc_preview.get("message")))

        # ---- 7.1 部分数量取消：4 件里砍 1 件 ----
        _, partial_preview = call(
            "GET", "/sales-orders/%d/items/%d/cancel-preview?qty=1" % (two, item_a["id"]), sale)
        partial_preview = partial_preview.get("data") or {}
        check("部分取消预检放行", partial_preview.get("cancellable") is True, partial_preview.get("blockers"))
        check("预检标为部分取消", partial_preview.get("mode") == "PARTIAL", partial_preview.get("mode"))
        check("预检算出保留 3 件", float(partial_preview.get("remainingQty") or 0) == 3.0,
              partial_preview.get("remainingQty"))
        partial_work = ((partial_preview.get("workOrders") or [{}])[0])
        check("预检里工单动作是缩量", partial_work.get("action") == "RESIZE", partial_work.get("action"))

        _, partial = call(
            "POST", "/sales-orders/%d/items/%d/cancel?qty=1&reason=%s"
            % (two, item_a["id"], quote("客户只砍 1 件")), sale, {})
        check("部分取消成功", ok(partial), partial.get("message"))
        partial_data = partial.get("data") or {}
        check("返回部分取消模式", partial_data.get("mode") == "PARTIAL", partial_data.get("mode"))
        check("保留数量为 3", float(partial_data.get("remainingQty") or 0) == 3.0,
              partial_data.get("remainingQty"))

        _, after_partial = call("GET", "/sales-orders/%d" % two, sale)
        after_partial = after_partial.get("data") or {}
        rows_partial = after_partial.get("items") or []
        active_a = next((row for row in rows_partial if row["id"] == item_a["id"]), {})
        check("原行减量到 3 且仍有效", float(active_a.get("qty") or 0) == 3.0
              and active_a.get("status") == "ACTIVE", active_a)
        trace = [row for row in rows_partial if row.get("status") == "CANCELLED"]
        check("砍掉的 1 件单起一条已取消行",
              len(trace) == 1 and float(trace[0].get("qty") or 0) == 1.0, trace)
        check("留痕带取消原因", (trace[0].get("cancelReason") == "客户只砍 1 件") if trace else False,
              trace[0].get("cancelReason") if trace else None)
        check("订单金额按剩余数量重算", float(after_partial.get("totalAmount") or 0) == 400.0,
              after_partial.get("totalAmount"))

        partial_work_id = partial_work.get("id")
        if partial_work_id:
            _, shrunk = call("GET", "/work-orders/%d" % partial_work_id, ops)
            shrunk = shrunk.get("data") or {}
            check("工单计划量跟着缩到 3", float(shrunk.get("planQty") or 0) == 3.0, shrunk.get("planQty"))
            op_qty = [float(op.get("planQty") or 0) for op in shrunk.get("operations") or []]
            check("工序计划量一起缩", bool(op_qty) and all(qty == 3.0 for qty in op_qty), op_qty)

        status, bad_qty = call(
            "POST", "/sales-orders/%d/items/%d/cancel?qty=99" % (two, item_a["id"]), sale, {})
        check("取消数量超过行数量被拒", not ok(bad_qty), bad_qty.get("message"))

        # ---- 7.2 剩下的整行取消 ----
        _, preview_a = call(
            "GET", "/sales-orders/%d/items/%d/cancel-preview" % (two, item_a["id"]), sale)
        preview_a = preview_a.get("data") or {}
        check("剩下的可以整行撤", preview_a.get("cancellable") is True, preview_a.get("blockers"))
        check("预检标为整行取消", preview_a.get("mode") == "WHOLE", preview_a.get("mode"))
        check("预检列出将连带作废的工单", len(preview_a.get("workOrders") or []) == 1,
              preview_a.get("workOrders"))

        _, cancelled_a = call(
            "POST", "/sales-orders/%d/items/%d/cancel?reason=%s"
            % (two, item_a["id"], quote("客户砍掉这一种")), sale, {})
        check("撤掉第一行", ok(cancelled_a), cancelled_a.get("message"))
        check("回报了连带作废的工单号", len((cancelled_a.get("data") or {}).get("cancelledWorkNos") or []) == 1,
              cancelled_a.get("data"))
        check("只撤一行时订单还在生产中", (cancelled_a.get("data") or {}).get("orderStatus") == "IN_PRODUCTION",
              (cancelled_a.get("data") or {}).get("orderStatus"))

        _, after = call("GET", "/sales-orders/%d" % two, sale)
        after = after.get("data") or {}
        after_items = {row["id"]: row for row in after.get("items") or []}
        check("第一行已取消", after_items[item_a["id"]].get("status") == "CANCELLED",
              after_items[item_a["id"]].get("status"))
        check("第二行不受影响", after_items[item_b["id"]].get("status") == "ACTIVE",
              after_items[item_b["id"]].get("status"))
        check("取消行的未发数量归零", float(after_items[item_a["id"]].get("remainingQty") or 0) == 0,
              after_items[item_a["id"]].get("remainingQty"))
        check("订单金额只算剩下的一行", float(after.get("totalAmount") or 0) == 100.0, after.get("totalAmount"))

        cancelled_work = ((preview_a.get("workOrders") or [{}])[0]).get("id")
        if cancelled_work:
            _, work_state = call("GET", "/work-orders/%d" % cancelled_work, ops)
            check("对应工单已作废", (work_state.get("data") or {}).get("status") == "CANCELLED",
                  (work_state.get("data") or {}).get("status"))

        status, twice_item = call(
            "POST", "/sales-orders/%d/items/%d/cancel" % (two, item_a["id"]), sale, {})
        check("同一行不能撤两次", not ok(twice_item), twice_item.get("message"))

        _, cancelled_b = call(
            "POST", "/sales-orders/%d/items/%d/cancel" % (two, item_b["id"]), sale, {})
        check("撤掉最后一行", ok(cancelled_b), cancelled_b.get("message"))
        check("全部撤完订单转已取消", (cancelled_b.get("data") or {}).get("orderStatus") == "CANCELLED",
              (cancelled_b.get("data") or {}).get("orderStatus"))

    # ================= 汇总 =================
    print("\n================ 汇总 ================")
    print("通过: %d 项" % PASSED)
    print("失败: %d 项" % len(FAILED))
    if FAILED:
        print("失败项：" + "、".join(FAILED))
        sys.exit(1)


if __name__ == "__main__":
    main()
