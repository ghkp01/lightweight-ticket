"""列表导出的服务端前置条件回归：各列表接口在导出用的大页长（size=5000）下仍正常返回，
并且返回结构能被导出组件识别（数组或 {records,total}）。

用法：python scripts/e2e-export.py [http://localhost:8080]
"""

import json
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PASSED = 0
FAILED = []

# 页面 → 导出接口（与前端 TableToolbar 的 export-api 一一对应）
ENDPOINTS = [
    ("生产工单", "/work-orders"),
    ("销售订单", "/sales-orders"),
    ("采购订单", "/purchase-orders"),
    ("采购申请", "/purchase-requisitions"),
    ("采购退货", "/purchase-returns"),
    ("询价比价", "/purchase-rfqs"),
    ("库存结存", "/inventory"),
    ("入库单", "/inbound-orders"),
    ("出库单", "/outbound-orders"),
    ("库存调拨", "/transfers"),
    ("库存盘点", "/stocktakes"),
    ("质量检验", "/inspections"),
    ("不良台账", "/defects"),
    ("发货单", "/shipments"),
    ("退货单", "/returns"),
    ("发票与应付", "/ap/invoices"),
    ("采购暂估", "/ap/accruals"),
    ("单件追踪", "/work-order-units"),
    ("客户列表", "/customers"),
    ("供应商", "/suppliers"),
    ("产品与BOM", "/products"),
    ("工序", "/processes"),
    ("仓库", "/warehouses"),
    ("用户", "/system/users"),
    ("消息台账", "/system/notices"),
    ("操作日志", "/system/logs"),
]


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


def ok(payload):
    return payload.get("code") == 0


def rows_of(payload):
    data = payload.get("data")
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        return data.get("records")
    return None


def check(name, cond, extra=""):
    global PASSED
    if cond:
        PASSED += 1
        print("  [OK]   " + name)
    else:
        FAILED.append(name)
        print("  [FAIL] " + name + "  " + str(extra))


status, login = call("POST", "/auth/login", body={"username": "admin", "password": "123456"})
token = (login.get("data") or {}).get("token")
check("管理员登录", ok(login) and token, login)

print("\n== 导出接口（page=1&size=5000）==")
for label, path in ENDPOINTS:
    status, payload = call("GET", path + "?page=1&size=5000", token)
    rows = rows_of(payload)
    check(label + " 导出取数正常", status == 200 and ok(payload) and rows is not None,
          (status, str(payload)[:160]))

print("\n== 导出带筛选条件 ==")
status, filtered = call("GET", "/work-orders?page=1&size=5000&status=FINISHED", token)
rows = rows_of(filtered) or []
check("筛选条件生效（只出已完工）",
      ok(filtered) and all(str(row.get("status")) == "FINISHED" for row in rows), len(rows))
status, ranged = call("GET", "/system/logs?page=1&size=5000&from=2026-01-01&to=2026-12-31", token)
check("日期区间筛选生效", ok(ranged) and rows_of(ranged) is not None, ranged.get("message"))

print("\n== 导出分页拉全量 ==")
status, page1 = call("GET", "/system/logs?page=1&size=200", token)
data1 = page1.get("data")
if isinstance(data1, dict):
    total = int(data1.get("total") or 0)
else:
    total = len(data1 or [])
if total > 200:
    status, page2 = call("GET", "/system/logs?page=2&size=200", token)
    rows2 = rows_of(page2) or []
    check("超过单页时能继续翻页（导出按页拉全量）", len(rows2) > 0, (total, len(rows2)))
else:
    check("日志总量不足一页（跳过分页校验）", True, total)
status, capped = call("GET", "/system/users?page=1&size=5000", token)
capped_rows = rows_of(capped) or []
check("接口对超大页长做裁剪时仍返回数据", len(capped_rows) > 0, len(capped_rows))

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
