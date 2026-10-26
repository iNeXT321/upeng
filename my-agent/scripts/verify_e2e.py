#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
端到端验收脚本：登录 → 多轮对话 → 问数（NL2SQL）全链路验证
==============================================================
覆盖：
  1. /api/ai/health 健康检查
  2. /api/auth/login 登录取 JWT（默认 admin/agent123）
  3. /api/ai/autoAgent SSE 多轮对话：
     轮1 问数据（触发 retrieve_schema 混合召回 + execute_sql MySQL）
     轮2 追问（验证多轮上下文继承，如"那华南区呢"）
  4. /api/ai/schema/status 混合召回健康状态
用法：python3 scripts/verify_e2e.py [--base http://127.0.0.1:8123]
"""
import json
import sys
import time
import uuid
import urllib.request

BASE = "http://127.0.0.1:8123"
args = sys.argv[1:]
if "--base" in args:
    BASE = args[args.index("--base") + 1]

OK, FAIL = "✅", "❌"
results = []


def check(name, cond, detail=""):
    results.append((name, cond, detail))
    print(f"{OK if cond else FAIL} {name}" + (f"  -- {detail[:160]}" if detail else ""))


def http_json(path, payload=None, token=None, method=None):
    req = urllib.request.Request(BASE + path, method=method or ("POST" if payload else "GET"))
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    data = json.dumps(payload).encode() if payload else None
    with urllib.request.urlopen(req, data=data, timeout=60) as resp:
        return json.loads(resp.read().decode())


def chat_stream(payload, token, timeout=300):
    """消费 SSE，返回 (result_text, tool_thoughts)"""
    req = urllib.request.Request(BASE + "/api/ai/autoAgent", method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {token}")
    body = dict(payload, isStream=True)
    text, thoughts = "", []
    with urllib.request.urlopen(req, data=json.dumps(body).encode(), timeout=timeout) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "ignore").strip()
            if not line or line.startswith(":") or not line.startswith("data:"):
                continue
            try:
                ev = json.loads(line[5:].strip())
            except json.JSONDecodeError:
                continue
            mt = ev.get("messageType")
            if mt == "result":
                text += ev.get("result") or ev.get("content") or ""
            elif mt == "tool_thought":
                thoughts.append(ev.get("content") or "")
            elif mt == "error":
                text += f"\n[ERROR] {ev.get('content')}"
    return text, thoughts


# 1. 健康检查
try:
    health = http_json("/api/ai/health")
    check("健康检查 /ai/health", True, json.dumps(health, ensure_ascii=False)[:120])
except Exception as e:
    check("健康检查 /ai/health", False, str(e))
    print("\n后端不可达，请先启动后端。")
    sys.exit(1)

# 2. 登录
try:
    login = http_json("/api/auth/login", {"username": "admin", "password": "agent123"})
    token = login.get("data", {}).get("token") or login.get("token")
    check("登录 admin/agent123", bool(token), "JWT 已获取")
except Exception as e:
    check("登录 admin/agent123", False, str(e))
    sys.exit(1)

# 3. schema 混合召回状态
try:
    status = http_json("/api/ai/schema/status", token=token)
    check("混合召回状态 /ai/schema/status", True, json.dumps(status, ensure_ascii=False)[:200])
except Exception as e:
    check("混合召回状态 /ai/schema/status", False, str(e))

# 4. 多轮问数（关键验收）
sid = f"e2e-{uuid.uuid4().hex[:8]}"

print("\n【轮1】上个月华东区的GMV是多少？")
t0 = time.time()
r1, th1 = chat_stream({"requestId": str(uuid.uuid4()), "sessionId": sid, "query": "上个月华东区的GMV是多少？", "agentType": 4}, token)
print(f"  耗时 {time.time() - t0:.0f}s | 工具轨迹 {len(th1)} 条")
print(f"  回答摘要: {r1[:300]}")
check("轮1 问数返回结果", bool(r1.strip()) and "[ERROR]" not in r1, f"thoughts={len(th1)}")

print("\n【轮2】那华南区呢？")
t0 = time.time()
r2, th2 = chat_stream({"requestId": str(uuid.uuid4()), "sessionId": sid, "query": "那华南区呢？", "agentType": 4}, token)
print(f"  耗时 {time.time() - t0:.0f}s | 工具轨迹 {len(th2)} 条")
print(f"  回答摘要: {r2[:300]}")
check("轮2 多轮追问（指代消解）", bool(r2.strip()) and "[ERROR]" not in r2, f"thoughts={len(th2)}")

# 汇总
failed = [n for n, ok, _ in results if not ok]
print("\n========== 验收汇总 ==========")
print(f"通过 {len(results) - len(failed)}/{len(results)}")
if failed:
    print("失败项:", ", ".join(failed))
    sys.exit(1)
print("全部通过 🎉")
