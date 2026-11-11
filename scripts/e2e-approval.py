"""审批流端到端回归：跨厂调拨自动进审批、两级依次通过、驳回与撤回、流程配置与越权。

用法：python scripts/e2e-approval.py [http://localhost:8080]
依赖：二号工厂 P2E2E 与两个测试仓 W1E2E / W2E2E（e2e-factory.py 会创建，本脚本也会兜底创建）
"""

import json
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta
import os

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PASSED = 0
FAILED = []
TAG = str(int(time.time()))[-6:]
PASSWORD = "Appr@123456"
# 首次登录必须改密，改完统一用这个口令，脚本可重复运行
READY_PASSWORD = "Appr@1234567"
TODAY = date.today()


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
    # 强制改密之后 admin 的口令可能已经变了，用 MES_PASSWORD 传入
    password = password or os.environ.get("MES_PASSWORD", "123456")
    status, res = call("POST", "/auth/login", body={"username": username, "password": password})
    return res["data"]["token"] if res.get("code") == 0 else None


def ok(payload):
    return isinstance(payload, dict) and payload.get("code") == 0


def rows_of(payload):
    data = payload.get("data") if isinstance(payload, dict) else payload
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        return data.get("records") or []
    return []


def section(title):
    print("\n== " + title + " ==")


def check(name, cond, extra=""):
    global PASSED
    if cond:
        PASSED += 1
        print("  [OK]   " + name)
    else:
        FAILED.append(name)
        print("  [FAIL] " + name + "  " + str(extra))


def ensure_plant(code, name):
    _, rows = call("GET", "/orgs?type=PLANT", admin)
    for row in rows_of(rows):
        if row.get("code") == code:
            return row.get("id")
    _, created = call("POST", "/orgs", admin, {
        "code": code, "name": name, "type": "PLANT", "sort": 9, "enabled": True
    })
    return created.get("data")


def ensure_warehouse(code, name, factory_id=None):
    _, rows = call("GET", "/warehouses", admin)
    for row in rows_of(rows):
        if row.get("code") == code:
            if factory_id and row.get("factoryOrgId") != factory_id:
                call("PUT", f"/warehouses/{row.get('id')}", admin,
                     {"code": code, "name": name, "type": "RAW", "factoryOrgId": factory_id})
            return row.get("id")
    body = {"code": code, "name": name, "type": "RAW"}
    if factory_id:
        body["factoryOrgId"] = factory_id
    _, created = call("POST", "/warehouses", admin, body)
    return created.get("data")


def ensure_user(username, real_name, role, org_id):
    """建号并完成首次改密：新建用户带 mustChangePassword，不改密的话除改密外所有接口都返回 4023"""
    call("POST", "/system/users", admin, {
        "username": username, "password": PASSWORD, "realName": real_name,
        "role": role, "orgId": org_id, "enabled": True
    })
    token = login(username, READY_PASSWORD)
    if token:
        return token
    token = login(username, PASSWORD)
    if token is None:
        return None
    status, changed = call("PUT", "/auth/password", token,
                           {"oldPassword": PASSWORD, "newPassword": READY_PASSWORD})
    if ok(changed):
        return (changed.get("data") or {}).get("token")
    return token


def create_transfer(token, remark):
    return call("POST", "/transfers", token, {
        "fromWarehouseId": w1, "toWarehouseId": w2, "remark": remark,
        "items": [{"productId": 1, "qty": 1}]
    })


def transfer_status(transfer_id):
    _, detail = call("GET", f"/transfers/{transfer_id}", admin)
    return (detail.get("data") or {}).get("status"), (detail.get("data") or {}).get("approvalInstanceId")


def task_of(token, transfer_id):
    _, tasks = call("GET", "/approvals/tasks", token)
    for task in rows_of(tasks):
        _, detail = call("GET", f"/transfers/{transfer_id}", admin)
        instance_id = (detail.get("data") or {}).get("approvalInstanceId")
        if task.get("instanceId") == instance_id:
            return task
    return None


