import json
import os
import threading
import time
import urllib.request
import urllib.error
from datetime import date, timedelta

BASE = os.environ.get("MES_BASE", "http://localhost:8080/api")
TODAY = date.today().isoformat()
DELIVERY = (date.today() + timedelta(days=7)).isoformat()

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
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8")
        try:
            return json.loads(text)
        except Exception:
            return {"code": e.code, "message": text}


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


def stock(code, token):
    data = call("GET", "/inventory?keyword=" + code, token=token)["data"]
    rows = data.get("records") if isinstance(data, dict) else data
    return sum(float(r["qty"]) for r in rows
               if r["productCode"] == code and int(r.get("warehouseId") or 0) == 1)


token = call("POST", "/auth/login", {"username": "admin", "password": "123456"})["data"]["token"]

print("== 1. 并发出库：库存不超发 ==")
current = stock("P005", token)
if current < 500:
    iid = call("POST", "/inbound-orders", {
        "type": "OTHER", "warehouseId": 1, "supplier": "压测补料", "needInspection": False,
        "noOrderReason": "压测脚本准备并发测试库存，无采购单",
        "items": [{"productId": 5, "qty": 500 - current}]
    }, token=token)["data"]
    call("POST", f"/inbound-orders/{iid}/submit", {}, token=token)
elif current > 500:
    oid = call("POST", "/outbound-orders", {
        "type": "MANUAL", "warehouseId": 1, "remark": "压减到 500 用于并发测试",
        "items": [{"productId": 5, "qty": current - 500}]
    }, token=token)["data"]
    call("POST", f"/outbound-orders/{oid}/complete", {}, token=token)
check("库存准备到 500", abs(stock("P005", token) - 500) < 0.01, stock("P005", token))

order_ids = []
for _ in range(20):
    oid = call("POST", "/outbound-orders", {
        "type": "MANUAL", "warehouseId": 1, "remark": "并发出库测试",
        "items": [{"productId": 5, "qty": 50}]
    }, token=token)["data"]
    order_ids.append(oid)

results = []
barrier = threading.Barrier(len(order_ids))


def complete_one(oid):
    barrier.wait()
    payload = call("POST", f"/outbound-orders/{oid}/complete", {}, token=token)
    results.append(payload)


start = time.time()
threads = [threading.Thread(target=complete_one, args=(oid,)) for oid in order_ids]
for thread in threads:
    thread.start()
for thread in threads:
    thread.join()
elapsed = time.time() - start

success = [r for r in results if r.get("code") == 0]
failed = [r for r in results if r.get("code") != 0]
final_stock = stock("P005", token)
print("    并发 %d 单，耗时 %.2fs，成功 %d，失败 %d，最终库存 %s"
      % (len(results), elapsed, len(success), len(failed), final_stock))
check("并发成功单数等于库存可支撑单数", len(success) == 10, len(success))
check("并发失败单被库存拦截", len(failed) == 10
      and all("库存不足" in str(r.get("message")) for r in failed),
      [r.get("message") for r in failed[:3]])
check("并发后库存不为负", final_stock >= 0 and abs(final_stock) < 0.01, final_stock)
check("成功出库总量等于库存", abs(len(success) * 50 - 500) < 0.01, len(success) * 50)

print("\n== 2. 并发报工：不超过计划数量 ==")
wid = call("POST", "/work-orders", {
    "productId": 1, "planQty": 100, "planStart": TODAY, "planEnd": DELIVERY
}, token=token)["data"]
call("POST", f"/scheduling/{wid}/schedule", {"date": TODAY}, token=token)
call("POST", f"/work-orders/{wid}/release", {}, token=token)
detail = call("GET", f"/work-orders/{wid}", token=token)["data"]
operation_id = detail["operations"][0]["id"]

report_results = []
barrier2 = threading.Barrier(20)


def report_once():
    barrier2.wait()
    payload = call("POST", f"/work-orders/{wid}/report", {
        "operationId": operation_id, "qty": 10, "qualifiedQty": 10, "defectQty": 0,
        "worker": "并发测试"
    }, token=token)
    report_results.append(payload)


start = time.time()
threads = [threading.Thread(target=report_once) for _ in range(20)]
for thread in threads:
    thread.start()
for thread in threads:
    thread.join()
elapsed = time.time() - start

report_success = [r for r in report_results if r.get("code") == 0]
report_failed = [r for r in report_results if r.get("code") != 0]
detail = call("GET", f"/work-orders/{wid}", token=token)["data"]
done = float(detail["operations"][0]["doneQty"])
print("    并发 20 次报工，耗时 %.2fs，成功 %d，失败 %d，完成数量 %s"
      % (elapsed, len(report_success), len(report_failed), done))
check("并发报工成功 10 次", len(report_success) == 10, len(report_success))
check("报工完成数量等于计划", abs(done - 100) < 0.01, done)
check("报工未超出计划", done <= 100.0, done)
check("超量报工被拒绝", len(report_failed) == 10
      and all(r.get("code") == 2010 for r in report_failed),
      [r.get("code") for r in report_failed[:3]])

print("\n== 3. 查询吞吐 ==")
latencies = []
errors = []


def query_loop(rounds):
    for _ in range(rounds):
        begin = time.time()
        payload = call("GET", f"/work-orders/{wid}", token=token)
        latencies.append((time.time() - begin) * 1000)
        if payload.get("code") != 0:
            errors.append(payload.get("message"))


start = time.time()
threads = [threading.Thread(target=query_loop, args=(20,)) for _ in range(20)]
for thread in threads:
    thread.start()
for thread in threads:
    thread.join()
elapsed = time.time() - start
latencies.sort()
count = len(latencies)
p50 = latencies[int(count * 0.5) - 1]
p95 = latencies[int(count * 0.95) - 1]
qps = count / elapsed if elapsed > 0 else 0
print("    请求 %d 次，耗时 %.2fs，QPS %.1f，P50 %.1fms，P95 %.1fms，最大 %.1fms"
      % (count, elapsed, qps, p50, p95, latencies[-1]))
check("查询无错误", len(errors) == 0, errors[:3])
check("P95 低于 1000ms", p95 < 1000, p95)

print()
print("================ 汇总 ================")
print("通过: %d 项" % PASS)
print("失败: %d 项" % len(FAILS))
for name in FAILS:
    print("  - " + name)
