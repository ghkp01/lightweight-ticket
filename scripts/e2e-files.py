# -*- coding: utf-8 -*-
"""
统一附件与操作日志回归：
1. 存储配置读取（LOCAL / 上限 / 目录）
2. 统一上传接口：台账字段、摘要、业务归属
3. 台账按业务查询、下载内容一致、未登录下载被拒
4. 空文件与可执行脚本被拒
5. 删除权限（质检可传不可删）与删除后不可下载
6. 切换到未配置的 S3 时上传明确报错，切回本地恢复
7. 操作日志：上传/删除留痕、模块筛选、失败也记、无权限看不到
用法：python scripts/e2e-files.py [base_url]
"""
import hashlib
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import os

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
STAMP = time.strftime("%H%M%S")
PASSED = 0
FAILED = []
CREATED = []


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


def raw(method, path, token=None, headers=None):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.read(), dict(resp.headers), resp.status
    except urllib.error.HTTPError as e:
        return e.read(), dict(e.headers), e.code


def upload(filename, content, token, biz_type=None, biz_id=None, biz_no=None,
           content_type="application/octet-stream"):
    boundary = "----mes" + uuid.uuid4().hex
    params = {}
    if biz_type:
        params["bizType"] = biz_type
    if biz_id is not None:
        params["bizId"] = biz_id
    if biz_no:
        params["bizNo"] = biz_no
    url = BASE + "/files"
    if params:
        url += "?" + urllib.parse.urlencode(params)

    body = b""
    body += ("--%s\r\n" % boundary).encode()
    body += ('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % filename).encode("utf-8")
    body += ("Content-Type: %s\r\n\r\n" % content_type).encode()
    body += content
    body += ("\r\n--%s--\r\n" % boundary).encode()

    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "multipart/form-data; boundary=" + boundary)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
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


def q(value):
    return urllib.parse.quote(str(value))


