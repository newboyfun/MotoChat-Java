"""成功路径回归：真实 LLM 流式/非流式全链路（mock OpenAI :9999）。
用带自定义 api_config 的临时用户，测完删除，零残留。"""
import json
import os
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8081"
MOCK = "http://127.0.0.1:9999"
PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  [PASS] " if cond else "  [FAIL] ") + name + (f"  -> {detail}" if detail and not cond else ""))


def req(method, path, body=None, token=None):
    r = urllib.request.Request(BASE + path, method=method)
    if token:
        r.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, data=data, timeout=60) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read())
        except Exception:
            return e.code, {}


admin = None
st, body = req("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
if st == 200:
    admin = body.get("token")
check("admin 登录", admin is not None)

# 1. 建临时用户，api_config 指向 mock
st, body = req("POST", "/api/admin/users/create",
               {"username": "smoke_llm", "password": "llm1234", "persona": "MONO"}, token=admin)
check("创建临时用户 smoke_llm", st == 200 and body.get("success"), str(body))
st, body = req("POST", "/api/admin/users/update",
               {"username": "smoke_llm", "api_key": "mock-key",
                "base_url": MOCK, "model": "mock-model"}, token=admin)
check("设置用户级 API 配置（指向 mock）", st == 200, str(body))

st, body = req("POST", "/api/auth/login", {"username": "smoke_llm", "password": "llm1234"})
tok = body.get("token") if st == 200 else None
check("临时用户登录", tok is not None)

# 2. 非流式成功路径
st, chat = req("POST", "/api/chat", {"message": "回归测试-非流式", "conversation_id": "default"}, token=tok)
reply = chat.get("reply", "")
check("非流式返回正常回复", st == 200 and "你好呀" in reply and "MONO" in reply, str(chat)[:150])
check("非流式 usage 透传（total=20）", chat.get("usage", {}).get("total_tokens") == 20, str(chat.get("usage")))
check("非流式三连换行被压缩", "\n\n\n" not in reply and "\n\n" in reply, repr(reply))

# 3. 流式成功路径
r = urllib.request.Request(BASE + "/api/chat/stream", method="POST",
                           data=json.dumps({"message": "回归测试-流式", "conversation_id": "default"}).encode(),
                           headers={"Content-Type": "application/json", "Authorization": "Bearer " + tok})
frames = []
with urllib.request.urlopen(r, timeout=60) as resp:
    buf = b""
    while True:
        c = resp.read(512)
        if not c:
            break
        buf += c
        while b"\n\n" in buf:
            f, buf = buf.split(b"\n\n", 1)
            frames.append(f.decode("utf-8", "replace"))
all_text = "".join(frames)
check("流式含 chunk 帧", "event: chunk" in all_text)
check("流式含 end 帧", "event: end" in all_text)
check("流式 end 带 usage=20", '"total_tokens":20' in all_text.replace(" ", ""), all_text[-300:])
check("流式 [happy] 表情标签被过滤", "[happy]" not in all_text, all_text[:300])
check("流式 think 块被隐藏", "推理过程" not in all_text and "<think>" not in all_text, all_text[:300])
# 解析所有 chunk 文本拼接后校验（合并器按 24 字/50ms 放行，小块会分帧发出，属设计行为）
import re as _re
joined = ""
for m in _re.findall(r'data: (\{[^\n]*\})', all_text):
    try:
        obj = json.loads(m)
        if "text" in obj:
            joined += obj["text"]
    except Exception:
        pass
check("流式正文完整（拼接口所有 chunk）",
      "你好呀" in joined and "我是" in joined and "MONO" in joined
      and "很高兴" in joined and "见到你" in joined, repr(joined[:200]))
# 流式帧保留原始换行（OutputFilter 流式只处理 think/表情标签，换行压缩发生在最终 clean），
# 落库内容必须已压缩——见下方"落库的助手回复"断言
stored_asst = None

# 4. 落库验证（成功路径必须正常落库——这是不能回归的核心功能）
st, hist = req("GET", "/api/conversations/user_smoke_llm", token=admin)
msgs = hist.get("messages", [])
check("成功路径正常落库（用户+助手各 1 条）", len(msgs) == 4, f"实际 {len(msgs)} 条")
if len(msgs) >= 4:
    asst = [m for m in msgs if m.get("role") == "assistant"]
    contents = " ".join(m.get("content", "") for m in asst)
    check("落库的助手回复已过滤（无 [happy]/think）",
          "[happy]" not in contents and "<think>" not in contents and "推理过程" not in contents, contents[:200])
    check("落库内容非空且含正文", "MONO" in contents or "你好" in contents, contents[:200])
    check("落库内容三连换行已压缩", "\n\n\n" not in contents, repr(contents[:200]))

# 5. 短期记忆文件验证
mem_path = "userdata/avatars/MONO/memory/smoke_llm/short_memory.json"
check("短期记忆文件已生成", os.path.exists(mem_path), mem_path)
if os.path.exists(mem_path):
    data = json.load(open(mem_path, encoding="utf-8"))
    check("记忆内容干净（过滤后）",
          all("[happy]" not in e.get("bot", "") and "<think>" not in e.get("bot", "") for e in data),
          json.dumps(data, ensure_ascii=False)[:200])
    check("记忆条数正确（2 轮）", len(data) == 2, f"{len(data)} 条")

# 6. token 用量落库
st, usage = req("GET", "/api/admin/token-usage", token=admin)
users = {u.get("username"): u for u in usage.get("users", [])}
check("token 用量已记录 smoke_llm", "smoke_llm" in users, str(list(users.keys())))

# 7. 清理：删除临时用户（级联删会话/消息/记忆）
st, body = req("POST", "/api/admin/users/delete", {"username": "smoke_llm"}, token=admin)
check("删除临时用户", st == 200 and body.get("success"), str(body))
st, hist = req("GET", "/api/conversations/user_smoke_llm", token=admin)
check("级联删除后会话消息为空", len(hist.get("messages", [])) == 0, str(len(hist.get("messages", []))))
check("记忆目录已清理", not os.path.exists(mem_path), mem_path)

print()
print(f"===== 成功路径回归: {len(PASS)} 通过, {len(FAIL)} 失败 =====")
if FAIL:
    print("失败项:", FAIL)
