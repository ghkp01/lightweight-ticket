import json
import os
import time
import urllib.parse
import urllib.request
import urllib.error
from datetime import date, timedelta

BASE = os.environ.get("MES_BASE", "http://localhost:8080/api").rstrip("/")
TODAY = date.today().isoformat()
DELIVERY = (date.today() + timedelta(days=7)).isoformat()
TAG = str(int(time.time()))[-6:]
PASS = 0
FAILS = []


def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path,
                                 data=json.dumps(body).encode("utf-8") if body is not None else None,
                                 method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8")
        try:
            payload = json.loads(text)
            payload["_http"] = e.code
            return payload
        except Exception:
            return {"code": e.code, "message": text, "_http": e.code}


def ok(payload):
    return payload.get("code") == 0


def check(name, cond, extra=""):
    global PASS
    if cond:
        PASS += 1
        print("  PASS  " + name)
    else:
        FAILS.append(name)
        print("  FAIL  " + name + "  " + str(extra))


def section(title):
    print("\n== " + title + " ==")


token = call("POST", "/auth/login", {"username": "admin", "password": "123456"})["data"]["token"]

section("1. 组织树与层级规则")
tree = call("GET", "/orgs/tree", token=token)["data"]
check("组织树根节点都是工厂", len(tree) >= 1 and all(row["type"] == "PLANT" for row in tree), tree[:1])
root = next((row for row in tree if row.get("code") == "PLANT01"), tree[0])
children = root.get("children") or []
workshops = [c for c in children if c["type"] == "WORKSHOP"]
check("工厂下有车间", len(workshops) >= 2, len(workshops))
check("车间下挂班组", any(len(w.get("children") or []) >= 2 for w in workshops))
check("同级按排序而非路径", children[0]["type"] == "WORKSHOP", [c["name"] for c in children][:3])
plant_id = root["id"]
ws_id = workshops[0]["id"]
ws2_id = workshops[1]["id"]
first_team = (workshops[0].get("children") or [])[0]
check("班组层级为 3", first_team["level"] == 3, first_team.get("level"))

wrong = call("POST", "/orgs", {
    "code": "TEAMX" + TAG, "name": "错挂班组" + TAG, "type": "TEAM", "parentId": plant_id
}, token=token)
check("班组不能直挂工厂", wrong.get("code") == 2001, wrong)

no_parent = call("POST", "/orgs", {
    "code": "WSZ" + TAG, "name": "无主车间" + TAG, "type": "WORKSHOP"
}, token=token)
check("车间不能作为顶级", no_parent.get("code") == 2001, no_parent)

section("2. 新建、移动与删除护栏")
created_ws = call("POST", "/orgs", {
    "code": "WST" + TAG, "name": "测试车间" + TAG, "type": "WORKSHOP",
    "parentId": plant_id, "sort": 90, "leader": "测试主任"
}, token=token)
check("新建车间成功", ok(created_ws), created_ws)
ws_new = created_ws.get("data")
detail = call("GET", f"/orgs/{ws_new}", token=token)["data"]
check("车间路径为 /工厂/车间/", str(detail["path"]).endswith(f"/{ws_new}/") and detail["level"] == 2, detail)
check("车间成员为空", detail.get("members") == [], detail.get("members"))

created_team = call("POST", "/orgs", {
    "code": "TMT" + TAG, "name": "测试班组" + TAG, "type": "TEAM", "parentId": ws_new
}, token=token)
team_id = created_team.get("data")
team_detail = call("GET", f"/orgs/{team_id}", token=token)["data"]
check("新建班组层级为 3", team_detail["level"] == 3, team_detail)

guard = call("DELETE", f"/orgs/{ws_new}", token=token)
check("有下级的组织不能删除", guard.get("code") == 2002, guard)

