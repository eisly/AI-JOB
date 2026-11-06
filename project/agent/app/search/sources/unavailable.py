from __future__ import annotations

from .base import SourceAdapter, SourceContext


class UnavailableSource(SourceAdapter):
    available = False
    type = "PUBLIC_SEARCH"
    verification = "UNVERIFIED"
    requires_credentials = True

    async def search(self, context: SourceContext):
        return self.unavailable()


class BossZhipinSource(UnavailableSource):
    key = "boss"
    name = "BOSS直聘"
    unavailable_reason = (
        "未接入：BOSS直聘未提供无需企业授权的公开岗位接口；2026-11-06 实测公开搜索接口返回风控"
        "（code=37 环境异常）。需官方开放平台合作或授权凭据后才可启用。"
    )


class ZhaopinSource(UnavailableSource):
    key = "zhaopin"
    name = "智联招聘"
    unavailable_reason = (
        "未接入：智联招聘官方开放平台需要企业授权凭据；2026-11-06 实测公开搜索接口不可访问（超时），"
        "无可用免鉴权数据源。"
    )


class LiepinSource(UnavailableSource):
    key = "liepin"
    name = "猎聘"
    unavailable_reason = (
        "未接入：猎聘公开搜索页返回需 JS 渲染/风控空壳，未提供可直接解析的结构化岗位数据；"
        "需官方开放平台授权或合作数据源。"
    )