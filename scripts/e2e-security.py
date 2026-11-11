"""登录安全端到端回归：密码策略、失败锁定与解锁、刷新令牌轮换、退出撤销。

用法：python scripts/e2e-security.py [http://localhost:8080]
"""

import json
import sys
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080") + "/api"
PASSED = 0
FAILED = []
TAG = str(int(time.time()))[-6:]

USER = "sec" + TAG
PHONE = "137" + TAG + "0"
STRONG = "Sec@123456"


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


status, login = call("POST", "/auth/login", body={"username": "admin", "password": "123456"})
admin = (login.get("data") or {}).get("token")
admin_refresh = (login.get("data") or {}).get("refreshToken")
check("管理员登录", ok(login) and admin, login)

section("刷新令牌")
check("登录返回刷新令牌与有效期",
      bool(admin_refresh) and (login.get("data") or {}).get("expiresIn", 0) > 0, login.get("data"))
status, refreshed = call("POST", "/auth/refresh", body={"refreshToken": admin_refresh})
new_token = (refreshed.get("data") or {}).get("token")
new_refresh = (refreshed.get("data") or {}).get("refreshToken")
check("刷新得到新访问令牌", ok(refreshed) and bool(new_token), refreshed)
status, me = call("GET", "/auth/me", new_token)
check("新令牌可访问接口", ok(me), me)
status, reused = call("POST", "/auth/refresh", body={"refreshToken": admin_refresh})
check("旧刷新令牌不可重复使用", reused.get("code") == 4022, reused)
status, logout = call("POST", "/auth/logout", new_token, {"refreshToken": new_refresh})
check("退出登录成功", ok(logout), logout)
status, revoked = call("POST", "/auth/refresh", body={"refreshToken": new_refresh})
check("退出后刷新令牌失效", revoked.get("code") == 4022, revoked)
status, bad_refresh = call("POST", "/auth/refresh", body={"refreshToken": "not-a-token"})
check("非法刷新令牌被拒", bad_refresh.get("code") == 4022, bad_refresh)

section("密码策略")
_, weak = call("POST", "/system/users", admin, {
    "username": USER + "w", "password": "123456", "realName": "弱密码",
    "role": "QC", "enabled": True
})
check("新建用户弱密码被拒", weak.get("code") == 1002, weak)
_, short = call("POST", "/system/users", admin, {
    "username": USER + "s", "password": "Ab1@", "realName": "太短",
    "role": "QC", "enabled": True
})
check("长度不足被拒", short.get("code") == 1002 and "8" in str(short.get("message")), short)

section("失败锁定与解锁")
status, created = call("POST", "/system/users", admin, {
    "username": USER, "password": STRONG, "realName": "安全测试" + TAG,
    "phone": PHONE[:11], "role": "QC", "enabled": True
})
check("新建用户强密码通过", ok(created), created)
status, first = call("POST", "/auth/login", body={"username": USER, "password": "Wrong@1"})
check("密码错误提示剩余次数",
      first.get("code") == 4001 and "还可尝试" in str(first.get("message")), first)
locked_code = None
for _ in range(6):
    status, attempt = call("POST", "/auth/login", body={"username": USER, "password": "Wrong@1"})
    if attempt.get("code") == 4021:
        locked_code = attempt
        break
check("连续失败达到上限后锁定", locked_code is not None, locked_code)
status, locked_login = call("POST", "/auth/login", body={"username": USER, "password": STRONG})
check("锁定期间正确密码也进不去",
      locked_login.get("code") == 4021 and "锁定" in str(locked_login.get("message")), locked_login)

_, users = call("GET", "/system/users?keyword=" + USER, admin)
rows = (users.get("data") or {}).get("records") or []
row = rows[0] if rows else {}
check("用户列表标出锁定状态", row.get("locked") is True and row.get("lockedUntil"), row)
check("用户列表带失败次数", row.get("failedAttempts") is not None, row)

status, unlocked = call("POST", f"/system/users/{row.get('id')}/unlock", admin)
check("管理员解锁成功", ok(unlocked), unlocked)
status, relogin = call("POST", "/auth/login", body={"username": USER, "password": STRONG})
check("解锁后可正常登录", ok(relogin) and (relogin.get("data") or {}).get("token"), relogin)

section("改密校验")
token = (relogin.get("data") or {}).get("token")
status, weak_change = call("PUT", "/auth/password",
                          token, {"oldPassword": STRONG, "newPassword": "abcdefgh"})
check("改密弱密码被拒", weak_change.get("code") == 1002, weak_change)
status, good_change = call("PUT", "/auth/password",
                           token, {"oldPassword": STRONG, "newPassword": "Sec@654321"})
check("改密强密码通过", ok(good_change), good_change)
status, new_login = call("POST", "/auth/login", body={"username": USER, "password": "Sec@654321"})
check("新密码可登录", ok(new_login), new_login)

section("清理测试账号")
call("PUT", f"/system/users/{row.get('id')}", admin, {
    "realName": "安全测试" + TAG, "phone": PHONE[:11], "role": "QC", "enabled": False
})

print("\n通过 %d 项，失败 %d 项" % (PASSED, len(FAILED)))
if FAILED:
    print("失败项：" + "、".join(FAILED))
    sys.exit(1)
