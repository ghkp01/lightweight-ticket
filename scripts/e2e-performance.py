"""绩效核算回归：生成绩效单 → 录实际工时重算 → 提交 → 审核通过，
以及越权、非法期间、提交后不许改工时这些边界。

用法：python scripts/e2e-performance.py [http://localhost:8080]
"""

import datetime
import json
import os
import sys
import urllib.error
import urllib.request

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
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as err:
        text = err.read().decode()
        try:
            return err.code, json.loads(text)
        except json.JSONDecodeError:
            return err.code, {"code": -1, "message": text}


def login(username, password=None):
    password = password or os.environ.get("MES_PASSWORD", "123456")
    status, res = call("POST", "/auth/login", body={"username": username, "password": password})
    return res["data"]["token"] if res.get("code") == 0 else None


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


def num(value):
    try:
        return float(value or 0)
    except (TypeError, ValueError):
        return 0.0


def find_task(token, instance_id):
    """在审批中心里按实例 id 找待办；找不到返回 None。"""
    _, tasks = call("GET", "/approvals/tasks", token)
    payload = tasks.get("data")
    items = (payload.get("records") if isinstance(payload, dict) else payload) or []
    for item in items:
        if item.get("instanceId") == instance_id:
            return item
    return None


def pick_draft(exclude=None):
    """挑一张草稿绩效单；本期已经被批完就先撤回一张（撤回重算是真实业务动作，不是测试后门）。"""
    _, resp = call("GET", "/performance/sheets?period=" + PERIOD + "&size=100", admin)
    rows = ((resp.get("data") or {}).get("records")) or []
    for row in rows:
        if row.get("status") in ("DRAFT", "REJECTED") and row.get("worker") != exclude:
            return row
    for row in rows:
        if row.get("status") in ("SUBMITTED", "APPROVED") and row.get("worker") != exclude:
            status, reopened = call("POST", "/performance/sheets/%d/reopen" % row["id"], admin,
                                    {"reason": "回归脚本撤回重算"})
            if ok(reopened) and (reopened.get("data") or {}).get("status") == "DRAFT":
                return reopened.get("data")
    return None


PERIOD = datetime.date.today().strftime("%Y-%m")

admin = login("admin")
check("管理员登录", admin is not None)
planner = login("planner")
check("计划登录", planner is not None)
warehouse = login("warehouse")
check("仓库登录", warehouse is not None)

section("生成本期绩效")
status, generated = call("POST", "/performance/generate", admin, {"period": PERIOD})
check("生成本期绩效成功", ok(generated), generated.get("message"))
data = generated.get("data") or {}
check("生成结果带期间", data.get("period") == PERIOD, data.get("period"))
check("生成结果带人数统计", "workers" in data and "created" in data, data)

section("列表与汇总")
status, sheets = call("GET", "/performance/sheets?period=" + PERIOD + "&size=50", admin)
check("绩效列表可查", ok(sheets), sheets.get("message"))
rows = ((sheets.get("data") or {}).get("records")) or []
status, summary = call("GET", "/performance/summary?period=" + PERIOD, admin)
check("绩效汇总可查", ok(summary), summary.get("message"))
summary_data = summary.get("data") or {}
check("汇总人数与明细一致", int(num(summary_data.get("workers"))) == len(rows),
      (summary_data.get("workers"), len(rows)))
check("汇总带计件与绩效工资", "pieceWage" in summary_data and "wage" in summary_data, summary_data)

section("撤回重算")
# 算错要能退回重算。全新库本期全是草稿，没有可撤回的单据，
# 所以先自己提交一张做出「已提交」状态，再验证撤回，别依赖上一次回归留下的数据。
_, fresh = call("GET", "/performance/sheets?period=" + PERIOD + "&size=100", admin)
rows_now = ((fresh.get("data") or {}).get("records") or [])
locked_rows = [r for r in rows_now if r.get("status") in ("SUBMITTED", "APPROVED")]
if not locked_rows:
    seed = next((r for r in rows_now if r.get("status") in ("DRAFT", "REJECTED")), None)
    if seed is not None:
        _, submitted_seed = call("POST", "/performance/sheets/%d/submit" % seed["id"], admin)
        if ok(submitted_seed):
            locked_rows = [submitted_seed.get("data") or seed]