def main():
    admin = login("admin")
    qc = login("qc")
    if not admin or not qc:
        print("登录失败，请确认服务已启动且演示账号存在")
        sys.exit(1)

    # 先清掉上一轮可能残留的测试附件（bizId 990001-990003 是回归专用号段）
    for biz_id in (990001, 990002, 990003):
        for biz_type in ("DEFECT", "INSPECTION"):
            leftover = call("GET", "/files?bizType=%s&bizId=%s" % (biz_type, biz_id), admin).get("data") or []
            for row in leftover:
                call("DELETE", "/files/%s" % row.get("id"), admin)

    print("== 存储配置 ==")
    storage = call("GET", "/files/storage", admin).get("data") or {}
    check("存储方式默认本地", storage.get("provider") == "LOCAL", storage)
    check("单文件上限 20MB", storage.get("maxSizeMb") == 20, storage)
    check("本地目录有值", bool(storage.get("localRoot")), storage)

    configs = call("GET", "/system/configs", admin).get("data") or []
    provider = next((c for c in configs if c["key"] == "storage.provider"), None)
    check("配置页能看到存储方式", provider is not None and provider.get("options") == "LOCAL,S3", provider)
    secret = next((c for c in configs if c["key"] == "storage.s3.secret-key"), None)
    check("密文配置不回传真实值", secret is not None and secret.get("value") in ("", "******"), secret)

    print("== 统一上传 ==")
    content = ("MES 附件回归 " + STAMP).encode("utf-8")
    digest = hashlib.sha256(content).hexdigest()
    biz_no = "D-E2E-" + STAMP
    res = upload("检验报告.txt", content, admin, "DEFECT", 990001, biz_no, "text/plain")
    record = res.get("data") or {}
    check("上传成功", ok(res), res)
    file_no = record.get("fileNo") or ""
    check("生成台账号", file_no.startswith("F"), file_no)
    check("写入本地存储", record.get("storageType") == "LOCAL", record)
    check("文件名保留中文", record.get("originalName") == "检验报告.txt", record.get("originalName"))
    check("大小与内容一致", record.get("sizeBytes") == len(content), record.get("sizeBytes"))
    check("摘要为 sha256", record.get("sha256") == digest, record.get("sha256"))
    check("业务归属正确", record.get("bizType") == "DEFECT" and record.get("bizNo") == biz_no, record)
    check("返回下载地址", str(record.get("url", "")).endswith("/download"), record.get("url"))
    file_id = record.get("id")
    CREATED.append(file_id)

    listed = call("GET", "/files?bizType=DEFECT&bizId=990001", admin).get("data") or []
    check("台账按业务查得到", len(listed) == 1 and listed[0].get("id") == file_id, listed)

    data, headers, status = raw("GET", "/files/%s/download" % file_id, admin)
    check("下载内容一致", data == content, data[:40])
    disposition = headers.get("Content-Disposition", "")
    check("下载带附件名", "attachment" in disposition and "filename*=UTF-8" in disposition, disposition)

    _, _, anon_status = raw("GET", "/files/%s/download" % file_id, None)
    check("未登录不能下载", anon_status == 401, anon_status)

    print("== 上传校验 ==")
    empty = upload("空文件.txt", b"", admin, "DEFECT", 990001)
    check("空文件被拒", empty.get("code") == 1002, empty)
    script = upload("virus.exe", b"MZ", admin, "DEFECT", 990001)
    check("可执行脚本被拒", script.get("code") == 1002 and "exe" in str(script.get("message")), script)

    print("== 权限 ==")
    qc_upload = upload("质检照片.txt", ("QC " + STAMP).encode(), qc, "INSPECTION", 990002, "QI-E2E-" + STAMP)
    check("质检可以上传", ok(qc_upload), qc_upload)
    qc_file_id = (qc_upload.get("data") or {}).get("id")
    CREATED.append(qc_file_id)
    qc_delete = call("DELETE", "/files/%s" % qc_file_id, qc)
    check("质检不能删除", qc_delete.get("code") == 403 or qc_delete.get("_http") == 403, qc_delete)

    print("== 删除 ==")
    deleted = call("DELETE", "/files/%s" % file_id, admin)
    check("管理员删除成功", ok(deleted), deleted)
    gone = call("GET", "/files/%s" % file_id, admin)
    check("台账标记删除", gone.get("code") == 4040, gone)
    after = call("GET", "/files?bizType=DEFECT&bizId=990001", admin).get("data") or []
    check("删除后列表不再出现", len(after) == 0, after)

    print("== 存储切换 ==")
    invalid = call("PUT", "/system/configs/storage.provider", admin, {"value": "FTP"})
    check("非法存储方式被拒", invalid.get("code") == 1002, invalid)

    call("PUT", "/system/configs/storage.provider", admin, {"value": "S3"})
    call("PUT", "/system/configs/storage.s3.endpoint", admin, {"value": ""})
    s3_fail = upload("切到S3.txt", b"switch", admin, "DEFECT", 990003)
    check("S3 未配置时明确报错", s3_fail.get("code") == 5011 and "storage.s3.endpoint" in str(s3_fail.get("message")), s3_fail)

    call("PUT", "/system/configs/storage.provider", admin, {"value": "LOCAL"})
    back = upload("切回本地.txt", ("back " + STAMP).encode(), admin, "DEFECT", 990003)
    check("切回本地后可上传", ok(back) and (back.get("data") or {}).get("storageType") == "LOCAL", back)
    CREATED.append((back.get("data") or {}).get("id"))

    test = call("POST", "/files/storage/test", admin)
    check("存储自检可用", ok(test) and "可写" in str((test.get("data") or {}).get("message")), test)

    print("== 操作日志 ==")
    logs = call("GET", "/system/logs?module=" + q("附件") + "&limit=50", admin).get("data") or []
    check("附件操作有日志", len(logs) > 0, logs)
    upload_log = next((row for row in logs if row.get("action") == "上传" and "检验报告.txt" in str(row.get("summary"))), None)
    check("上传日志带摘要与单号", upload_log is not None and upload_log.get("bizNo") == biz_no, upload_log)
    check("上传日志记录操作人", upload_log is not None and upload_log.get("operator") == "admin", upload_log)
    check("上传日志成功状态", upload_log is not None and upload_log.get("success") is True, upload_log)

    fail_logs = call("GET", "/system/logs?module=" + q("附件") + "&success=false&limit=50", admin).get("data") or []
    check("失败操作也留痕", len(fail_logs) > 0, fail_logs)

    options = call("GET", "/system/logs/options", admin).get("data") or {}
    check("日志下拉包含附件模块", "附件" in (options.get("modules") or []), options)
    check("日志统计有累计数", int(options.get("total") or 0) > 0, options)

    qc_logs = call("GET", "/system/logs", qc)
    check("无权限看不到操作日志", qc_logs.get("code") == 403 or qc_logs.get("_http") == 403, qc_logs)

    print("== 清理 ==")
    for item in CREATED:
        if item:
            call("DELETE", "/files/%s" % item, admin)
    left = call("GET", "/files?bizType=DEFECT&bizId=990003", admin).get("data") or []
    check("测试附件已清理", len(left) == 0, left)

    print()
    print("通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
    if FAILED:
        print("失败项：" + " / ".join(FAILED))
        sys.exit(1)


if __name__ == "__main__":
    main()
