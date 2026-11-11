"""车间大屏回归：三块免登录接口能取到数据、字段齐全，且公开范围没有扩大。

用法：python scripts/e2e-screen.py [http://localhost:8080]
"""

import json
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


section("免登录访问三块大屏数据")
status, production = call("GET", "/public/screen/production")
check("生产屏 200", status == 200, status)
check("生产屏返回成功码", production.get("code") == 0, production.get("message"))
prod = production.get("data") or {}
check("生产屏带 KPI", isinstance(prod.get("kpi"), dict))
check("生产屏带工作中心负荷", isinstance(prod.get("workCenters"), list))
check("生产屏带在制工单", isinstance(prod.get("orders"), list))
check("生产屏带刷新时间", bool(prod.get("updatedAt")))
kpi = prod.get("kpi") or {}
for key in ("inProgress", "todayOutput", "todayQualified", "dueToday", "shortageOrders"):
    check("生产 KPI 含 " + key, key in kpi)
for row in (prod.get("orders") or [])[:3]:
    check("工单进度有百分比字段", "progress" in row, row.get("workNo"))
    # 进度按工序算（工单 done_qty 在制期间恒为 0，不能拿来算进度）
    check("工单进度带工序数", "opDone" in row and "opTotal" in row, row.get("workNo"))
    break

status, quality = call("GET", "/public/screen/quality")
check("质量屏 200", status == 200, status)
qual = quality.get("data") or {}
check("质量屏带 KPI", isinstance(qual.get("kpi"), dict))
check("质量屏带不良 TOP", isinstance(qual.get("defectTop"), list))
check("质量屏带最近检验", isinstance(qual.get("inspections"), list))
for key in ("todayInspections", "todayInspectQty", "todayDefectQty", "openDefects", "reworkOrders"):
    check("质量 KPI 含 " + key, key in (qual.get("kpi") or {}))

status, andon = call("GET", "/public/screen/andon")
check("异常屏 200", status == 200, status)
and_data = andon.get("data") or {}
check("异常屏带 KPI", isinstance(and_data.get("kpi"), dict))
check("异常屏带呼叫清单", isinstance(and_data.get("calls"), list))
for key in ("open", "acked", "resolvedToday", "avgAckMinutes"):
    check("异常 KPI 含 " + key, key in (and_data.get("kpi") or {}))

status, oee = call("GET", "/public/screen/oee")
check("设备屏 200", status == 200, status)
oee_data = oee.get("data") or {}
oee_kpi = oee_data.get("kpi") or {}
for key in ("oee", "availability", "performance", "quality", "centers", "reportQty"):
    check("设备 KPI 含 " + key, key in oee_kpi)
check("设备屏带分中心明细", isinstance(oee_data.get("centers"), list))
for row in (oee_data.get("centers") or [])[:2]:
    check("分中心含三项分解", all(k in row for k in ("oee", "availability", "performance", "quality")),
          row.get("name"))
    break

section("公开范围没有扩大")
status, _ = call("GET", "/performance/sheets")
check("绩效接口仍需登录", status == 401, status)
status, _ = call("GET", "/workshop/board")
check("车间看板接口仍需登录", status == 401, status)

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