check("有已提交/已通过的绩效单可验证撤回", bool(locked_rows), "本期全在草稿态且提交失败")
if locked_rows:
    target = locked_rows[0]
    status, planner_reopen = call("POST", "/performance/sheets/%d/reopen" % target["id"], planner,
                                  {"reason": "回归"})
    check("计划无权撤回绩效单", status == 403, (status, planner_reopen.get("message")))
    status, reopened = call("POST", "/performance/sheets/%d/reopen" % target["id"], admin,
                            {"reason": "回归脚本撤回重算"})
    check("已提交/已通过的能撤回重算", ok(reopened), reopened.get("message"))
    reopened_row = reopened.get("data") or {}
    check("撤回后回到草稿", reopened_row.get("status") == "DRAFT", reopened_row.get("status"))
    check("撤回后清掉审批实例", reopened_row.get("approvalInstanceId") is None,
          reopened_row.get("approvalInstanceId"))
    check("撤回不动已算好的工时",
          num(reopened_row.get("actualMinutes")) == num(target.get("actualMinutes")),
          (reopened_row.get("actualMinutes"), target.get("actualMinutes")))
    status, bad_reopen = call("POST", "/performance/sheets/%d/reopen" % target["id"], admin)
    check("草稿不能再撤回", not ok(bad_reopen), bad_reopen.get("message"))

section("录工时 → 重算 → 提交 → 审核")
draft = pick_draft()
if draft is None:
    check("有草稿绩效单可操作", False, "本期没有草稿行，可能全部已提交或本期无报工")
else:
    sheet_id = draft["id"]
    piece_wage = num(draft.get("pieceWage"))
    standard = num(draft.get("standardMinutes"))
    actual = max(standard * 2, 60)
    status, updated = call("PUT", "/performance/sheets/%d/minutes" % sheet_id, admin,
                           {"actualMinutes": actual, "remark": "回归脚本录入"})
    check("录入实际工时成功", ok(updated), updated.get("message"))
    row = updated.get("data") or {}
    # 标准工时可能很小（不足 30 分钟），actual 有 60 分钟下限，所以期望值按实际口径算
    expected_eff = standard / actual if actual else 0
    check("效率按标准/实际算出", abs(num(row.get("efficiency")) - expected_eff) < 0.01,
          (row.get("efficiency"), expected_eff, standard, actual))
    check("绩效系数已重算", num(row.get("score")) > 0, row.get("score"))
    check("绩效工资 = 计件工资 × 系数",
          abs(num(row.get("wage")) - piece_wage * num(row.get("score"))) < 0.02,
          (row.get("wage"), piece_wage, row.get("score")))

    status, submitted = call("POST", "/performance/sheets/%d/submit" % sheet_id, admin)
    check("提交绩效单成功", ok(submitted), submitted.get("message"))
    submit_data = submitted.get("data") or {}
    check("提交后状态为已提交", submit_data.get("status") == "SUBMITTED", submit_data.get("status"))

    status, blocked = call("PUT", "/performance/sheets/%d/minutes" % sheet_id, admin,
                           {"actualMinutes": 100})
    check("提交后不许再改工时", not ok(blocked), blocked.get("message"))

    # 系统若已配 PERFORMANCE 审批流，提交会自动进流程，审核只能走审批中心；
    # 没配才走绩效页自己的审核按钮。两种部署形态都要能批过去。
    instance_id = submit_data.get("approvalInstanceId")
    if instance_id:
        task = find_task(planner, instance_id)
        check("已配审批流时生成计划待办", task is not None, instance_id)
        if task:
            status, approved = call("POST", "/approvals/tasks/%s/approve" % task["id"], planner,
                                    {"comment": "回归脚本核对"})
        else:
            approved = {"code": -1, "message": "审批中心没有待办"}
    else:
        status, approved = call("POST", "/performance/sheets/%d/approve" % sheet_id, planner)
    check("计划可审核通过", ok(approved), approved.get("message"))
    _, sheet_now = call("GET", "/performance/sheets/%d" % sheet_id, admin)
    check("审核后状态为已通过", (sheet_now.get("data") or {}).get("status") == "APPROVED",
          (sheet_now.get("data") or {}).get("status"))

    status, again = call("POST", "/performance/sheets/%d/submit" % sheet_id, admin)
    check("已通过的不能重复提交", not ok(again), again.get("message"))

    # 重新生成不能覆盖已锁定的绩效单
    status, regenerated = call("POST", "/performance/generate", admin, {"period": PERIOD})
    check("重新生成不报错", ok(regenerated), regenerated.get("message"))
    check("重新生成会跳过已锁定行", int(num((regenerated.get("data") or {}).get("skipped"))) >= 1,
          (regenerated.get("data") or {}).get("skipped"))

section("工时填报驱动实际工时")
# 上一段已经把那张单批掉了，这里重新挑一张草稿，避免拿旧状态选人
hours_draft = pick_draft()
hour_worker = hours_draft["worker"] if hours_draft else None
if hour_worker is None:
    check("有可用的草稿员工填工时", False, "本期没有草稿行")
