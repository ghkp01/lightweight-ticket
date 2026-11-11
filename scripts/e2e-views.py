# -*- coding: utf-8 -*-
"""
表格视图专项回归：
1. 保存视图、列出视图、内容原样返回
2. 同页重名拒绝、非法页面标识与过短名称拒绝
3. 默认视图互斥
4. 更新视图名称与筛选内容
5. 跨用户隔离：看不到、删不掉别人的视图
6. 删除视图、每页视图数量上限
用法：python scripts/e2e-views.py [base_url]
"""
import json
import sys
import urllib.error
import urllib.request
import os

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PAGE = "test.e2e-views"
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


def view_payload(status="", warning=False):
    return {
        "order": ["product", "workNo"],
        "columns": [
            {"key": "workNo", "visible": True, "width": 180, "fixed": "left"},
            {"key": "product", "visible": not warning, "width": 200, "fixed": ""},
        ],
        "filters": {"status": status, "onlyWarning": warning},
        "pageSize": 50,
    }


def main():
    admin = login("admin")
    planner = login("planner")
    if not admin or not planner:
        print("登录失败，请确认服务已启动且演示账号存在")
        sys.exit(1)

    # 先清理上次遗留
    old = call("GET", "/user-views?pageKey=" + PAGE, admin).get("data") or []
    for item in old:
        call("DELETE", "/user-views/%s" % item["id"], admin)

    print("== 1. 保存与列出 ==")
    created = call("POST", "/user-views", admin, {
        "pageKey": PAGE, "name": "测试视图一", "view": view_payload("IN_PROGRESS"), "isDefault": False
    })
    first_id = created.get("data")
    check("保存视图", ok(created) and first_id, created)

    listed = call("GET", "/user-views?pageKey=" + PAGE, admin).get("data") or []
    check("列表返回一条", len(listed) == 1, listed)
    row = listed[0] if listed else {}
    check("名称与页面标识正确", row.get("name") == "测试视图一" and row.get("pageKey") == PAGE, row)
    payload = row.get("view") or {}
    check("筛选条件原样保存", (payload.get("filters") or {}).get("status") == "IN_PROGRESS", payload.get("filters"))
    check("分页大小原样保存", payload.get("pageSize") == 50, payload.get("pageSize"))
    columns = payload.get("columns") or []
    check("列设置原样保存", len(columns) == 2 and columns[0].get("fixed") == "left", columns)
    check("列顺序原样保存", payload.get("order") == ["product", "workNo"], payload.get("order"))

    print("== 2. 校验 ==")
    duplicate = call("POST", "/user-views", admin, {
        "pageKey": PAGE, "name": "测试视图一", "view": view_payload(), "isDefault": False
    })
    check("重名被拒(2015)", duplicate.get("code") == 2015, duplicate)

    bad_key = call("POST", "/user-views", admin, {
        "pageKey": "Bad Key!", "name": "非法页面", "view": view_payload(), "isDefault": False
    })
    check("非法页面标识被拒(1002)", bad_key.get("code") == 1002, bad_key)

    short_name = call("POST", "/user-views", admin, {
        "pageKey": PAGE, "name": "A", "view": view_payload(), "isDefault": False
    })
    check("名称过短被拒(1002)", short_name.get("code") == 1002, short_name)

    empty_view = call("POST", "/user-views", admin, {
        "pageKey": PAGE, "name": "空视图", "view": {}, "isDefault": False
    })
    check("空视图内容被拒(1002)", empty_view.get("code") == 1002, empty_view)

    print("== 3. 默认视图互斥 ==")
    second = call("POST", "/user-views", admin, {
        "pageKey": PAGE, "name": "测试视图二", "view": view_payload("", True), "isDefault": True
    })
    second_id = second.get("data")
    check("新建时直接设为默认", ok(second) and second_id, second)
    listed = call("GET", "/user-views?pageKey=" + PAGE, admin).get("data") or []
    check("默认视图排在第一位", listed and listed[0]["id"] == second_id, [r["id"] for r in listed])
    check("同页面只有一个默认", sum(1 for r in listed if r.get("isDefault")) == 1, listed)

    call("POST", "/user-views/%s/default" % first_id, admin)
    listed = call("GET", "/user-views?pageKey=" + PAGE, admin).get("data") or []
    default_row = next((r for r in listed if r.get("isDefault")), None)
    check("切换默认后指向新视图", default_row and default_row["id"] == first_id, default_row)

    print("== 4. 更新视图 ==")
    updated = call("PUT", "/user-views/%s" % first_id, admin, {
        "pageKey": PAGE, "name": "测试视图一改", "view": view_payload("SCHEDULED"), "isDefault": True
    })
    check("更新视图", ok(updated), updated)
    listed = call("GET", "/user-views?pageKey=" + PAGE, admin).get("data") or []
    row = next((r for r in listed if r["id"] == first_id), {})
    check("更新后名称生效", row.get("name") == "测试视图一改", row.get("name"))
    check("更新后筛选生效",
          ((row.get("view") or {}).get("filters") or {}).get("status") == "SCHEDULED",
          (row.get("view") or {}).get("filters"))

    print("== 5. 跨用户隔离 ==")
    planner_list = call("GET", "/user-views?pageKey=" + PAGE, planner).get("data") or []
    check("计划账号看不到管理员视图", len(planner_list) == 0, planner_list)
    denied = call("DELETE", "/user-views/%s" % first_id, planner)
    check("不能删除别人的视图(2012)", denied.get("code") == 2012, denied)

    print("== 6. 数量上限与删除 ==")
    for i in range(3, 21):
        call("POST", "/user-views", admin, {
            "pageKey": PAGE, "name": "批量视图 %02d" % i, "view": view_payload(), "isDefault": False
        })
    listed = call("GET", "/user-views?pageKey=" + PAGE, admin).get("data") or []
    check("已保存 20 套视图", len(listed) == 20, len(listed))
    overflow = call("POST", "/user-views", admin, {
        "pageKey": PAGE, "name": "第二十一套", "view": view_payload(), "isDefault": False
    })
    check("超过 20 套被拒(1002)", overflow.get("code") == 1002, overflow)

    call("DELETE", "/user-views/%s" % second_id, admin)
    listed = call("GET", "/user-views?pageKey=" + PAGE, admin).get("data") or []
    check("删除后数量减少", len(listed) == 19, len(listed))
    missing = call("DELETE", "/user-views/99999999", admin)
    check("删除不存在的视图(2012)", missing.get("code") == 2012, missing)

    for item in listed:
        call("DELETE", "/user-views/%s" % item["id"], admin)
    left = call("GET", "/user-views?pageKey=" + PAGE, admin).get("data") or []
    check("清理后列表为空", len(left) == 0, left)

    print("== 7. 兼容未存列顺序的旧视图 ==")
    legacy_page = "test.views-legacy"
    for item in call("GET", "/user-views?pageKey=" + legacy_page, admin).get("data") or []:
        call("DELETE", "/user-views/%s" % item["id"], admin)
    legacy = call("POST", "/user-views", admin, {
        "pageKey": legacy_page, "name": "旧版视图",
        "view": {
            "columns": [{"key": "workNo", "visible": True, "width": 180, "fixed": "left"}],
            "filters": {}, "pageSize": 20
        },
        "isDefault": False
    })
    check("不带 order 的旧视图能保存", ok(legacy), legacy)
    legacy_row = (call("GET", "/user-views?pageKey=" + legacy_page, admin).get("data") or [{}])[0]
    check("旧视图读取正常且无 order", "order" not in (legacy_row.get("view") or {}), legacy_row.get("view"))
    call("DELETE", "/user-views/%s" % legacy_row.get("id"), admin)


main()
print()
print("通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
