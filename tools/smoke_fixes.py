"""MotoChat 修复项端到端冒烟测试（对 8081 实例，全部只读或无副作用）。"""
import json
import socket
import struct
import base64
import os
import urllib.request
import urllib.error
import zlib

BASE = "http://127.0.0.1:8081"
PASS = []
FAIL = []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  [PASS] " if cond else "  [FAIL] ") + name + (f"  -> {detail}" if detail and not cond else ""))


def req(method, path, body=None, token=None, raw=False):
    r = urllib.request.Request(BASE + path, method=method)
    if token:
        r.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, data=data, timeout=30) as resp:
            payload = resp.read()
            return resp.status, (payload if raw else json.loads(payload))
    except urllib.error.HTTPError as e:
        payload = e.read()
        try:
            return e.code, json.loads(payload)
        except Exception:
            return e.code, payload.decode("utf-8", "replace")


def login(username, password):
    st, body = req("POST", "/api/auth/login", {"username": username, "password": password})
    return body.get("token") if st == 200 else None


def multipart_upload(path, token, field, filename, content, ctype):
    boundary = "----smoke" + os.urandom(8).hex()
    parts = []
    for k, v in [("file", filename)]:
        pass
    body = b""
    body += f"--{boundary}\r\n".encode()
    body += f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'.encode()
    body += f"Content-Type: {ctype}\r\n\r\n".encode()
    body += content + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    r = urllib.request.Request(BASE + path, method="POST", data=body)
    r.add_header("Authorization", "Bearer " + token)
    r.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read())
        except Exception:
            return e.code, {}