else:
    # 上一轮跑剩的工时先清掉，否则合计会累加成 1080 而不是本次的 480
    _, existing_hours = call("GET", "/performance/hours?period=" + PERIOD, admin)
    for item in ((existing_hours.get("data") or {}).get("rows") or []):
        if item.get("worker") == hour_worker:
            call("DELETE", "/performance/hours/%d" % item["id"], admin)
    day1 = PERIOD + "-01"
    day2 = PERIOD + "-02"
    status, h1 = call("POST", "/performance/hours", admin,
                      {"workDate": day1, "worker": hour_worker, "minutes": 480, "remark": "回归-上午"})
    check("工时填报成功", ok(h1), h1.get("message"))
    status, h2 = call("POST", "/performance/hours", admin,
                      {"workDate": day2, "worker": hour_worker, "minutes": 120, "remark": "回归-加班"})
    check("同一个人一天多条工时也能填", ok(h2), h2.get("message"))

    status, bad_hour = call("POST", "/performance/hours", admin,
                            {"workDate": day1, "worker": hour_worker, "minutes": 0, "remark": "非法"})
    check("0 分钟工时被拒", not ok(bad_hour), bad_hour.get("message"))
    status, bad_hour2 = call("POST", "/performance/hours", admin,
                             {"workDate": day1, "worker": hour_worker, "minutes": 2000, "remark": "超过一天"})
    check("超过 1440 分钟被拒", not ok(bad_hour2), bad_hour2.get("message"))

    status, hours_summary = call("GET", "/performance/hours/summary?period=" + PERIOD, admin)
    summary_rows = (hours_summary.get("data") or {}).get("rows") or []
    mine = next((row for row in summary_rows if row.get("worker") == hour_worker), None)
    check("工时汇总按人累加", mine is not None and num(mine.get("minutes")) == 600, mine)
    check("工时汇总标出已填人数", int(num((hours_summary.get("data") or {}).get("filled"))) >= 1,
          hours_summary.get("data"))

    if ok(h2):
        status, deleted = call("DELETE", "/performance/hours/%d" % h2["data"]["id"], admin)
        check("工时明细可删除", ok(deleted), deleted.get("message"))

    # 重新生成绩效：实际工时应该取工时表的合计（480），并标明来源
    status, regenerated2 = call("POST", "/performance/generate", admin, {"period": PERIOD})
    check("填完工时后重新生成成功", ok(regenerated2), regenerated2.get("message"))
    status, sheets2 = call("GET", "/performance/sheets?period=" + PERIOD + "&size=100", admin)
    row_now = next((row for row in ((sheets2.get("data") or {}).get("records") or [])
                    if row.get("worker") == hour_worker), None)
    check("绩效实际工时 = 工时表合计", row_now is not None and num(row_now.get("actualMinutes")) == 480,
          row_now and row_now.get("actualMinutes"))
    check("工时来源标注为工时表", row_now is not None and row_now.get("minutesSource") == "HOUR_SHEET",
          row_now and row_now.get("minutesSource"))

    # 批量填报：班组长一次给一个班组记同样时长
    batch_workers = [hour_worker, "回归批量工A", "回归批量工B"]
    status, batch = call("POST", "/performance/hours/batch", admin, {
        "workDate": day2, "minutes": 60, "workers": batch_workers, "remark": "回归-批量"
    })
    check("批量填报一次给多人记工时",
          ok(batch) and int(num((batch.get("data") or {}).get("count"))) == 3, batch.get("message"))
    status, batch_dup = call("POST", "/performance/hours/batch", admin, {
        "workDate": day2, "minutes": 60, "workers": [hour_worker, hour_worker], "remark": "回归-批量去重"
    })
    check("批量填报里重复的人只记一次",
          ok(batch_dup) and int(num((batch_dup.get("data") or {}).get("count"))) == 1,
          batch_dup.get("message"))
    status, batch_empty = call("POST", "/performance/hours/batch", admin,
                              {"workDate": day2, "minutes": 60, "workers": []})
    check("批量填报必须选人", not ok(batch_empty), batch_empty.get("message"))
    status, batch_bad = call("POST", "/performance/hours/batch", admin,
                             {"workDate": day2, "minutes": 0, "workers": ["回归批量工A"]})
    check("批量填报里有人不合法整批被拒", not ok(batch_bad), batch_bad.get("message"))

    # 清掉批量写的记录，避免影响下一次运行的合计断言
    status, all_hours = call("GET", "/performance/hours?period=" + PERIOD, admin)
    batch_rows = [row for row in ((all_hours.get("data") or {}).get("rows") or [])
                  if str(row.get("remark") or "").startswith("回归-批量")]
    for row in batch_rows:
        call("DELETE", "/performance/hours/%d" % row["id"], admin)
    check("批量写入的工时已清理", len(batch_rows) >= 3, len(batch_rows))