admin = login("admin")
check("管理员登录", admin is not None)

section("准备两厂与测试仓")
plant2 = ensure_plant("P2E2E", "二号厂（回归）")
plant1 = next((row.get("id") for row in rows_of(call("GET", "/orgs?type=PLANT", admin)[1])
               if row.get("id") != plant2), None)
check("两个工厂就绪", plant1 is not None and plant2 is not None, (plant1, plant2))
w1 = ensure_warehouse("W1E2E", "一号厂测试仓", plant1)
w2 = ensure_warehouse("W2E2E", "二号厂测试仓", plant2)
check("跨厂两个仓库就绪", w1 is not None and w2 is not None, (w1, w2))
warehouse = login("warehouse")
check("仓库账号登录", warehouse is not None)
approver2 = ensure_user("wh2e2e", "二号厂仓管", "WAREHOUSE", plant2)
check("第二级审批账号可用", approver2 is not None)
planner = login("planner")
check("计划账号登录", planner is not None)

section("流程配置")
status, flows = call("GET", "/approvals/flows", admin)
flow_rows = rows_of(flows)
flow = next((row for row in flow_rows if row.get("code") == "CROSS_FACTORY_TRANSFER"), None)
check("默认跨厂调拨流程存在", flow is not None, flow_rows)
_, original_detail = call("GET", f"/approvals/flows/{flow['id']}", admin)
original_xml = (original_detail.get("data") or {}).get("bpmnXml") or ""

# 标准形态：角色 + 或签，两级串行。脚本可重复运行，先归一再断言，避免上一轮遗留配置干扰
STANDARD_NODES = [
    {"seq": 1, "name": "源厂计划审核", "approverType": "ROLE", "approverValues": ["PLANNER"],
     "multiMode": "ANY"},
    {"seq": 2, "name": "目标厂仓库确认", "approverType": "ROLE", "approverValues": ["WAREHOUSE"],
     "multiMode": "ANY"},
]


def save_standard():
    return call("PUT", f"/approvals/flows/{flow['id']}", admin, {
        "code": flow["code"], "name": flow["name"], "bizType": flow["bizType"], "enabled": True,
        "remark": flow.get("remark") or "", "nodes": STANDARD_NODES})


if flow:
    status, normalized = save_standard()
    check("流程可归一为标准形态", ok(normalized), normalized)
    _, fresh = call("GET", f"/approvals/flows/{flow['id']}", admin)
    flow = fresh.get("data") or flow
    # BPMN 是唯一事实来源：节点从已部署模型里读，只有 itemKey / name / nodeKind / multiMode
    nodes = flow.get("nodes") or []
    check("流程有两个节点", len(nodes) == 2, nodes)
    node_names = [node.get("name") for node in nodes]
    check("节点顺序为计划 → 仓库", node_names == ["源厂计划审核", "目标厂仓库确认"], node_names)
    check("两级节点都是审批节点且为或签",
          all(node.get("nodeKind") == "APPROVER" and node.get("multiMode") == "ANY" for node in nodes),
          nodes)
    check("流程处于启用状态", flow.get("enabled") is True, flow.get("enabled"))
status, forbidden = call("POST", "/approvals/flows", warehouse, {
    "code": "X" + TAG, "name": "越权流程", "bizType": "TRANSFER", "enabled": False,
    "nodes": [{"seq": 1, "name": "节点", "approverType": "ROLE", "approverValues": ["PLANNER"],
               "multiMode": "ANY"}]
})
check("非管理员不能改流程", status == 403 or not ok(forbidden), (status, forbidden))

