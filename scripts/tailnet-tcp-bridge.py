#!/usr/bin/env python3
"""
把本地端口通过 tailscaled 的 SOCKS5 出口转发到 tailnet 上的某个地址。

为什么需要它：
    这台机器跑的是 userspace 模式的 tailscaled（为了免 root，不建 TUN）。
    代价是**内核没有到 100.64.0.0/10 的路由** —— 只有 tailscaled 进程内部的
    serve 能接收入站。于是"手机能连电脑，电脑连不到手机"。
    adb 又不支持 SOCKS，所以中间得有一个用户态的 TCP 桥。

用法：
    # 把本地 15555 桥到手机的 adbd
    python3 scripts/tailnet-tcp-bridge.py 15555 <手机 tailnet IP> 5555

    # 然后
    adb connect 127.0.0.1:15555

只监听 127.0.0.1，不对外暴露。
"""
import argparse
import ipaddress
import select
import socket
import struct
import sys
import threading


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("SOCKS 代理提前关闭了连接")
        buf += chunk
    return buf


def socks5_connect(proxy_host: str, proxy_port: int, host: str, port: int, timeout: float) -> socket.socket:
    """通过 SOCKS5 代理建立到 host:port 的连接（无认证）。"""
    s = socket.create_connection((proxy_host, proxy_port), timeout=timeout)
    s.settimeout(timeout)
    # 握手：支持无认证
    s.sendall(b"\x05\x01\x00")
    ver, method = recv_exact(s, 2)
    if ver != 5 or method != 0:
        s.close()
        raise ConnectionError(f"SOCKS5 代理拒绝了无认证方式（method={method}）")

    # 目标地址编码：能解析成 IP 就用 ATYP 1/4，否则用域名
    try:
        ip = ipaddress.ip_address(host)
        if ip.version == 4:
            addr = b"\x01" + ip.packed
        else:
            addr = b"\x04" + ip.packed
    except ValueError:
        raw = host.encode("idna") if host.isascii() else host.encode()
        addr = b"\x03" + bytes([len(raw)]) + raw

    s.sendall(b"\x05\x01\x00" + addr + struct.pack("!H", port))
    reply = recv_exact(s, 4)
    if reply[1] != 0:
        s.close()
        raise ConnectionError(f"SOCKS5 CONNECT 失败，REP={reply[1]}")
    # 吃掉 BND.ADDR / BND.PORT
    atyp = reply[3]
    if atyp == 1:
        recv_exact(s, 4 + 2)
    elif atyp == 4:
        recv_exact(s, 16 + 2)
    elif atyp == 3:
        ln = recv_exact(s, 1)[0]
        recv_exact(s, ln + 2)
    s.settimeout(None)
    return s


def pump(a: socket.socket, b: socket.socket) -> None:
    """两个方向对拷，任一方向结束就都关掉。"""
    socks = [a, b]
    try:
        while True:
            readable, _, _ = select.select(socks, [], [], 60)
            if not readable:
                continue
            for src in readable:
                dst = b if src is a else a
                data = src.recv(65536)
                if not data:
                    return
                dst.sendall(data)
    except OSError:
        return
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            s.close()


def main() -> int:
    ap = argparse.ArgumentParser(description="通过 tailscaled SOCKS5 转发本地端口到 tailnet")
    ap.add_argument("listen_port", type=int, help="本地监听端口（绑 127.0.0.1）")
    ap.add_argument("target_host", help="tailnet 上的目标地址（如 100.x.y.z）")
    ap.add_argument("target_port", type=int, help="目标端口（adb 用 5555）")
    ap.add_argument("--socks-host", default="127.0.0.1")
    ap.add_argument("--socks-port", type=int, default=1055)
    ap.add_argument("--connect-timeout", type=float, default=15.0)
    args = ap.parse_args()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.listen_port))
    srv.listen(16)

    print(
        f"127.0.0.1:{args.listen_port}  ->  (socks5 {args.socks_host}:{args.socks_port})  ->  "
        f"{args.target_host}:{args.target_port}",
        flush=True,
    )

    def handle(client: socket.socket) -> None:
        try:
            upstream = socks5_connect(
                args.socks_host, args.socks_port, args.target_host, args.target_port, args.connect_timeout
            )
        except Exception as exc:  # noqa: BLE001
            print(f"  连接 {args.target_host}:{args.target_port} 失败：{exc}", flush=True)
            client.close()
            return
        pump(client, upstream)

    try:
        while True:
            client, _ = srv.accept()
            threading.Thread(target=handle, args=(client,), daemon=True).start()
    except KeyboardInterrupt:
        print("\n停止。", flush=True)
    finally:
        srv.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
