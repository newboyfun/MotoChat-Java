"""假的 OpenAI 兼容服务，用于验证 LLM 网关的请求构造、SSE 解析与标签过滤。"""
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

# 故意混入 [happy] 表情标签、<think> 推理块、连续换行，用来验证 OutputFilter
PIECES = [
    "你好", "呀", "，", "[happy]", "我是", "MONO", "。",
    "<think>", "这里是被隐藏的推理过程", "</think>",
    "\n\n\n", "很高兴", "见到", "你", "！",
]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        stream = bool(body.get("stream"))

        if not stream:
            payload = json.dumps({
                "choices": [{"message": {"content": "你好呀[wink]，我是 MONO。\n\n\n测试完成。"}}],
                "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20},
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        for piece in PIECES:
            frame = "data: " + json.dumps(
                {"choices": [{"delta": {"content": piece}}]}, ensure_ascii=False) + "\n\n"
            data = frame.encode("utf-8")
            self.wfile.write(f"{len(data):X}\r\n".encode() + data + b"\r\n")
            self.wfile.flush()
            time.sleep(0.02)

        usage = "data: " + json.dumps({
            "choices": [],
            "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20},
        }) + "\n\n"
        data = usage.encode("utf-8")
        self.wfile.write(f"{len(data):X}\r\n".encode() + data + b"\r\n")
        done = b"data: [DONE]\n\n"
        self.wfile.write(f"{len(done):X}\r\n".encode() + done + b"\r\n")
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()


if __name__ == "__main__":
    print("mock OpenAI 服务启动在 :9999")
    HTTPServer(("127.0.0.1", 9999), Handler).serve_forever()
