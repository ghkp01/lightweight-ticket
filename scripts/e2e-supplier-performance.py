"""供应商绩效端到端回归：排行指标、明细构成、筛选、非法入参与越权拦截。

（原文件名 e2e-performance.py，与「工人绩效核算」重名，2026-10 改为现名）

用法：python scripts/e2e-supplier-performance.py [http://localhost:8080]
"""

import json
import sys
import urllib.error
import urllib.request
import os

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
    # 强制改密之后 admin 的口令可能已经变了，用 MES_PASSWORD 传入
    password = password or os.environ.get("MES_PASSWORD", "123456")
    status, res = call("POST", "/auth/login", body={"username": username, "password": password})
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


admin = login("admin")
check("管理员登录", admin is not None)

print("\n== 绩效排行 ==")
status, ranking = call("GET", "/supplier-performance", admin)
rows = ranking.get("data") or []
check("返回供应商绩效列表", status == 200 and ok(ranking) and len(rows) > 0, len(rows))
sample = rows[0] if rows else {}
check("列表带准时率 / 合格率 / 退货率",
      all(key in sample for key in ("onTimeRate", "passRate", "returnRate")), list(sample.keys())[:12])
check("列表带综合得分与等级", "score" in sample and "grade" in sample, (sample.get("score"), sample.get("grade")))
check("按采购金额倒序",
      all(float(rows[i].get("amount") or 0) >= float(rows[i + 1].get("amount") or 0)
          for i in range(len(rows) - 1)), "order")

with_data = [row for row in rows if row.get("hasData")]
check("至少一家有往来数据的供应商", len(with_data) > 0, len(rows))
target = with_data[0] if with_data else {}
score = target.get("score")
check("有数据时得分在 0~100", score is not None and 0 <= float(score) <= 100, score)
check("等级取 A/B/C/D", target.get("grade") in ("A", "B", "C", "D"), target.get("grade"))

print("\n== 单供应商明细 ==")
if target:
    status, detail = call("GET", f"/supplier-performance/{target.get('id')}", admin)
    data = detail.get("data") or {}
    check("明细返回汇总", ok(detail) and (data.get("summary") or {}).get("id") == target.get("id"),
          detail.get("message"))
    check("明细带价格趋势", isinstance(data.get("priceTrend"), list), type(data.get("priceTrend")))
    check("明细带物料分布", isinstance(data.get("materials"), list), type(data.get("materials")))
    check("明细带收货记录", isinstance(data.get("receipts"), list), type(data.get("receipts")))
    check("明细带来料检验", isinstance(data.get("inspections"), list), type(data.get("inspections")))
    trend = data.get("priceTrend") or []
    if trend:
        check("价格趋势行带月份与加权均价",
              trend[0].get("month") and trend[0].get("avgPrice") is not None, trend[0])

print("\n== 筛选与边界 ==")
if target:
    status, filtered = call("GET", f"/supplier-performance?supplierId={target.get('id')}", admin)
    filtered_rows = filtered.get("data") or []
    check("按供应商筛选只返回一家", len(filtered_rows) == 1, len(filtered_rows))

status, bad = call("GET", "/supplier-performance?from=2026-13-01", admin)
check("非法日期被拒", status >= 400 or bad.get("code") == 1002, bad)
status, reversed_range = call("GET", "/supplier-performance?from=2026-12-31&to=2026-01-01", admin)
check("开始晚于结束被拒", status >= 400 or reversed_range.get("code") == 1002, reversed_range)
status, missing = call("GET", "/supplier-performance/999999", admin)
check("不存在的供应商被拒", status >= 400 or missing.get("code") == 4001, missing)

sales = login("sales")
status, forbidden = call("GET", "/supplier-performance", sales)
check("销售无权查看供应商绩效", status == 403 or forbidden.get("code") not in (0, None),
      (status, forbidden))

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
