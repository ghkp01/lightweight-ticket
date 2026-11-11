"""基础数据导入导出端到端回归：模板下载、产品/客户/BOM/期初库存导入（含预览与坏行回报）、
导出回读、越权拦截。

用法：python scripts/e2e-data.py [http://localhost:8080]
"""

import json
import os
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PASSED = 0
FAILED = []
BOUNDARY = "----mes-data-e2e"


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


def download(path, token):
    req = urllib.request.Request(BASE + path)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, resp.read().decode("utf-8-sig", "ignore")
    except urllib.error.HTTPError as err:
        return err.code, ""


def upload(path, token, filename, content):
    body = bytearray()
    body.extend(("--" + BOUNDARY + "\r\n").encode())
    body.extend(('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % filename).encode())
    body.extend(b"Content-Type: text/csv\r\n\r\n")
    body.extend(content.encode("utf-8"))
    body.extend(("\r\n--" + BOUNDARY + "--\r\n").encode())
    req = urllib.request.Request(BASE + path, data=bytes(body), method="POST")
    req.add_header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as err:
        return json.loads(err.read().decode())


def main():
    token = login("admin")
    if not token:
        print("登录失败，请确认服务已启动")
        sys.exit(1)
    suffix = str(int.from_bytes(os.urandom(2), "big"))

    section("1. 模板下载")
    status, product_tpl = download("/data-exchange/templates/product", token)
    check("产品模板可下载", status == 200, status)
    check("模板表头正确", product_tpl.startswith("产品编码,产品名称,规格,类别,单位"), product_tpl[:40])
    check("模板带单件管理列", "单件管理" in product_tpl)
    bad_status, bad_text = download("/data-exchange/templates/unknown", token)
    check("未知类型模板被拒", bad_status >= 400 or "1002" in bad_text, (bad_status, bad_text[:60]))

    section("2. 产品导入（预览 + 正式）")
    code = "E2E" + suffix
    csv_text = "产品编码,产品名称,规格,类别,单位,销售价,成本,安全库存,损耗率,单件管理\r\n"
    csv_text += "%s,端到端导入件,10x20,成品,件,12.5,8,3,0.02,否\r\n" % code
    csv_text += "%s-RAW,端到端导入料,,原料,kg,5,4,100,0,否\r\n" % code
    csv_text += "BAD%s,坏行,,不存在类别,件,1,1,0,0,否\r\n" % suffix

    preview = upload("/data-exchange/import/product?preview=true", token, "product.csv", csv_text)
    check("预览接口可用", ok(preview), preview.get("message"))
    preview_data = preview.get("data") or {}
    check("预览统计总行数 3", int(preview_data.get("total") or 0) == 3, preview_data)
    check("预览识别 1 行失败", int(preview_data.get("failed") or 0) == 1, preview_data)
    check("预览不落库", (call("GET", "/products?keyword=" + code, token).get("data") or []) == [], "已落库")

    imported = upload("/data-exchange/import/product", token, "product.csv", csv_text)
    data = imported.get("data") or {}
    check("正式导入成功 2 行", ok(imported) and int(data.get("success") or 0) == 2, data)
    check("导入回报新增 2 条", int(data.get("created") or 0) == 2, data)
    check("导入回报失败行号与原因",
          bool(data.get("errors")) and "类别" in str(data["errors"][0].get("message")),
          data.get("errors"))
    products = call("GET", "/products?keyword=" + code, token).get("data") or []
    check("产品已存在", len(products) >= 2, len(products))

    section("3. 导出回读")
    status, exported = download("/data-exchange/export/product", token)
    check("产品可导出", status == 200, status)
    check("导出表头与模板一致", exported.startswith("产品编码,产品名称"), exported[:30])
    check("导出包含刚导入的产品", code in exported)

    section("4. BOM 与工艺路线导入")
    bom_csv = "父件编码,子件编码,用量,工序号,替代组,是否关键件,备注\r\n"
    bom_csv += "%s,%s-RAW,2.5,1,,是,端到端\r\n" % (code, code)
    bom_csv += "%s,NOT-EXIST-%s,1,1,,否,\r\n" % (code, suffix)
    bom = upload("/data-exchange/import/bom", token, "bom.csv", bom_csv)
    bom_data = bom.get("data") or {}
    check("BOM 导入成功 1 行", ok(bom) and int(bom_data.get("success") or 0) == 1, bom_data)
    check("BOM 缺子件被拦", int(bom_data.get("failed") or 0) == 1, bom_data)

    process_code = None
    for item in call("GET", "/processes", token).get("data") or []:
        process_code = item.get("code")
        break
    check("有可用工序", bool(process_code))
    if process_code:
        routing_csv = "产品编码,工序号,工序编码,单件工时,计件单价,是否检验,备注\r\n"
        routing_csv += "%s,1,%s,1.5,1.2,否,端到端\r\n" % (code, process_code)
        routing = upload("/data-exchange/import/routing", token, "routing.csv", routing_csv)
        check("工艺路线导入成功", ok(routing) and int((routing.get("data") or {}).get("success") or 0) == 1, routing)

    section("5. 期初库存导入")
    warehouses = call("GET", "/warehouses", token).get("data") or []
    warehouse = warehouses[0] if warehouses else None
    check("有可用仓库", warehouse is not None)
    if warehouse:
        inv_csv = "物料编码,仓库编码,数量,批次号\r\n"
        inv_csv += "%s-RAW,%s,50,%s\r\n" % (code, warehouse["code"], "B" + suffix)
        inv = upload("/data-exchange/import/inventory", token, "inventory.csv", inv_csv)
        check("期初库存导入成功", ok(inv) and int((inv.get("data") or {}).get("success") or 0) == 1, inv)

    section("6. 越权与非法文件")
    warehouse_token = login("warehouse")
    denied = upload("/data-exchange/import/product", warehouse_token, "p.csv", csv_text)
    check("仓库账号导入被拒", denied.get("code") not in (0, None), denied.get("message"))
    status, _ = download("/data-exchange/export/product", warehouse_token)
    check("仓库账号导出被拒", status == 403, status)
    empty = upload("/data-exchange/import/product", token, "p.csv", "产品编码,产品名称\r\n")
    check("缺列文件被拒(1002)", empty.get("code") == 1002, empty.get("message"))

    print("\n" + ("全部通过：%d 项" % PASSED if not FAILED else "失败 %d 项：%s" % (len(FAILED), FAILED)))
    if FAILED:
        sys.exit(1)


if __name__ == "__main__":
    main()