section("画布配置项落库")
status, saved = call("PUT", f"/approvals/flows/{flow['id']}", admin, {
    "code": flow["code"], "name": flow["name"], "bizType": flow["bizType"], "enabled": True,
    "remark": flow.get("remark") or "",
    "nodes": [
        {"seq": 1, "name": "源厂计划审核", "approverType": "ROLE",
         "approverValues": ["PLANNER"], "multiMode": "ANY",
         "conditionField": "QTY", "conditionOp": ">=", "conditionValue": "1",
         "ccUsers": ["admin"], "remark": "数量达到 1 才需要计划审核"},
        {"seq": 2, "name": "目标厂仓库确认", "approverType": "USER",
         "approverValues": ["wh2e2e"], "multiMode": "ALL", "remark": ""}
    ]
})
check("画布配置可保存", ok(saved), saved)
_, after = call("GET", f"/approvals/flows/{flow['id']}", admin)
after_detail = after.get("data") or {}
nodes_after = after_detail.get("nodes") or []
# 条件不再是节点字段，而是编译进 BPMN 的 JUEL 表达式；审批人/抄送进策略表
check("条件编译进 BPMN", "${qty >= 1}" in str(after_detail.get("bpmnXml") or ""),
      after_detail.get("bpmnXml"))
_, policies = call("GET", f"/approvals/flows/{flow['id']}/node-policies", admin)
policy_rows = rows_of(policies)
check("抄送人落进节点策略",
      any("admin" in str(row.get("ccUsers") or "") for row in policy_rows), policy_rows)
check("会签与指定用户编译进模型",
      len(nodes_after) > 1 and nodes_after[1].get("multiMode") == "ALL", nodes_after)
status, restored = save_standard()
check("流程配置可还原", ok(restored), restored)

section("跨厂调拨提交审批")
status, created = create_transfer(warehouse, "审批回归 " + TAG)
transfer_id = created.get("data")
check("跨厂调拨创建成功", ok(created) and transfer_id, created)
current, instance_id = transfer_status(transfer_id)
check("跨厂调拨进入待审批", current == "PENDING_APPROVAL" and instance_id, (current, instance_id))
status, blocked = call("POST", f"/transfers/{transfer_id}/complete", warehouse)
check("审批中不能执行调拨", not ok(blocked), blocked)

_, detail = call("GET", f"/approvals/instances/{instance_id}", admin)
instance = detail.get("data") or {}
check("审批停在第一节点", instance.get("currentNode") == "源厂计划审核", instance.get("currentNode"))
check("实例状态为审批中", instance.get("status") == "PENDING", instance.get("status"))

section("两级依次通过")
task1 = task_of(planner, transfer_id)
check("计划能看到待办", task1 is not None, task1)
if task1:
    status, first = call("POST", f"/approvals/tasks/{task1['id']}/approve", planner,
                         {"comment": "数量核对无误"})
    check("计划审批通过", ok(first) and (first.get("data") or {}).get("status") == "PENDING", first)
    check("流转到第二节点", (first.get("data") or {}).get("nextNode") == "目标厂仓库确认", first.get("data"))
    current, _ = transfer_status(transfer_id)
    check("调拨仍是待审批", current == "PENDING_APPROVAL", current)

task2 = task_of(approver2, transfer_id)
check("仓库能看到第二级待办", task2 is not None, task2)
if task2:
    status, second = call("POST", f"/approvals/tasks/{task2['id']}/approve", approver2,
                          {"comment": "仓位已安排"})
    check("仓库审批通过", ok(second) and (second.get("data") or {}).get("status") == "APPROVED", second)
    current, _ = transfer_status(transfer_id)
    check("调拨变成已批准", current == "APPROVED", current)
    _, final = call("GET", f"/approvals/instances/{instance_id}", admin)
    tasks = (final.get("data") or {}).get("tasks") or []
    level_rows = [task for task in tasks
                  if task.get("id") and task.get("nodeName") in ("源厂计划审核", "目标厂仓库确认")]
    check("审批记录留存两级意见", len(level_rows) == 2 and all(task.get("comment") for task in level_rows), tasks)

def pending_tasks(token, transfer_id):
    """返回某张调拨单的审批实例 id，以及该账号在这个实例上还能看到的待办"""
    _, detail = call("GET", f"/transfers/{transfer_id}", admin)
    instance = (detail.get("data") or {}).get("approvalInstanceId")
    _, tasks = call("GET", "/approvals/tasks", token)
    return instance, [task for task in rows_of(tasks) if task.get("instanceId") == instance]


