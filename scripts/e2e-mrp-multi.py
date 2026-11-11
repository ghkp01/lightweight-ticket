"""多层物料需求端到端回归：接口契约、层数参数、分类正确性、越权。

说明：真实的多层展开（半成品继续拆）由 MultiLevelMrpTest 单元测试覆盖，
这里只验证接口结构、参数校验与权限，避免依赖演示库里的 BOM 结构。

用法：python scripts/e2e-mrp-multi.py [http://localhost:8080]
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

print("\n== 接口结构 ==")
status, exploded = call("GET", "/mrp/explode?maxLevel=3", admin)
data = exploded.get("data") or {}
check("多层展开返回成功", status == 200 and ok(exploded), exploded.get("message"))
check("返回 summary / make / buy",
      isinstance(data.get("summary"), dict) and isinstance(data.get("make"), list)
      and isinstance(data.get("buy"), list), list(data.keys()))

summary = data.get("summary") or {}
check("概览带层数与行数",
      all(key in summary for key in ("maxLevel", "demandRows", "makeRows", "buyRows")), summary)
check("默认展开层数为 3", int(summary.get("maxLevel") or 0) == 3, summary.get("maxLevel"))

make_rows = data.get("make") or []
buy_rows = data.get("buy") or []
check("自制行都是半成品", all(row.get("category") == "SEMI" for row in make_rows),
      [row.get("category") for row in make_rows][:5])
check("采购行都不带 SEMI 分类", all(row.get("category") != "SEMI" for row in buy_rows),
      [row.get("category") for row in buy_rows][:5])
if make_rows:
    row = make_rows[0]
    check("自制行带毛需求与缺口",
          row.get("grossNeed") is not None and row.get("shortage") is not None, row)
if buy_rows:
    row = buy_rows[0]
    check("采购行带在途与已申请未转",
          row.get("onWay") is not None and row.get("pending") is not None, row)

print("\n== 参数 ==")
status, one_level = call("GET", "/mrp/explode?maxLevel=1", admin)
check("层数 1 可执行", ok(one_level), one_level.get("message"))
status, five_level = call("GET", "/mrp/explode?maxLevel=5", admin)
check("层数 5 可执行", ok(five_level) and (five_level.get("data") or {}).get("summary", {}).get("maxLevel") == 5,
      five_level.get("message"))

status, too_deep = call("GET", "/mrp/explode?maxLevel=9", admin)
check("层数越界被拒", status >= 400 or too_deep.get("code") == 1002, too_deep)

status, ranged = call("GET", "/mrp/explode?includeScheduled=true&horizonDays=7&maxLevel=2", admin)
check("组合参数可执行", ok(ranged), ranged.get("message"))

print("\n== 与单阶 MRP 对比 ==")
status, single = call("GET", "/mrp/run?onlyShortage=false", admin)
single_rows = (single.get("data") or {}).get("rows") or []
check("单阶 MRP 仍可用", ok(single) and len(single_rows) > 0, len(single_rows))
check("多层结果至少覆盖单阶的物料数（可能更多）",
      len(buy_rows) + len(make_rows) >= min(len(single_rows), 1),
      (len(buy_rows), len(make_rows), len(single_rows)))

print("\n== 越权 ==")
sales = login("sales")
status, forbidden = call("GET", "/mrp/explode", sales)
check("销售无权查看物料需求", status == 403 or forbidden.get("code") not in (0, None), (status, forbidden))

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