section("绩效进统一审批流")
flow_code = "PERF" + PERIOD.replace("-", "")
status, flow_created = call("POST", "/approvals/flows", admin, {
    "code": flow_code, "name": "绩效审核（回归）", "bizType": "PERFORMANCE", "enabled": True,
    "remark": "e2e-performance 回归脚本创建",
    "nodes": [{"seq": 1, "name": "绩效审核", "approverType": "ROLE",
               "approverValues": ["PLANNER"], "multiMode": "ANY", "remark": ""}]
})
flow_id = (flow_created.get("data") or {}).get("id") if isinstance(flow_created.get("data"), dict) else None
if not ok(flow_created):
    # 上一次跑挂在中途没清理时会撞编码唯一键：查出来复用（顺带把停用过的重新启用）
    _, flows = call("GET", "/approvals/flows", admin)
    for item in (flows.get("data") or []) if isinstance(flows.get("data"), list) else []:
        if item.get("code") == flow_code:
            flow_id = item.get("id")
            if not item.get("enabled"):
                call("POST", "/approvals/flows/%d/toggle" % flow_id, admin, {"enabled": True})
            flow_created = {"code": 0}
            break
check("绩效审批流可用", ok(flow_created), flow_created.get("message"))

draft_flow = pick_draft(hour_worker)
if draft_flow is None:
    check("有草稿绩效单可走审批", False, "本期没有草稿行")
else:
    sheet_id = draft_flow["id"]
    status, submitted_flow = call("POST", "/performance/sheets/%d/submit" % sheet_id, admin)
    check("提交后进入审批", ok(submitted_flow), submitted_flow.get("message"))
    flow_data = submitted_flow.get("data") or {}
    check("绩效单带上审批实例", flow_data.get("approvalInstanceId") is not None,
          flow_data.get("approvalInstanceId"))
    check("提交后状态为已提交", flow_data.get("status") == "SUBMITTED", flow_data.get("status"))

    status, manual = call("POST", "/performance/sheets/%d/approve" % sheet_id, admin)
    check("进审批后不能在绩效页直接审核", not ok(manual), manual.get("message"))

    # 撤回重算要连审批实例一起作废，不然实例会一直挂在审批中心
    status, reopened_flow = call("POST", "/performance/sheets/%d/reopen" % sheet_id, admin,
                                 {"reason": "回归：撤回重算"})
    check("审批中的绩效单能撤回", ok(reopened_flow), reopened_flow.get("message"))
    check("撤回后审批实例已作废",
          (reopened_flow.get("data") or {}).get("approvalInstanceId") is None,
          (reopened_flow.get("data") or {}).get("approvalInstanceId"))

    status, resubmitted = call("POST", "/performance/sheets/%d/submit" % sheet_id, admin)
    check("撤回后可以重新提交", ok(resubmitted), resubmitted.get("message"))
    flow_data = resubmitted.get("data") or {}
    check("重新提交又生成了审批实例", flow_data.get("approvalInstanceId") is not None,
          flow_data.get("approvalInstanceId"))

    instance_id = flow_data.get("approvalInstanceId")
    task = find_task(planner, instance_id) if instance_id else None
    check("计划能在审批中心看到绩效待办", task is not None, instance_id)
    if task:
        status, approved_flow = call("POST", "/approvals/tasks/%s/approve" % task["id"], planner,
                                     {"comment": "本月绩效核对无误"})
        check("审批中心通过绩效单", ok(approved_flow), approved_flow.get("message"))
        _, after_flow = call("GET", "/performance/sheets/%d" % sheet_id, admin)
        check("审批通过后绩效单变成已通过",
              (after_flow.get("data") or {}).get("status") == "APPROVED",
              (after_flow.get("data") or {}).get("status"))

if flow_id:
    # 已有审批记录的流程删不掉，只能停用；下轮靠上面的复用分支重新启用
    status, flow_disabled = call("POST", "/approvals/flows/%d/toggle" % flow_id, admin,
                                 {"enabled": False})
    check("回归用的审批流已停用", ok(flow_disabled), flow_disabled.get("message"))

section("边界")
status, bad = call("POST", "/performance/generate", admin, {"period": "2026-13"})
check("非法期间被拒", not ok(bad), bad.get("message"))
status, forbidden = call("GET", "/performance/sheets", warehouse)
check("仓库无权查看绩效", not ok(forbidden) or status == 403, (status, forbidden.get("message")))
status, forbidden2 = call("POST", "/performance/generate", warehouse, {"period": PERIOD})
check("仓库无权生成绩效", not ok(forbidden2) or status == 403, (status, forbidden2.get("message")))
status, missing = call("GET", "/performance/sheets/99999999", admin)
check("不存在的绩效单报错", not ok(missing), missing.get("message"))

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