section("多类型节点（抄送 / 条件 / 自动通过）")
status, saved = call("PUT", f"/approvals/flows/{flow['id']}", admin, {
    "code": flow["code"], "name": flow["name"], "bizType": flow["bizType"], "enabled": True,
    "remark": flow.get("remark") or "",
    "nodes": [
        {"seq": 1, "itemKey": "n1", "name": "抄送计划部", "nodeKind": "CC", "ccUsers": ["admin"],
         "approverType": "ROLE", "approverValues": ["PLANNER"], "multiMode": "ANY"},
        {"seq": 2, "itemKey": "n2", "name": "计划审核", "nodeKind": "APPROVER", "approverType": "ROLE",
         "approverValues": ["PLANNER"], "multiMode": "ANY"},
        {"seq": 3, "itemKey": "n3", "name": "数量分支", "nodeKind": "CONDITION", "conditionField": "QTY",
         "conditionOp": ">=", "conditionValue": "100", "approverType": "ROLE",
         "approverValues": [], "multiMode": "ANY"},
        {"seq": 4, "itemKey": "n4", "name": "仓库复核", "nodeKind": "APPROVER",
         "parentKey": "n3", "branchKey": "TRUE",
         "approverType": "ROLE", "approverValues": ["WAREHOUSE"], "multiMode": "ANY"},
        {"seq": 5, "itemKey": "n5", "name": "自动归档", "nodeKind": "AUTO", "approverType": "ROLE",
         "approverValues": [], "multiMode": "ANY"}
    ]
})
check("多类型节点流程可保存", ok(saved), saved)
_, kinds = call("GET", f"/approvals/flows/{flow['id']}", admin)
kind_detail = kinds.get("data") or {}
_, kind_policies = call("GET", f"/approvals/flows/{flow['id']}/node-policies", admin)
kind_policy_rows = rows_of(kind_policies)
cc_rows = [row for row in kind_policy_rows if row.get("nodeKind") == "CC"]
check("抄送节点与抄送人落库",
      len(cc_rows) == 1 and "admin" in str(cc_rows[0].get("ccUsers") or ""),
      kind_policy_rows)
check("条件分支编译进 BPMN",
      "${qty >= 100}" in str(kind_detail.get("bpmnXml") or ""), kind_detail.get("bpmnXml"))
_, bpmn = call("GET", f"/approvals/flows/{flow['id']}", admin)
check("保存后已部署 BPMN", (bpmn.get("data") or {}).get("processVersion") is not None, bpmn.get("data"))

status, mixed_transfer = create_transfer(warehouse, "多类型节点回归 " + TAG)
mixed_id = mixed_transfer.get("data")
mixed_instance, mixed_tasks = pending_tasks(planner, mixed_id)
check("抄送节点不停留，直接到计划审核", len(mixed_tasks) == 1, mixed_tasks)
if mixed_tasks:
    call("POST", f"/approvals/tasks/{mixed_tasks[0]['id']}/approve", planner, {"comment": "同意"})
current, _ = transfer_status(mixed_id)
check("条件不命中时分支跳过并走完流程", current == "APPROVED", current)
_, mixed_detail = call("GET", f"/approvals/instances/{mixed_instance}", admin)
mixed_rows = (mixed_detail.get("data") or {}).get("tasks") or []
check("抄送与自动通过留有记录",
      any(row.get("action") == "CC" for row in mixed_rows)
      and any(row.get("action") == "AUTO" for row in mixed_rows), mixed_rows)
check("条件不命中时仓库复核没有产生待办",
      not any(row.get("nodeName") == "仓库复核" for row in mixed_rows), mixed_rows)
status, scanned = call("POST", "/approvals/timeout-scan", admin)
check("超时扫描接口可用", ok(scanned) and "scanned" in (scanned.get("data") or {}), scanned)
status, restored_mixed = save_standard()
check("多类型节点流程可还原", ok(restored_mixed), restored_mixed)