moved = call("PUT", f"/orgs/{team_id}", {
    "code": "TMT" + TAG, "name": "测试班组" + TAG, "type": "TEAM", "parentId": ws2_id, "sort": 1
}, token=token)
check("班组可移到其他车间", ok(moved), moved)
after_move = call("GET", f"/orgs/{team_id}", token=token)["data"]
check("移动后上级更新", after_move["parentId"] == ws2_id, after_move)
check("移动后路径重算", f"/{ws2_id}/{team_id}/" in str(after_move["path"]), after_move["path"])

call("DELETE", f"/orgs/{team_id}", token=token)
deleted = call("DELETE", f"/orgs/{ws_new}", token=token)
check("移走下级后可删除", ok(deleted), deleted)

section("3. 组织成员")
members = call("GET", f"/orgs/{first_team['id']}/members", token=token)["data"]
check("班组成员可查询", any(m["username"] == "worker1" for m in members), members)
sub_members = call("GET", f"/orgs/{ws_id}/members?includeChildren=true", token=token)["data"]
check("含下级成员查询生效", len(sub_members) >= len(members), (len(sub_members), len(members)))

section("4. 工作中心与工序绑定")
centers = call("GET", "/work-centers", token=token)["data"]
check("种子工作中心 6 个", len(centers) >= 6, len(centers))
cut_center = [c for c in centers if c["code"] == "WC-CUT"][0]
check("下料中心挂了工序", cut_center["processCount"] >= 1 and "下料" in str(cut_center["processNames"]), cut_center)
check("工作中心带日产能", cut_center["dailyCapacityMinutes"] == 480, cut_center["dailyCapacityMinutes"])

bad_capacity = call("POST", "/work-centers", {
    "code": "WCB" + TAG, "name": "零产能中心" + TAG, "orgId": ws_id, "type": "HAND",
    "dailyCapacityMinutes": 0
}, token=token)
check("产能必须为正", bad_capacity.get("code") == 1002, bad_capacity)

new_center = call("POST", "/work-centers", {
    "code": "WCT" + TAG, "name": "测试中心" + TAG, "orgId": ws_id, "type": "MACHINE",
    "dailyCapacityMinutes": 300
}, token=token)
center_id = new_center.get("data")
check("新建工作中心成功", ok(new_center), new_center)

processes = call("GET", "/processes?keyword=" + urllib.parse.quote("下料"), token=token)["data"]
cut_process = [p for p in processes if p["code"] == "CUT"][0]
check("工序带工作中心信息", cut_process.get("workCenterId") == cut_center["id"], cut_process)
check("工序带工作中心名称", cut_process.get("workCenterName") == cut_center["name"], cut_process.get("workCenterName"))
bind = call("PUT", f"/processes/{cut_process['id']}", {
    "code": cut_process["code"], "name": cut_process["name"], "category": cut_process["category"],
    "defaultMinutes": float(cut_process["defaultMinutes"]), "defaultPrice": float(cut_process["defaultPrice"]),
    "needInspection": bool(cut_process["needInspection"]), "enabled": bool(cut_process["enabled"]),
    "workCenterId": center_id, "remark": cut_process.get("remark")
}, token=token)
check("工序可改绑工作中心", ok(bind), bind)
center_after = call("GET", f"/work-centers?keyword=WCT{TAG}", token=token)["data"][0]
check("工作中心显示引用工序", center_after["processCount"] == 1, center_after)

in_use = call("DELETE", f"/work-centers/{center_id}", token=token)
check("被工序引用的工作中心不能删除", in_use.get("code") == 2004, in_use)
call("PUT", f"/processes/{cut_process['id']}", {
    "code": cut_process["code"], "name": cut_process["name"], "category": cut_process["category"],
    "defaultMinutes": float(cut_process["defaultMinutes"]), "defaultPrice": float(cut_process["defaultPrice"]),
    "needInspection": bool(cut_process["needInspection"]), "enabled": bool(cut_process["enabled"]),
    "workCenterId": cut_center["id"], "remark": cut_process.get("remark")
}, token=token)
unbind = call("DELETE", f"/work-centers/{center_id}", token=token)
check("解绑后可删除工作中心", ok(unbind), unbind)

