"""慢速流式 mock：200 块 × 50ms ≈ 10 秒，用于验证客户端断开后服务端是否停止生成。"""
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

CHUNKS = 200


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        self.rfile.read(length)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        try:
            for i in range(CHUNKS):
                frame = "data: " + json.dumps(
                    {"choices": [{"delta": {"content": f"块{i:03d}"}}]}, ensure_ascii=False) + "\n\n"
                data = frame.encode()
                self.wfile.write(f"{len(data):X}\r\n".encode() + data + b"\r\n")
                self.wfile.flush()
                time.sleep(0.05)
            done = b"data: [DONE]\n\n"
            self.wfile.write(f"{len(done):X}\r\n".encode() + done + b"\r\n")
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
            print(f"[mock] 全部 {CHUNKS} 块发送完毕", flush=True)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            print(f"[mock] 客户端在第 {i} 块时断开（共 {CHUNKS} 块）", flush=True)


if __name__ == "__main__":
    print("slow mock 启动在 :9998", flush=True)
    HTTPServer(("127.0.0.1", 9998), Handler).serve_forever()