section("转交 / 加签 / 催办")
status, moved_transfer = create_transfer(warehouse, "转交回归 " + TAG)
moved_id = moved_transfer.get("data")
moved_instance, planner_tasks = pending_tasks(planner, moved_id)
check("转交前计划有第一级待办", len(planner_tasks) == 1, planner_tasks)
status, urged = call("POST", f"/approvals/instances/{moved_instance}/urge", warehouse)
check("申请人可以催办", ok(urged) and ((urged.get("data") or {}).get("notified") or 0) >= 1, urged)
status, denied_urge = call("POST", f"/approvals/instances/{moved_instance}/urge", approver2)
check("非申请人不能催办", not ok(denied_urge), denied_urge)
if planner_tasks:
    status, moved = call("POST", f"/approvals/tasks/{planner_tasks[0]['id']}/transfer", planner,
                         {"targetUser": "wh2e2e", "comment": "我不在厂里，转给你"})
    check("转交成功", ok(moved) and (moved.get("data") or {}).get("assignee") == "wh2e2e", moved)
    _, left = pending_tasks(planner, moved_id)
    check("转交后原审批人没有待办", not left, left)
    _, taken = pending_tasks(approver2, moved_id)
    check("被转交人看到待办", len(taken) == 1, taken)
    status, still = call("POST", f"/approvals/tasks/{planner_tasks[0]['id']}/approve", planner, {"comment": "同意"})
    check("转交后原审批人不能再处理", not ok(still), still)
    if taken:
        status, first_done = call("POST", f"/approvals/tasks/{taken[0]['id']}/approve", approver2,
                                  {"comment": "收到"})
        check("被转交人审批后流转到第二级",
              ok(first_done) and (first_done.get("data") or {}).get("nextNode") == "目标厂仓库确认", first_done)
        _, second_tasks = pending_tasks(approver2, moved_id)
        if second_tasks:
            call("POST", f"/approvals/tasks/{second_tasks[0]['id']}/approve", approver2, {"comment": "仓位已安排"})
        current, _ = transfer_status(moved_id)
        check("转交后的单据能走完整条流程", current == "APPROVED", current)

status, added_transfer = create_transfer(warehouse, "加签回归 " + TAG)
added_id = added_transfer.get("data")
added_instance, adder_tasks = pending_tasks(planner, added_id)
if adder_tasks:
    status, added = call("POST", f"/approvals/tasks/{adder_tasks[0]['id']}/add-sign", planner,
                         {"targetUser": "admin", "comment": "请管理员一起看"})
    check("加签成功", ok(added) and "加签" in str((added.get("data") or {}).get("message") or ""), added)

    status, blocked = call("POST", f"/approvals/tasks/{adder_tasks[0]['id']}/approve", planner,
                           {"comment": "同意"})
    check("加签人没处理前原审批人不能通过",
          not ok(blocked) and "加签" in str(blocked.get("message") or (blocked.get("data") or {}).get("message") or ""), blocked)

    _, admin_tasks = call("GET", "/approvals/tasks", admin)
    extra = next((row for row in (admin_tasks.get("data") or [])
                  if row.get("extra") and row.get("instanceId") == added_instance), None)
    check("加签人待办出现在列表里", extra is not None, admin_tasks.get("data"))
    if extra:
        status, extra_done = call("POST", f"/approvals/extra-tasks/{extra['id']}/approve", admin,
                                  {"comment": "我看过了"})
        check("加签人可以确认", ok(extra_done), extra_done)

    status, passed = call("POST", f"/approvals/tasks/{adder_tasks[0]['id']}/approve", planner,
                          {"comment": "同意"})
    check("加签人确认后原审批人可以通过", ok(passed), passed)
    _, second_tasks = pending_tasks(approver2, added_id)
    if second_tasks:
        call("POST", f"/approvals/tasks/{second_tasks[0]['id']}/approve", approver2, {"comment": "仓位已安排"})
    current, _ = transfer_status(added_id)
    check("加签后单据能正常走完", current == "APPROVED", current)

