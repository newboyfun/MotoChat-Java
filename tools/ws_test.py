"""极简 WebSocket 客户端，用于验证 /ws/chat 的完整收发链路（不依赖第三方库）。"""
import base64, json, os, socket, struct, sys, urllib.request

BASE = "http://127.0.0.1:8080"


def login():
    req = urllib.request.Request(
        BASE + "/api/auth/login",
        data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())["token"]


def send_frame(sock, payload: str):
    data = payload.encode("utf-8")
    mask = os.urandom(4)
    header = bytearray([0x81])
    n = len(data)
    if n < 126:
        header.append(0x80 | n)
    elif n < 65536:
        header.append(0x80 | 126)
        header += struct.pack(">H", n)
    else:
        header.append(0x80 | 127)
        header += struct.pack(">Q", n)
    header += mask
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    sock.sendall(bytes(header) + masked)


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("连接已关闭")
        buf += chunk
    return buf


def recv_frame(sock):
    b1, b2 = recv_exact(sock, 2)
    opcode = b1 & 0x0F
    masked = b2 & 0x80
    length = b2 & 0x7F
    if length == 126:
        length = struct.unpack(">H", recv_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack(">Q", recv_exact(sock, 8))[0]
    key = recv_exact(sock, 4) if masked else None
    payload = recv_exact(sock, length) if length else b""
    if key:
        payload = bytes(b ^ key[i % 4] for i, b in enumerate(payload))
    return opcode, payload


def main():
    token = login()
    print("登录成功, token:", token[:12], "...")

    sock = socket.create_connection(("127.0.0.1", 8080), timeout=30)
    key = base64.b64encode(os.urandom(16)).decode()
    handshake = (
        "GET /ws/chat HTTP/1.1\r\n"
        "Host: 127.0.0.1:8080\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        f"Cookie: kc_token={token}\r\n\r\n"
    )
    sock.sendall(handshake.encode())
    resp = sock.recv(4096).decode("utf-8", "replace")
    status = resp.split("\r\n")[0]
    print("握手:", status)
    if "101" not in status:
        sys.exit(1)

    print("--- ping/pong ---")
    send_frame(sock, json.dumps({"type": "ping"}))
    op, payload = recv_frame(sock)
    print("收到:", payload.decode())

    print("--- 发送聊天消息 ---")
    send_frame(sock, json.dumps({"message": "你好", "conversation_id": "default"}))
    chunks = 0
    types = []
    for _ in range(20):
        op, payload = recv_frame(sock)
        msg = json.loads(payload.decode())
        types.append(msg.get("type"))
        if msg.get("type") == "chunk":
            chunks += 1
            print("  chunk:", msg["data"][:60])
        elif msg.get("type") == "end":
            print("  end: usage =", msg.get("usage"))
            break
        else:
            print(" ", msg.get("type"), ":", str(msg)[:100])
    print(f"帧序列: {types}  共 {chunks} 个 chunk")
    sock.close()


if __name__ == "__main__":
    main()