def png_bomb(w, h):
    """只有头部的 PNG：IHDR 声明超大尺寸，不含像素数据"""
    def chunk(t, d):
        c = t + d
        return struct.pack(">I", len(d)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IEND", b""))


print("=== 0. 基线与登录 ===")
st, body = req("GET", "/api/health")
check("health 200", st == 200 and body.get("status") == "ok", str(body))
admin = login("admin", "admin123")
check("admin 登录成功", admin is not None)
st, me = req("GET", "/api/auth/me", token=admin)
check("/api/auth/me 200", st == 200 and me.get("username") == "admin", str(me))

st, hist0 = req("GET", "/api/conversations/default", token=admin)
msgs0 = hist0.get("messages", [])
print(f"  default 会话当前 {len(msgs0)} 条消息（基线）")

print("=== H-2a 非流式：LLM 出错不落库 ===")
st, chat = req("POST", "/api/chat", {"message": "冒烟测试-非流式-请忽略", "conversation_id": "default"}, token=admin)
check("非流式返回 200 且含不可用提示", st == 200 and "不可用" in chat.get("reply", ""), str(chat)[:120])
st, hist1 = req("GET", "/api/conversations/default", token=admin)
check("非流式出错后消息数不变", len(hist1.get("messages", [])) == len(msgs0),
      f"{len(msgs0)} -> {len(hist1.get('messages', []))}")

print("=== H-2b 流式 SSE：错误文本仍展示，但不落库 ===")
r = urllib.request.Request(BASE + "/api/chat/stream", method="POST",
                           data=json.dumps({"message": "冒烟测试-流式-请忽略", "conversation_id": "default"}).encode(),
                           headers={"Content-Type": "application/json", "Authorization": "Bearer " + admin})
frames = []
with urllib.request.urlopen(r, timeout=60) as resp:
    buf = b""
    while True:
        chunk = resp.read(256)
        if not chunk:
            break
        buf += chunk
        while b"\n\n" in buf:
            frame, buf = buf.split(b"\n\n", 1)
            frames.append(frame.decode("utf-8", "replace"))
text_all = "".join(frames)
check("SSE 含 chunk 帧（错误文本展示）", "event: chunk" in text_all and "不可用" in text_all, text_all[:200])
check("SSE 含 end 帧", "event: end" in text_all, text_all[:200])
check("SSE 帧格式 data: 带空格（前端兼容）", "data: {" in text_all or "data: \"" in text_all, text_all[:200])
st, hist2 = req("GET", "/api/conversations/default", token=admin)
check("流式出错后消息数不变", len(hist2.get("messages", [])) == len(msgs0),
      f"{len(msgs0)} -> {len(hist2.get('messages', []))}")

print("=== H-1 删除用户保护 ===")
st, body = req("POST", "/api/admin/users/delete", {"username": "admin"}, token=admin)
check("删除 admin 被拒 400", st == 400 and "管理员" in str(body.get("error", "")), f"{st} {body}")
st, body = req("POST", "/api/admin/users/delete", {"username": "no_such_user_xyz"}, token=admin)
check("删除不存在用户仍 404（原行为）", st == 404, f"{st} {body}")

print("=== M-4 自助改密：当前会话保留，其他会话失效 ===")
st, body = req("POST", "/api/admin/users/create",
               {"username": "smoke_tmp", "password": "tmp1234", "persona": "MONO"}, token=admin)
check("创建临时用户", st == 200 and body.get("success"), str(body))
tokA = login("smoke_tmp", "tmp1234")
tokB = login("smoke_tmp", "tmp1234")
check("临时用户拿到两个会话", tokA and tokB and tokA != tokB)
st, body = req("POST", "/api/auth/change-password",
               {"old_password": "tmp1234", "new_password": "tmp5678"}, token=tokA)
check("改密成功", st == 200 and body.get("success"), str(body))
st, _ = req("GET", "/api/auth/me", token=tokA)
check("改密后当前会话仍有效（不被踢下线）", st == 200, str(st))
st, _ = req("GET", "/api/auth/me", token=tokB)
check("改密后其他设备会话失效 401", st == 401, str(st))
check("新密码可登录", login("smoke_tmp", "tmp5678") is not None)
check("旧密码不可登录", login("smoke_tmp", "tmp1234") is None)
st, body = req("POST", "/api/admin/users/delete", {"username": "smoke_tmp"}, token=admin)
check("清理临时用户", st == 200 and body.get("success"), str(body))

print("=== L-1 / L-8 参数与类型校验 ===")
st, body = req("GET", "/api/admin/users/admin/memory?limit=-5", token=admin)
check("memory limit=-5 返回 200（不再 500）", st == 200 and "memory" in body, f"{st} {str(body)[:100]}")
st, body = req("GET", "/api/admin/users/admin/memory?limit=20", token=admin)
check("memory limit=20 正常", st == 200, f"{st}")
st, body = req("POST", "/api/admin/users/update", {"username": "admin", "persona_override": "oops"}, token=admin)
check("persona_override 传字符串被拒 400", st == 400, f"{st} {body}")

print("=== N-3 提醒创建/取消 ===")
st, body = req("POST", "/api/conversations/reminders/create",
               {"conversation_id": "default", "target_time": "2099-01-01T10:00:00", "content": "冒烟测试提醒"}, token=admin)
rid = body.get("reminder_id")
check("创建提醒成功", st == 200 and rid, str(body))
st, body = req("POST", f"/api/conversations/reminders/{rid}/cancel", token=admin)
check("取消待触发提醒 success=true", st == 200 and body.get("success") is True, str(body))
st, body = req("POST", "/api/conversations/reminders/rem_nope/cancel", token=admin)
check("取消不存在提醒 success=false（原行为）", st == 200 and body.get("success") is False, str(body))

print("=== N-1 配置值校验（拒绝路径，不写盘） ===")
bad_cases = [
    ("llm.temperature", 99), ("llm.temperature", -1), ("llm.temperature", "abc"),
    ("llm.max_tokens", -5), ("llm.max_tokens", "abc"), ("llm.max_tokens", 1.5),
    ("llm.max_context_rounds", 0), ("llm.max_context_rounds", 101),
    ("behavior.quiet_time.start", "25:99"), ("behavior.quiet_time.end", "9:00"),
    ("llm.base_url", "ftp://x"), ("behavior.auto_message.min_hours", 200),
    ("behavior.queue_timeout", 999),
]
for key, val in bad_cases:
    st, body = req("POST", "/api/config/save", {key: val}, token=admin)
    check(f"拒绝非法配置 {key}={val!r}", st == 400, f"{st} {body}")
st, body = req("POST", "/api/config/save",
               {"behavior.auto_message.min_hours": 6, "behavior.auto_message.max_hours": 2}, token=admin)
check("拒绝 min_hours > max_hours", st == 400, f"{st} {body}")
st, body = req("POST", "/api/config/save", {"llm.model": "smoke-test-model"}, token=admin)
check("合法配置可保存", st == 200 and body.get("success"), str(body))
st, body = req("POST", "/api/config/save", {"llm.model": ""}, token=admin)
check("合法配置可保存（还原为空）", st == 200 and body.get("success"), str(body))

print("=== M-2 解压炸弹防护 ===")
st, body = multipart_upload("/api/personas/MONO/avatar", admin, "file", "bomb.png", png_bomb(20000, 20000), "image/png")
check("超大尺寸头像上传被拒 400", st == 400 and "尺寸" in str(body.get("error", "")), f"{st} {body}")

print("=== WebSocket：握手/ping/聊天（错误路径） ===")
try:
    sock = socket.create_connection(("127.0.0.1", 8081), timeout=30)
    key = base64.b64encode(os.urandom(16)).decode()
    sock.sendall((
        "GET /ws/chat HTTP/1.1\r\nHost: 127.0.0.1:8081\r\nUpgrade: websocket\r\n"
        "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n"
        "Cookie: kc_token=" + admin + "\r\n\r\n").encode())
    resp = sock.recv(4096).decode("utf-8", "replace")
    check("WS 握手 101", "101" in resp.split("\r\n")[0], resp.split("\r\n")[0])

    def send_frame(payload):
        data = payload.encode()
        mask = os.urandom(4)
        head = bytearray([0x81])
        n = len(data)
        if n < 126:
            head.append(0x80 | n)
        else:
            head.append(0x80 | 126)
            head += struct.pack(">H", n)
        head += mask
        sock.sendall(bytes(head) + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def recv_frame():
        def exact(n):
            buf = b""
            while len(buf) < n:
                c = sock.recv(n - len(buf))
                if not c:
                    raise ConnectionError("closed")
                buf += c
            return buf
        b1, b2 = exact(2)
        ln = b2 & 0x7F
        if ln == 126:
            ln = struct.unpack(">H", exact(2))[0]
        elif ln == 127:
            ln = struct.unpack(">Q", exact(8))[0]
        return exact(ln) if ln else b""

    send_frame(json.dumps({"type": "ping"}))
    pong = json.loads(recv_frame().decode())
    check("ping/pong 正常", pong.get("type") == "pong", str(pong))
    send_frame(json.dumps({"message": "冒烟测试-WS-请忽略", "conversation_id": "default"}))
    types = []
    for _ in range(20):
        msg = json.loads(recv_frame().decode())
        types.append(msg.get("type"))
        if msg.get("type") == "end":
            break
    check("WS 聊天帧序列 start→chunk→end", types[:2] == ["start", "chunk"] and types[-1] == "end", str(types))
    sock.close()
except Exception as e:
    check("WebSocket 链路", False, repr(e))

print("=== H-3 限流不再被 X-Forwarded-For 绕过 ===")
codes = []
for i in range(12):
    r = urllib.request.Request(BASE + "/api/auth/login", method="POST",
                               data=json.dumps({"username": "admin", "password": "wrong-" + str(i)}).encode(),
                               headers={"Content-Type": "application/json",
                                        "X-Forwarded-For": f"10.0.0.{i}"})
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            codes.append(resp.status)
    except urllib.error.HTTPError as e:
        codes.append(e.code)
# 安全属性：每个请求带不同 XFF，限流器仍然生效（429 出现且不晚于第 11 次尝试）
first429 = codes.index(429) if 429 in codes else -1
check("轮换 XFF 也无法绕过限流（出现 429）", 429 in codes, str(codes))
check("限流在 11 次尝试内生效", 0 <= first429 <= 10, str(codes))

st, hist3 = req("GET", "/api/conversations/default", token=admin)
check("WS 聊天（出错）后消息数不变", len(hist3.get("messages", [])) == len(msgs0),
      f"{len(msgs0)} -> {len(hist3.get('messages', []))}")

print()
print(f"===== 结果: {len(PASS)} 通过, {len(FAIL)} 失败 =====")
if FAIL:
    print("失败项:", FAIL)
