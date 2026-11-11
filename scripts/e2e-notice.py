"""站内消息与推送联动端到端回归：
通知带单据号与分类、按分类筛、未读统计与已读、安灯进待办、采购申请提交/驳回通知、
推送配置默认未接入、推送设备登记与注销。

用法：python scripts/e2e-notice.py [http://localhost:8080]
"""

import json
import os
import sys
import urllib.error
import urllib.request
from datetime import date

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


def notices(token, category=None, unread=None):
    query = ["limit=100"]
    if category:
        query.append("category=" + category)
    if unread:
        query.append("unreadOnly=true")
    return call("GET", "/notices?" + "&".join(query), token).get("data") or []


def main():
    token = login("admin")
    if not token:
        print("登录失败，请确认服务已启动")
        sys.exit(1)

    section("1. 消息带单据号与分类")
    all_notices = notices(token)
    check("消息列表可查询", isinstance(all_notices, list), all_notices)
    if all_notices:
        first = all_notices[0]
        for field in ("category", "title", "refType", "refNo", "readAt", "createdAt"):
            check("消息字段包含 " + field, field in first, list(first.keys()))
    tasks = notices(token, category="TASK")
    check("按待办分类筛选只返回 TASK",
          all(n.get("category") == "TASK" for n in tasks), [n.get("category") for n in tasks[:3]])

    section("2. 推送通道默认未接入")
    config = call("GET", "/push/config", token).get("data") or {}
    check("推送配置有 provider", "provider" in config, config)
    check("默认通道是 NONE", config.get("provider") == "NONE", config.get("provider"))
    check("默认未启用推送", config.get("enabled") is False, config.get("enabled"))
    check("说明里提示可用浏览器桌面通知",
          "桌面通知" in str(config.get("hint") or ""), config.get("hint"))

    section("3. 推送设备登记与注销")
    device_token = "e2e-cid-" + str(int.from_bytes(os.urandom(3), "big"))
    registered = call("POST", "/push/devices", token, {
        "platform": "APP-ANDROID", "provider": "JPUSH", "token": device_token
    })
    check("登记推送设备", ok(registered), registered.get("message"))
    devices = call("GET", "/push/devices", token).get("data") or []
    mine = next((d for d in devices if d.get("provider") == "JPUSH"), None)
    check("设备列表能查到", mine is not None, devices)
    if mine:
        check("token 打码返回", "****" in str(mine.get("token")), mine.get("token"))
    bad = call("POST", "/push/devices", token, {
        "platform": "PC-WEB", "provider": "JPUSH", "token": "x"
    })
    check("非法平台被拒(1002)", bad.get("code") == 1002, bad.get("message"))
    removed = call("DELETE", "/push/devices?token=" + device_token, token)
    check("注销设备", ok(removed), removed.get("message"))
    left = [d for d in (call("GET", "/push/devices", token).get("data") or [])
            if str(d.get("provider")) == "JPUSH" and "****" not in device_token]
    check("注销后列表里没有该设备", all("****" not in str(d.get("token")) for d in left))

    section("4. 安灯呼叫进待办")
    page = call("GET", "/work-orders?status=IN_PROGRESS&page=1&size=1", token).get("data") or {}
    works = page.get("records") or []
    check("有在制工单可用于安灯", len(works) > 0)
    if works:
        work = works[0]
        # 先记下当前最新消息 id，模拟浏览器桌面通知的轮询基线
        baseline_rows = call("GET", "/notices/pending?since=0&limit=20", token).get("data") or []
        baseline = max([int(row.get("id") or 0) for row in baseline_rows], default=0)
        created = call("POST", "/andon/calls", token, {
            "workOrderId": work["id"], "operationName": "装配",
            "type": "QUALITY", "description": "e2e 消息联动测试"
        })
        check("发起安灯呼叫", ok(created), created.get("message"))
        call_no = (created.get("data") or {}).get("callNo")
        check("返回安灯号", bool(call_no) and str(call_no).startswith("AD"), call_no)

        task_notices = notices(token, category="TASK")
        hit = next((n for n in task_notices
                    if n.get("refType") == "ANDON" and n.get("refNo") == call_no), None)
        check("安灯呼叫推成待办消息", hit is not None, [n.get("title") for n in task_notices[:3]])
        check("待办消息带安灯号", hit is not None and call_no in str(hit.get("title")), hit)

        section("4.1 桌面通知轮询（在线弹系统通知用的接口）")
        pending_rows = call("GET", "/notices/pending?since=%s&limit=20" % baseline, token).get("data") or []
        pending_hit = next((n for n in pending_rows if n.get("refNo") == call_no), None)
        check("轮询能拿到基线之后的新消息", pending_hit is not None,
              [n.get("title") for n in pending_rows[:3]])
        check("新消息带可跳转的单据类型",
              pending_hit is not None and pending_hit.get("refType") == "ANDON", pending_hit)
        check("轮询只返回未读", all(not n.get("readAt") for n in pending_rows), pending_rows[:2])

        section("5. 按单据号定位（跳转落地用的接口）")
        located = call("GET", "/andon/calls?keyword=" + call_no, token).get("data") or []
        check("按安灯号能定位到这一条", len(located) == 1, len(located))
        empty = call("GET", "/andon/calls?keyword=NOT-EXIST-" + str(os.urandom(2).hex()), token).get("data") or []
        check("不存在的安灯号返回空", len(empty) == 0, len(empty))

    section("6. 采购申请提交与驳回都推消息")
    products = call("GET", "/products", token).get("data") or []
    material = next((p for p in products if p.get("code") == "P003"), None)
    check("有可申请的物料 P003", material is not None)
    if material:
        req_id = call("POST", "/purchase-requisitions", token, {
            "sourceType": "MANUAL", "department": "生产部", "needDate": TODAY,
            "purpose": "e2e 消息联动", "remark": "e2e",
            "items": [{"productId": material["id"], "qty": 1, "targetPrice": 10}]
        }).get("data")
        detail = call("GET", "/purchase-requisitions/%s" % req_id, token).get("data") or {}
        req_no = detail.get("reqNo")
        submitted = call("POST", "/purchase-requisitions/%s/submit" % req_id, token, {})
        check("提交采购申请", ok(submitted), submitted.get("message"))
        task_notices = notices(token, category="TASK")
        submit_hit = next((n for n in task_notices
                           if n.get("refType") == "REQUISITION" and n.get("refNo") == req_no), None)
        check("提交后审批人收到待办", submit_hit is not None, req_no)

        rejected = call("POST", "/purchase-requisitions/%s/reject" % req_id, token,
                        {"reason": "e2e 驳回原因"})
        check("驳回采购申请", ok(rejected), rejected.get("message"))
        alerts = notices(token, category="ALERT")
        reject_hit = next((n for n in alerts
                           if n.get("refType") == "REQUISITION" and n.get("refNo") == req_no), None)
        check("驳回后申请人收到预警", reject_hit is not None, [n.get("title") for n in alerts[:3]])
        check("驳回消息带原因", reject_hit is not None and "驳回原因" in str(reject_hit.get("content")),
              reject_hit)

        section("6.1 通知管理页（管理员）")
        admin_list = call("GET", "/system/notices?page=1&size=10", token).get("data") or {}
        check("管理页能查全量消息", int(admin_list.get("total") or 0) > 0, admin_list.get("total"))
        admin_rows = admin_list.get("records") or []
        if admin_rows:
            first_row = admin_rows[0]
            for field in ("receiver", "category", "title", "readAt", "createdAt"):
                check("管理页字段包含 " + field, field in first_row, list(first_row.keys()))
        task_rows = (call("GET", "/system/notices?category=TASK&size=20", token).get("data") or {}).get("records") or []
        check("管理页按分类筛只返回 TASK",
              all(r.get("category") == "TASK" for r in task_rows), len(task_rows))
        admin_unread = (call("GET", "/system/notices?readStatus=UNREAD&size=20", token).get("data") or {}).get("records") or []
        check("管理页只看未读", all(not r.get("readAt") for r in admin_unread), len(admin_unread))
        keyword_rows = (call("GET", "/system/notices?keyword=" + req_no + "&size=20", token).get("data") or {}).get("records") or []
        check("管理页按单据号能搜到", any(r.get("refNo") == req_no for r in keyword_rows), len(keyword_rows))
        admin_summary = call("GET", "/system/notices/summary", token).get("data") or {}
        check("管理页有统计", int(admin_summary.get("total") or 0) > 0 and "unread" in admin_summary, admin_summary)

        target_id = next((r.get("id") for r in keyword_rows if r.get("refNo") == req_no), None)
        if target_id:
            removed = call("DELETE", "/system/notices/%s" % target_id, token)
            check("删除单条消息", ok(removed) and int(removed.get("data") or 0) == 1, removed)
            after_delete = (call("GET", "/system/notices?keyword=" + req_no + "&size=50", token).get("data") or {}).get("records") or []
            check("删除后查不到该条", all(r.get("id") != target_id for r in after_delete), len(after_delete))

        before_total = int(admin_summary.get("total") or 0)
        purged = call("POST", "/system/notices/purge-read", token, {"days": 30})
        check("清理 30 天前已读返回 0", ok(purged) and int(purged.get("data") or 0) == 0, purged)
        after_purge = call("GET", "/system/notices?page=1&size=1", token).get("data") or {}
        check("清理不影响现有消息", int(after_purge.get("total") or 0) >= before_total - 1, after_purge.get("total"))

        warehouse_token = login("warehouse")
        forbidden = call("GET", "/system/notices?page=1&size=5", warehouse_token)
        check("仓库账号访问管理页被拒", forbidden.get("code") not in (0, None), forbidden.get("message"))

        section("7. 未读统计与已读")
        summary = call("GET", "/notices/summary", token).get("data") or {}
        check("未读数大于 0", int(summary.get("unread") or 0) > 0, summary)
        unread_rows = notices(token, unread=True)
        check("只看未读有返回", len(unread_rows) > 0, len(unread_rows))
        if unread_rows:
            call("PUT", "/notices/%s/read" % unread_rows[0]["id"], token)
            after = call("GET", "/notices/summary", token).get("data") or {}
            check("单条已读后未读数减一",
                  int(after.get("unread") or 0) == int(summary.get("unread") or 0) - 1,
                  (summary.get("unread"), after.get("unread")))
        call("PUT", "/notices/read-all", token)
        finally_summary = call("GET", "/notices/summary", token).get("data") or {}
        check("全部已读后未读为 0", int(finally_summary.get("unread") or 0) == 0, finally_summary)

    print("\n" + ("全部通过：%d 项" % PASSED if not FAILED else "失败 %d 项：%s" % (len(FAILED), FAILED)))
    if FAILED:
        sys.exit(1)


if __name__ == "__main__":
    main()
