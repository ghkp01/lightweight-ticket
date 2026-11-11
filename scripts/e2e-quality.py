# -*- coding: utf-8 -*-
"""质检形态 / SOP-SIP / 不良处置 / 退货返修 / 站内通知 端到端验证

用法：python scripts/e2e-quality.py
默认打 http://localhost:8080/api，可用环境变量 MES_BASE 覆盖。
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("MES_BASE", "http://localhost:8080/api")
PASSED = 0
FAILED = []


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


def login(username, password=None):
    # 强制改密之后 admin 的口令可能已经变了，用 MES_PASSWORD 传入
    password = password or os.environ.get("MES_PASSWORD", "123456")
    r = call("POST", "/auth/login", body={"username": username, "password": password})
    return r["data"]["token"] if r.get("code") == 0 else None


def check(name, cond, extra=""):
    global PASSED
    if cond:
        PASSED += 1
        print("  [OK] " + name)
    else:
        FAILED.append(name)
        print("  [FAIL] " + name + " " + str(extra))


def main():
    admin = login("admin")
    qc = login("qc")
    worker = login("worker1")
    if not admin or not qc or not worker:
        print("登录失败，请确认服务已启动且演示账号存在")
        sys.exit(1)

    print("== 1. 产品 SOP / SIP ==")
    docs = call("GET", "/products/1/documents", admin)["data"]
    check("产品有 SOP 与 SIP", {d["docType"] for d in docs} >= {"SOP", "SIP"}, docs)
    sop = [d for d in docs if d["docType"] == "SOP"][0]
    check("SOP 绑定到具体工序", sop.get("processName") == "下料", sop.get("processName"))
    check("SOP 有正文内容", bool(sop.get("content")))

    print("== 2. 工序检验方式三态 ==")
    procs = {p["code"]: p for p in call("GET", "/processes", admin)["data"]}
    cut = procs["CUT"]
    base = {"code": cut["code"], "name": cut["name"], "category": cut["category"],
            "workCenterId": cut.get("workCenterId"), "defaultMinutes": cut["defaultMinutes"],
            "defaultPrice": cut["defaultPrice"], "remark": cut.get("remark")}
    r = call("PUT", "/processes/%d" % cut["id"], admin, dict(base, inspectionMode="NONE"))
    check("工序可设为不检验", r.get("code") == 0, r)
    r = call("PUT", "/processes/%d" % cut["id"], admin, dict(base, inspectionMode="ATTACHED"))
    check("工序可设为工序内检验点", r.get("code") == 0, r)
    cur = [p for p in call("GET", "/processes?keyword=CUT", admin)["data"] if p["code"] == "CUT"][0]
    check("检验方式已落库", cur["inspectionMode"] == "ATTACHED", cur["inspectionMode"])
    check("终端检验工序仍是独立工序", procs["QC"]["inspectionMode"] == "ROUTING",
          procs["QC"]["inspectionMode"])

    print("== 3. 新工单带下检验方式与指导书 ==")
    p1 = [p for p in call("GET", "/products", admin)["data"] if p["code"] == "P001"][0]
    r = call("POST", "/work-orders", admin, {"productId": p1["id"], "planQty": 6,
                                             "planStart": "2026-10-22", "planEnd": "2026-10-24",
                                             "priority": "NORMAL", "remark": "e2e 质检"})
    check("建工单成功", r.get("code") == 0, r)
    wo = r["data"]["id"] if isinstance(r["data"], dict) else r["data"]
    r = call("POST", "/scheduling/%d/schedule" % wo, admin, {"date": "2026-10-22"})
    check("工单排产", r.get("code") == 0, r)
    r = call("POST", "/work-orders/%d/release" % wo, admin)
    check("工单下达", r.get("code") == 0, r)

    detail = call("GET", "/work-orders/%d" % wo, admin)["data"]
    ops = detail["operations"]
    check("工单工序带检验方式", all(o.get("inspectionMode") for o in ops),
          [(o["seq"], o.get("inspectionMode")) for o in ops])
    attached = [o for o in ops if o.get("inspectionMode") == "ATTACHED"]
    check("下料是工序内检验点", len(attached) == 1, [(o["seq"], o.get("inspectionMode")) for o in ops])

    data = call("GET", "/work-orders/%d/documents" % wo, admin)["data"]
    check("工单文档按工序下发", len(data["operations"]) == len(ops))
    cutDocs = [o["documents"] for o in data["operations"] if o["seq"] == 1][0]
    check("下料工序挂 SOP", len(cutDocs) >= 1 and cutDocs[0]["docType"] == "SOP", cutDocs)

    print("== 4. 工序内检验拦截 ==")
    op1 = attached[0]
    r = call("POST", "/work-orders/%d/report" % wo, worker,
             {"operationId": op1["id"], "qty": 2, "qualifiedQty": 2, "defectQty": 0,
              "worker": "worker1"})
    check("不填检验结论被拦截(2020)", r.get("code") == 2020, r)
    r = call("POST", "/work-orders/%d/report" % wo, worker,
             {"operationId": op1["id"], "qty": 2, "qualifiedQty": 2, "defectQty": 0,
              "worker": "worker1", "inspectionResult": "PASS"})
    check("判合格且无不良可报工", r.get("code") == 0, r)
    r = call("POST", "/work-orders/%d/report" % wo, worker,
             {"operationId": op1["id"], "qty": 2, "qualifiedQty": 2, "defectQty": 0,
              "worker": "worker1", "inspectionResult": "FAIL"})
    check("判不合格却没填不良被拒(2010)", r.get("code") == 2010, r)

    print("== 5. 报工不良进台账并通知质检 ==")
    r = call("POST", "/work-orders/%d/report" % wo, worker,
             {"operationId": op1["id"], "qty": 3, "qualifiedQty": 2, "defectQty": 1,
              "worker": "worker1", "inspectionResult": "FAIL", "remark": "切口毛刺"})
    check("不合格报工成功", r.get("code") == 0, r)
    pending = call("GET", "/defects?status=PENDING", admin)["data"]
    check("台账有 PENDING 不良", len(pending) >= 1, pending)
    defect = [d for d in pending if d["workOrderId"] == wo][0]
    check("不良关联工单与工序", defect.get("workNo") == detail["workNo"]
          and defect.get("operationName") == "下料", defect)
    notices = call("GET", "/notices?unreadOnly=true", qc)["data"]
    check("质检收到待处置提醒", any(n.get("refType") == "DEFECT" for n in notices), notices[:1])

    print("== 6. 处置：让步接收 ==")
    r = call("POST", "/defects/%d/dispose" % defect["id"], qc,
             {"disposition": "CONCESSION", "remark": "客户同意让步"})
    check("让步接收成功", r.get("code") == 0, r)
    disposed = call("GET", "/defects?status=DISPOSED", qc)["data"]
    check("台账状态变为已处置",
          any(d["id"] == defect["id"] and d["disposition"] == "CONCESSION" for d in disposed))
    r = call("POST", "/defects/%d/dispose" % defect["id"], qc, {"disposition": "SCRAP"})
    check("重复处置被拒(2019)", r.get("code") == 2019, r)

    print("== 7. 处置：返修开单 ==")
    r = call("POST", "/work-orders/%d/report" % wo, worker,
             {"operationId": op1["id"], "qty": 1, "qualifiedQty": 0, "defectQty": 1,
              "worker": "worker1", "inspectionResult": "FAIL", "remark": "尺寸超差"})
    check("再来一件全不良", r.get("code") == 0, r)
    pending = call("GET", "/defects?status=PENDING", admin)["data"]
    defect2 = [d for d in pending if d["workOrderId"] == wo][0]
    r = call("POST", "/defects/%d/dispose" % defect2["id"], qc,
             {"disposition": "REWORK", "remark": "返修"})
    check("返修处置成功", r.get("code") == 0, r)
    reworkNo = r["data"].get("reworkWorkNo")
    check("生成返修工单", bool(reworkNo), r["data"])
    recs = call("GET", "/work-orders?keyword=%s" % (reworkNo or ""), admin)["data"]["records"]
    check("返修工单类型为 REWORK", len(recs) == 1 and recs[0]["workType"] == "REWORK", recs[:1])
    rw = call("GET", "/work-orders/%d" % recs[0]["id"], admin)["data"]
    check("返修工单有工序", len(rw["operations"]) > 0, len(rw["operations"]))
    check("返修工单有 BOM", len(rw.get("bom") or []) > 0, len(rw.get("bom") or []))

    print("== 8. 退货返修 ==")
    cand = None
    for st in ("SHIPPED", "COMPLETED"):
        for o in call("GET", "/sales-orders?status=%s&page=1&size=10" % st, admin)["data"]["records"]:
            d = call("GET", "/sales-orders/%d" % o["id"], admin)["data"]
            items = [i for i in d["items"] if float(i.get("shippedQty") or 0) >= 1]
            if items:
                cand = (o, items[0])
                break
        if cand:
            break
    check("存在可退货的已发货订单", cand is not None)
    if cand:
        so, item = cand
        r = call("POST", "/returns", admin, {"salesOrderId": so["id"], "reason": "e2e 退货返修",
                                              "disposition": "REWORK",
                                              "items": [{"productId": item["productId"], "qty": 1}]})
        check("退货单创建（处理方式=返修）", r.get("code") == 0, r)
        rid = r["data"]["id"] if isinstance(r["data"], dict) else r["data"]
        r = call("POST", "/returns/%d/process" % rid, admin)
        check("退货处理成功", r.get("code") == 0, r)
        rd = call("GET", "/returns/%d" % rid, admin)["data"]
        check("退货单已处理", rd["status"] == "PROCESSED", rd["status"])
        check("退货单回链返修工单号", bool(rd.get("reworkWorkNo")), rd.get("reworkWorkNo"))
        disposed = call("GET", "/defects?status=DISPOSED", admin)["data"]
        check("退货返修进不良台账",
              any(d["source"] == "RETURN" and d["disposition"] == "REWORK" for d in disposed))

    print("== 9. 派工只能选系统用户 ==")
    users = call("GET", "/system/users/options", admin)["data"]
    check("可选用户带组织与岗位", len(users) >= 1 and all("realName" in u for u in users), users[:2])
    workerUser = [u for u in users if u["username"] == "worker1"][0]
    r = call("POST", "/work-orders/%d/operations/%d/assign" % (wo, op1["id"]), admin,
             {"userId": workerUser["id"]})
    check("按用户派工成功", r.get("code") == 0, r)
    detail2 = call("GET", "/work-orders/%d" % wo, admin)["data"]
    assignedOp = [o for o in detail2["operations"] if o["id"] == op1["id"]][0]
    check("派工写入责任人", assignedOp.get("assignedUserId") == workerUser["id"], assignedOp)
    check("派工显示名为用户姓名", assignedOp.get("assignedWorker") == workerUser["realName"],
          assignedOp.get("assignedWorker"))
    r = call("POST", "/work-orders/%d/operations/%d/assign" % (wo, op1["id"]), admin,
             {"worker": "查无此人"})
    check("自由文本派工被拒(1002)", r.get("code") == 1002, r)
    r = call("POST", "/work-orders/%d/operations/%d/assign" % (wo, op1["id"]), admin,
             {"userId": 99999999})
    check("不存在的用户被拒(1002)", r.get("code") == 1002, r)

    print("== 10. 返修从中间工序起做 ==")
    op2 = [o for o in detail2["operations"] if o["seq"] == 2][0]
    r = call("POST", "/work-orders/%d/report" % wo, worker,
             {"operationId": op2["id"], "qty": 2, "qualifiedQty": 1, "defectQty": 1,
              "worker": "worker1", "remark": "装配划伤"})
    check("中间工序报出不良", r.get("code") == 0, r)
    pending = call("GET", "/defects?status=PENDING", admin)["data"]
    midDefect = [d for d in pending if d["workOrderId"] == wo and d.get("operationSeq") == 2]
    check("不良记录带工序序号", len(midDefect) == 1, [(d["workOrderId"], d.get("operationSeq")) for d in pending])
    r = call("POST", "/defects/%d/dispose" % midDefect[0]["id"], qc,
             {"disposition": "REWORK", "remark": "从装配返修"})
    check("返修处置成功", r.get("code") == 0, r)
    check("处置返回返修起点工序", r["data"].get("reworkFromSeq") == 2, r["data"])
    midReworkNo = r["data"].get("reworkWorkNo")
    recs = call("GET", "/work-orders?keyword=%s" % midReworkNo, admin)["data"]["records"]
    check("生成返修工单", len(recs) == 1, recs[:1])
    rw = call("GET", "/work-orders/%d" % recs[0]["id"], admin)["data"]
    seqs = sorted(int(o["seq"]) for o in rw["operations"])
    check("返修工单只保留后道工序", seqs == [2, 3], seqs)
    check("工单记录返修起点", rw.get("reworkFromSeq") == 2, rw.get("reworkFromSeq"))
    bomSeqs = {int(b["operationSeq"]) for b in (rw.get("bom") or []) if b.get("operationSeq")}
    check("返修 BOM 只带后道物料", all(s >= 2 for s in bomSeqs), bomSeqs)

    print("== 11. 站内通知 ==")
    summary = call("GET", "/notices/summary", admin)["data"]
    check("管理员有未读通知", summary["unread"] >= 1, summary)
    r = call("PUT", "/notices/read-all", admin)
    check("一键已读", r.get("code") == 0, r)
    summary = call("GET", "/notices/summary", admin)["data"]
    check("已读后未读清零", summary["unread"] == 0, summary)

    print("== 12. 良品率口径 ==")
    s = call("GET", "/inspections/summary", admin)["data"]
    check("提供一次合格率", "firstPassRate" in s, list(s.keys()))
    check("提供检验合格率", "rate" in s)
    check("提供待处置笔数", "pending" in s)
    d = call("GET", "/dashboard/summary", admin)["data"]
    check("工作台合格率有报工口径支撑", "reportQty" in d and "pendingDefects" in d, list(d.keys())[:8])

    print("== 13. 扫码检验（工序码）==")
    scan_body = {"type": "PROCESS", "refType": "WORK_ORDER", "refNo": detail["workNo"],
                 "workOrderId": wo, "productId": detail["productId"],
                 "inspectQty": 2, "defectQty": 0, "inspector": "qc",
                 "operationSeq": 2, "remark": "扫码工序检验"}
    r = call("POST", "/inspections", qc, scan_body)
    check("扫工序码提交过程检成功", r.get("code") == 0, r)
    check("检验单回带工序序号", r.get("data", {}).get("operationSeq") == 2, r.get("data"))
    scan_no = (r.get("data") or {}).get("inspectionNo")
    recs = call("GET", "/inspections?keyword=%s" % scan_no, admin)["data"]["records"]
    check("检验列表带工序序号", len(recs) == 1 and recs[0].get("operationSeq") == 2, recs[:1])
    check("检验列表带工序名称", bool(recs[0].get("operationName")), recs[:1])

    r = call("POST", "/inspections", qc, dict(scan_body, defectQty=1, remark="扫码发现装配划伤"))
    check("工序检验判不合格成功", r.get("code") == 0, r)
    pending = call("GET", "/defects?status=PENDING", admin)["data"]
    scan_defect = [d for d in pending
                   if d.get("source") == "PROCESS_QC" and d.get("workOrderId") == wo]
    check("工序检不合格进不良台账", len(scan_defect) == 1,
          [(d.get("source"), d.get("workOrderId")) for d in pending])
    if scan_defect:
        check("不良挂到扫码的那道工序", scan_defect[0].get("operationSeq") == 2
              and bool(scan_defect[0].get("operationName")), scan_defect[:1])
        call("POST", "/defects/%d/dispose" % scan_defect[0]["id"], qc,
             {"disposition": "SCRAP", "remark": "扫码检验回归收尾"})

    r = call("POST", "/inspections", qc, dict(scan_body, operationSeq=9))
    check("工序不属于该工单被拒(1002)", r.get("code") == 1002, r)

    # 收尾：演示库的工序检验方式回到默认（仅终检为独立检验工序），避免影响其它回归脚本
    call("PUT", "/processes/%d" % cut["id"], admin, dict(base, inspectionMode="NONE"))
    restored = [p for p in call("GET", "/processes?keyword=CUT", admin)["data"] if p["code"] == "CUT"][0]
    check("收尾还原为不检验", restored["inspectionMode"] == "NONE", restored["inspectionMode"])

    print("== 10. 完工检不传产品也能建单 ==")
    finished = call("GET", "/work-orders?status=FINISHED&page=1&size=1", admin)["data"]["records"]
    if finished:
        target = finished[0]
        check("工单列表带产品 ID", target.get("productId") is not None, target.get("workNo"))
        r = call("POST", "/inspections", qc, {
            "type": "FINAL", "refType": "WORK_ORDER", "refNo": target["workNo"],
            "workOrderId": target["id"], "inspectQty": 1, "defectQty": 0, "inspector": "周敏"
        })
        check("完工检缺产品时由工单补齐", r.get("code") == 0, r)
        if r.get("code") == 0:
            detail = call("GET", "/inspections/%s" % r["data"]["id"], admin)["data"]
            check("补齐的产品与工单一致", detail.get("productId") == target.get("productId"),
                  (detail.get("productId"), target.get("productId")))
    else:
        check("存在已完工工单用于完工检", False, "没有 FINISHED 工单")

    print("\n结果：%d 项通过，%d 项失败" % (PASSED, len(FAILED)))
    if FAILED:
        print("失败项：" + "、".join(FAILED))
        sys.exit(1)


if __name__ == "__main__":
    main()
