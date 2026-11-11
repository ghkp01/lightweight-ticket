# -*- coding: utf-8 -*-
"""产能口径与人员技能专项验证

覆盖：工作中心设备台账、可用设备折算、外协中心、人员技能候选与派工持证校验。
用法：python scripts/e2e-capacity.py
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


def center_of(center_id, token):
    for item in call("GET", "/work-centers", token)["data"]:
        if item["id"] == center_id:
            return item
    return None


def main():
    admin = login("admin")
    if not admin:
        print("登录失败，请确认服务已启动")
        sys.exit(1)

    print("== 1. 工作中心返回设备统计 ==")
    centers = call("GET", "/work-centers", admin)["data"]
    check("工作中心列表带设备字段", all("deviceCount" in c for c in centers), centers[:1])
    mach = [c for c in centers if c["code"] == "WC-MACH"][0]
    check("机加中心已维护 2 台设备", mach["deviceCount"] == 2, mach["deviceCount"])
    check("设备产能折算不超过中心日产能",
          mach["deviceCapacityMinutes"] >= mach["dailyCapacityMinutes"], mach)

    print("== 2. 临时中心与设备台账 ==")
    suffix = str(int(__import__("time").time() * 1000))[-6:]
    org = [o for o in call("GET", "/orgs/options", admin)["data"] if o["name"] == "一号车间"][0]
    r = call("POST", "/work-centers", admin, {"code": "TWC" + suffix, "name": "测试中心" + suffix,
                                              "orgId": org["id"], "type": "MACHINE",
                                              "dailyCapacityMinutes": 480, "enabled": True})
    check("建临时工作中心", r.get("code") == 0, r)
    center_id = r["data"]
    r = call("POST", "/work-centers/%d/devices" % center_id, admin,
             {"code": "TD1" + suffix, "name": "测试设备1", "capacityMinutes": 120,
              "status": "RUNNING", "enabled": True})
    check("新增设备 1", r.get("code") == 0, r)
    device1 = r["data"]
    r = call("POST", "/work-centers/%d/devices" % center_id, admin,
             {"code": "TD2" + suffix, "name": "测试设备2", "capacityMinutes": 120,
              "status": "RUNNING", "enabled": True})
    check("新增设备 2", r.get("code") == 0, r)
    device2 = r["data"]
    r = call("POST", "/work-centers/%d/devices" % center_id, admin,
             {"code": "TD1" + suffix, "name": "重复编码", "capacityMinutes": 60})
    check("设备编码重复被拒(2019)", r.get("code") == 2019, r)
    devices = call("GET", "/work-centers/%d/devices" % center_id, admin)["data"]
    check("设备列表 2 台", len(devices) == 2, len(devices))

    center = center_of(center_id, admin)
    check("中心统计可用设备 2 台", center["activeDeviceCount"] == 2, center)
    check("可用设备产能合计 240", center["deviceCapacityMinutes"] == 240, center)

    print("== 3. 设备维修后产能下降 ==")
    call("PUT", "/devices/%d" % device1, admin,
         {"code": "TD1" + suffix, "name": "测试设备1", "capacityMinutes": 120,
          "status": "MAINTENANCE", "enabled": True})
    center = center_of(center_id, admin)
    check("维修设备不计可用台数", center["activeDeviceCount"] == 1, center)
    check("可用设备产能降为 120", center["deviceCapacityMinutes"] == 120, center)
    r = call("DELETE", "/devices/%d" % device2, admin)
    check("删除设备", r.get("code") == 0, r)

    print("== 4. 外协中心 ==")
    r = call("POST", "/work-centers", admin, {"code": "TWO" + suffix, "name": "外协测试" + suffix,
                                              "orgId": org["id"], "type": "OUTSOURCE",
                                              "dailyCapacityMinutes": 480, "enabled": True})
    check("建外协中心", r.get("code") == 0, r)
    outsource_id = r["data"]
    outsource = center_of(outsource_id, admin)
    check("类型为外协", outsource["type"] == "OUTSOURCE", outsource["type"])
    check("外协未挂工序", outsource["processCount"] == 0, outsource["processCount"])
    r = call("DELETE", "/work-centers/%d" % outsource_id, admin)
    check("删除外协中心", r.get("code") == 0, r)
    r = call("DELETE", "/work-centers/%d" % center_id, admin)
    check("删除临时中心（设备级联）", r.get("code") == 0, r)

    print("== 5. 人员技能与派工持证 ==")
    processes = {p["code"]: p for p in call("GET", "/processes", admin)["data"]}
    cut = processes["CUT"]
    cands = call("GET", "/processes/%d/skill-users" % cut["id"], admin)["data"]
    check("下料工序有持证候选人", any(c["username"] == "worker1" for c in cands),
          [c["username"] for c in cands])

    p1 = [p for p in call("GET", "/products", admin)["data"] if p["code"] == "P001"][0]
    r = call("POST", "/work-orders", admin, {"productId": p1["id"], "planQty": 3,
                                             "planStart": "2026-10-23", "planEnd": "2026-10-24",
                                             "priority": "NORMAL", "remark": "技能验证"})
    check("建验证工单", r.get("code") == 0, r)
    work_id = r["data"]["id"] if isinstance(r["data"], dict) else r["data"]
    detail = call("GET", "/work-orders/%d" % work_id, admin)["data"]
    op = [o for o in detail["operations"] if o["seq"] == 1][0]

    users = {u["username"]: u for u in call("GET", "/system/users/options", admin)["data"]}
    worker1 = users["worker1"]
    worker2 = users["worker2"]
    r = call("POST", "/work-orders/%d/operations/%d/assign" % (work_id, op["id"]), admin,
             {"userId": worker2["id"]})
    check("无证人员派工被拒(2022)", r.get("code") == 2022, r)
    r = call("POST", "/work-orders/%d/operations/%d/assign" % (work_id, op["id"]), admin,
             {"userId": worker1["id"]})
    check("持证人员派工成功", r.get("code") == 0, r)

    print("== 6. 补配技能后即可派工 ==")
    origin = call("GET", "/system/users/%d/skills" % worker2["id"], admin)["data"]
    items = [{"processId": item["processId"], "level": item.get("level") or "NORMAL"} for item in origin]
    items.append({"processId": cut["id"], "level": "NORMAL"})
    r = call("PUT", "/system/users/%d/skills" % worker2["id"], admin, {"items": items})
    check("给 worker2 补下料技能", r.get("code") == 0, r)
    cands = call("GET", "/processes/%d/skill-users" % cut["id"], admin)["data"]
    check("候选人增加到 2 人", len(cands) >= 2, [c["username"] for c in cands])
    r = call("POST", "/work-orders/%d/operations/%d/assign" % (work_id, op["id"]), admin,
             {"userId": worker2["id"]})
    check("补证后派工成功", r.get("code") == 0, r)

    # 收尾：恢复 worker2 原有技能，避免影响其它回归
    call("PUT", "/system/users/%d/skills" % worker2["id"], admin,
         {"items": [{"processId": item["processId"], "level": item.get("level") or "NORMAL"}
                    for item in origin]})
    restored = call("GET", "/system/users/%d/skills" % worker2["id"], admin)["data"]
    check("技能已还原", all(item["processId"] != cut["id"] for item in restored),
          [item["processName"] for item in restored])

    print("\n结果：%d 项通过，%d 项失败" % (PASSED, len(FAILED)))
    if FAILED:
        print("失败项：" + "、".join(FAILED))
        sys.exit(1)


if __name__ == "__main__":
    main()
