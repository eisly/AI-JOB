from __future__ import annotations

import asyncio
import ipaddress
import socket
from collections.abc import Callable
from urllib.parse import urlparse


class UnsafeUrlError(ValueError):
    pass


Resolver = Callable[[str], list[str]]


def _parse_ip(value: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    try:
        return ipaddress.ip_address(value)
    except ValueError:
        return None


def _default_resolver(host: str) -> list[str]:
    infos = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    addresses: list[str] = []
    for item in infos:
        sockaddr = item[4]
        if sockaddr and isinstance(sockaddr[0], str):
            addresses.append(sockaddr[0])
    return addresses


def _reject_address(address: str) -> None:
    ip = _parse_ip(address)
    if ip is None:
        raise UnsafeUrlError(f"无法解析的主机地址: {address}")
    if (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
    ):
        raise UnsafeUrlError("目标地址属于内网或保留地址，已拒绝访问")


async def resolve_public_ipv4(host: str) -> str:
    """Resolve untrusted web destinations once, before connecting to the returned IP."""
    if "." not in host or host.endswith((".local", ".localhost", ".internal")) or _parse_ip(host):
        raise UnsafeUrlError("详情来源必须是公网域名")
    addresses = await asyncio.to_thread(_default_resolver, host)
    ipv4 = []
    for address in addresses:
        ip = _parse_ip(address)
        if ip is None or not ip.is_global or ip.is_multicast:
            raise UnsafeUrlError("详情域名包含非公网地址")
        if isinstance(ip, ipaddress.IPv4Address):
            ipv4.append(str(ip))
    if not ipv4:
        raise UnsafeUrlError("详情域名没有可用的公网 IPv4 地址")
    return ipv4[0]


async def ensure_public_url(
    url: str,
    *,
    allowed_hosts: set[str],
    allow_http: bool = False,
    resolver: Resolver | None = None,
) -> str:
    parsed = urlparse(url)
    scheme = (parsed.scheme or "").lower()
    if scheme not in {"http", "https"}:
        raise UnsafeUrlError("仅允许 http/https 地址")
    if scheme == "http" and not allow_http:
        raise UnsafeUrlError("外部检索仅允许 https 地址")
    host = (parsed.hostname or "").lower().strip(".")
    if not host:
        raise UnsafeUrlError("缺少目标主机")
    if allowed_hosts and host not in allowed_hosts:
        raise UnsafeUrlError(f"目标主机不在来源白名单: {host}")
    literal = _parse_ip(host)
    if literal is not None:
        _reject_address(str(literal))
        return url
    resolve = resolver or _default_resolver
    addresses = await asyncio.to_thread(resolve, host)
    if not addresses:
        raise UnsafeUrlError("目标主机无法解析")
    for address in addresses:
        _reject_address(address)
    return url
