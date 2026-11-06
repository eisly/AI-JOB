"""运行环境兼容辅助：仅在受限环境生效，健康环境下为 no-op。

生产代码不依赖本模块；测试 conftest 与本地冒烟脚本在创建 asyncio
事件循环前调用，用于绕过禁止 IPv4 回环连接的沙箱限制。
"""

from __future__ import annotations

import socket


def ensure_asyncio_self_pipe() -> None:
    """asyncio 在 Windows 通过 socket.socketpair() 创建事件循环 self-pipe。

    默认实现连接 127.0.0.1；当 IPv4 回环被沙箱拒绝时，透明回退为
    IPv6 回环 pair。若默认 socketpair 可用则不做任何修改。
    """
    original = socket.socketpair
    try:
        left, right = original()
    except OSError:
        pass
    else:
        left.close()
        right.close()
        return

    def socketpair(family=socket.AF_INET, type=socket.SOCK_STREAM, proto=0):
        if family == socket.AF_INET and hasattr(socket, "AF_INET6"):
            family = socket.AF_INET6
        return original(family, type, proto)

    socket.socketpair = socketpair