section("5. 排产按工作中心产能")
wid = call("POST", "/work-orders", {
    "productId": 1, "planQty": 20, "planStart": TODAY, "planEnd": DELIVERY
}, token=token)["data"]
# APS 只排草稿工单；窗口给足 30 天，避免库里历史单据把前两周产能占满导致「排不进去」的假失败
aps = call("POST", "/scheduling/aps", {"startDate": TODAY, "days": 30}, token=token)
check("有限产能排产执行", ok(aps), aps)
assignments = aps["data"]["assignments"] if ok(aps) else []
daily = aps["data"]["dailyLoad"] if ok(aps) else []
check("排程带工作中心", assignments and all(a.get("workCenterName") for a in assignments),
      assignments[:1])
center_capacity = {c["name"]: c["dailyCapacityMinutes"] for c in centers}
check("负荷行按工作中心统计",
      all(row.get("workCenterName") for row in daily),
      daily[:1])
check("本次排入工序不超工作中心产能",
      all(int(a["minutes"]) <= int(a["capacity"]) for a in assignments if a.get("capacity")),
      [a for a in assignments if int(a["minutes"]) > int(a["capacity"] or 0)][:2])
check("超载行带 overloaded 标记",
      all((int(row["minutes"]) > int(row["capacity"])) == bool(row.get("overloaded")) for row in daily),
      [r for r in daily if (int(r["minutes"]) > int(r["capacity"])) != bool(r.get("overloaded"))][:2])
check("产能取自工作中心而非工序",
      all(int(row["capacity"]) in set(center_capacity.values()) for row in daily),
      sorted({int(r["capacity"]) for r in daily}))

section("6. 用户归属组织")
user_name = "orguser" + TAG
user_phone = "137" + TAG + "0"
created_user = call("POST", "/system/users", {
    "username": user_name, "password": "Org@123456", "realName": "组织测试员" + TAG,
    "phone": user_phone[:11], "role": "PLANNER", "orgId": first_team["id"],
    "position": "操作工", "employeeNo": "E9" + TAG, "enabled": True
}, token=token)
check("新建用户可挂组织岗位", ok(created_user), created_user)
users = call("GET", "/system/users?keyword=" + user_name, token=token)["data"]["records"]
row = users[0] if users else {}
check("用户列表返回组织与工号",
      row.get("orgName") == first_team["name"] and row.get("employeeNo") == "E9" + TAG
      and row.get("position") == "操作工", row)

dup = call("POST", "/system/users", {
    "username": user_name + "b", "password": "Org@123456", "realName": "工号重复" + TAG,
    "role": "PLANNER", "orgId": first_team["id"], "employeeNo": "E9" + TAG
}, token=token)
check("工号唯一", dup.get("code") == 1002, dup)

login = call("POST", "/auth/login", {"username": user_name, "password": "Org@123456"})
check("新用户可登录", ok(login), login)
me = call("GET", "/auth/me", token=login["data"]["token"])["data"] if ok(login) else {}
check("/me 返回组织信息",
      me.get("orgName") == first_team["name"] and me.get("position") == "操作工"
      and str(me.get("orgPath", "")).count("/") >= 3, me)

section("7. 车间看板按组织过滤")
board = call("GET", f"/workshop/board?orgId={ws_id}", token=token)
check("看板按车间过滤", ok(board) and board["data"].get("orgName") == workshops[0]["name"], board)
all_board = call("GET", "/workshop/board", token=token)
check("不传组织时为全厂", ok(all_board) and all_board["data"].get("orgName") == "全厂", all_board.get("message"))
check("看板卡片带工作中心",
      all("workCenterName" in card for card in all_board["data"]["locations"]),
      all_board["data"]["locations"][:1])

print("\n================ 汇总 ================")
print("通过: %d 项" % PASS)
print("失败: %d 项" % len(FAILS))
if FAILS:
    for name in FAILS:
        print("  - " + name)
    raise SystemExit(1)