section("驳回")
status, rejected_transfer = create_transfer(warehouse, "驳回回归 " + TAG)
rejected_id = rejected_transfer.get("data")
task = task_of(planner, rejected_id)
if task:
    status, rejected = call("POST", f"/approvals/tasks/{task['id']}/reject", planner, {"comment": "源仓库存不足"})
    check("驳回成功", ok(rejected) and (rejected.get("data") or {}).get("status") == "REJECTED", rejected)
    current, _ = transfer_status(rejected_id)
    check("调拨变成已驳回", current == "REJECTED", current)
    status, blocked = call("POST", f"/transfers/{rejected_id}/complete", warehouse)
    check("驳回后不能执行调拨", not ok(blocked), blocked)

# 驳回必须填原因：换一张单，避免撞上「已处理」
_, another = create_transfer(warehouse, "空意见回归 " + TAG)
another_task = task_of(planner, another.get("data"))
if another_task:
    status, no_reason = call("POST", f"/approvals/tasks/{another_task['id']}/reject", planner, {"comment": ""})
    check("驳回必须填原因", not ok(no_reason), no_reason)

section("撤回")
status, cancelled_transfer = create_transfer(warehouse, "撤回回归 " + TAG)
cancelled_id = cancelled_transfer.get("data")
_, cancelled_detail = call("GET", f"/transfers/{cancelled_id}", admin)
cancelled_instance = (cancelled_detail.get("data") or {}).get("approvalInstanceId")
status, cancelled = call("POST", f"/approvals/instances/{cancelled_instance}/cancel", warehouse)
check("申请人可以撤回", ok(cancelled), cancelled)
current, _ = transfer_status(cancelled_id)
check("撤回后调拨回到草稿", current == "DRAFT", current)

section("同厂调拨不受影响")
same = call("POST", "/transfers", warehouse, {
    "fromWarehouseId": w1, "toWarehouseId": w1, "remark": "同仓自调", "items": [{"productId": 1, "qty": 1}]
})
check("同仓自调被拒", not ok(same), same)
_, same_wh = call("GET", "/warehouses", admin)
same_factory_wh = next((row.get("id") for row in rows_of(same_wh)
                        if row.get("factoryOrgId") == plant1 and row.get("id") != w1
                        and row.get("code") != "W2E2E"), None)
if same_factory_wh:
    status, same_transfer = call("POST", "/transfers", warehouse, {
        "fromWarehouseId": w1, "toWarehouseId": same_factory_wh, "remark": "同厂调拨",
        "items": [{"productId": 1, "qty": 1}]
    })
    same_id = same_transfer.get("data")
    current, same_instance = transfer_status(same_id)
    check("同厂调拨不进审批", current == "DRAFT" and not same_instance, (current, same_instance))
else:
    print("  [SKIP] 没有同厂第二仓库，跳过同厂调拨断言")

section("恢复默认 BPMN 图")
if original_xml:
    status, restored_xml = call("PUT", f"/approvals/flows/{flow['id']}", admin, {
        "code": flow["code"], "name": flow["name"], "bizType": flow["bizType"], "enabled": True,
        "remark": flow.get("remark") or "", "bpmnXml": original_xml
    })
    check("跑完后恢复带图形的默认流程", ok(restored_xml), restored_xml)
    _, after_restore = call("GET", f"/approvals/flows/{flow['id']}", admin)
    restored_detail = after_restore.get("data") or {}
    # 节点现在从已部署的 BPMN 反推，所以「恢复后 nodes 为空」不再成立；
    # 该断言改成「XML 已还原 + 仍能部署出节点」，避免把已删除的节点表当成事实来源
    check("恢复后 BPMN 与默认一致且节点可读",
          restored_detail.get("bpmnXml") == original_xml
          and restored_detail.get("processVersion") is not None
          and len(restored_detail.get("nodes") or []) >= 1, restored_detail)

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
