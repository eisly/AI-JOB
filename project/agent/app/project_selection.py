"""综合项目优先，同时保证候选集合包含全部目标技能。"""
from __future__ import annotations

from .schemas import (
    AIProjectRecommendResult, GitHubCandidate, GitHubLookup,
    ProjectRecommendationItem, ProjectRecommendPayload,
)


def candidate_skills(candidate: GitHubCandidate, targets: list[str]) -> list[str]:
    matched = {code.upper() for code in candidate.matched_skill_codes}
    return [code for code in dict.fromkeys(targets) if code.upper() in matched]


def select_repositories(
    candidates: list[GitHubCandidate], targets: list[str], limit: int = 6,
) -> list[GitHubCandidate]:
    """先选综合覆盖最佳项，再补技能差集，最后填充优质备选。"""
    unique = {item.html_url.lower().rstrip("/"): item for item in candidates
              if candidate_skills(item, targets)}
    ranked = sorted(unique.values(), key=lambda item: (
        -len(candidate_skills(item, targets)), -item.stargazers_count, item.full_name.lower(),
    ))
    remaining = list(ranked)
    selected: list[GitHubCandidate] = []
    covered: set[str] = set()
    while remaining and len(selected) < limit:
        candidate = min(remaining, key=lambda item: (
            -len(set(candidate_skills(item, targets)) - covered),
            -len(candidate_skills(item, targets)), -item.stargazers_count, item.full_name.lower(),
        ))
        if not set(candidate_skills(candidate, targets)) - covered:
            break
        selected.append(candidate)
        covered.update(candidate_skills(candidate, targets))
        remaining.remove(candidate)
    selected.extend(item for item in ranked if item not in selected)
    # 展示顺序按单仓库覆盖数，集合选择仍保留用于补齐差集的项目。
    return sorted(selected[:limit], key=lambda item: (
        -len(candidate_skills(item, targets)), -item.stargazers_count, item.full_name.lower(),
    ))


def repository_recommendation(payload: ProjectRecommendPayload, candidate: GitHubCandidate) -> ProjectRecommendationItem:
    skills = candidate_skills(candidate, payload.allowed_skill_codes)
    return ProjectRecommendationItem(
        title=f"{candidate.full_name} 仓库实战"[:200],
        summary=candidate.description,
        skillCodes=skills, difficulty=payload.difficulty,
        estimatedWeeks=min(payload.max_duration_weeks, {"BEGINNER": 1, "INTERMEDIATE": 2, "ADVANCED": 4}[payload.difficulty]),
        reason=f"GitHub 公开仓库，元数据匹配 {'、'.join(skills)}；与其他候选互补覆盖目标技能。",
        deliverables=["完成仓库环境搭建与核心流程运行", "提交功能改动或测试改进", "记录运行结果与问题定位过程"],
        sourceType="GITHUB_REPOSITORY", source="GITHUB_API", repository=candidate,
    )


def _supplement(payload: ProjectRecommendPayload, skills: list[str], github_available: bool) -> ProjectRecommendationItem:
    return ProjectRecommendationItem(
        title=f"{skills[0]} 等 {len(skills)} 项技能综合补充练习"[:200],
        summary=f"围绕 {'、'.join(skills)} 设计一条可运行的业务流程，说明各技能承担的功能，完成集成、自测和运行说明。",
        skillCodes=skills, difficulty=payload.difficulty,
        estimatedWeeks=min(payload.max_duration_weeks, {"BEGINNER": 1, "INTERMEDIATE": 2, "ADVANCED": 4}[payload.difficulty]),
        reason=("本轮选出的 GitHub 项目尚未覆盖这些目标技能，使用独立 AI 练习方案补充；未绑定真实仓库。"
                if github_available else "GitHub 不可用或未找到合适仓库，使用综合练习方案补齐目标技能。"),
        deliverables=["技能用途与集成设计", "可运行源码、样例数据与自测记录", "运行说明与核心流程演示"],
        sourceType="AI_GENERATED", source="LOCAL_RULES",
    )


def complete_recommendations(
    payload: ProjectRecommendPayload, github: GitHubLookup, model_items: list[ProjectRecommendationItem],
) -> AIProjectRecommendResult:
    """不依赖模型决定数量或遗漏技能；最多六项，缺少真实仓库时明确补 AI 方案。"""
    targets = list(dict.fromkeys(payload.allowed_skill_codes))
    by_url = {item.repository.html_url.lower().rstrip("/"): item for item in model_items if item.repository}
    candidates = github.candidates if github.status == "AVAILABLE" else []
    ai_items = [item for item in model_items if item.source_type == "AI_GENERATED"]
    ai_items.sort(key=lambda item: -len(set(item.skill_codes)))
    for reserved in range(3):
        if candidates:
            selected = select_repositories(candidates, targets, 6 - reserved)
            items = [by_url.get(candidate.html_url.lower().rstrip("/")) or repository_recommendation(payload, candidate)
                     for candidate in selected]
        else:
            items = ai_items[:6 - reserved]
        covered = {code for item in items for code in item.skill_codes}
        missing = [code for code in targets if code not in covered]
        if len(missing) <= reserved * 10:
            break
    # 一个补充方案最多十项技能；保留仓库优先位置，避免 AI 方案伪装成仓库覆盖。
    for start in range(0, len(missing), 10):
        required = missing[start:start + 10]
        reusable = next((item for item in ai_items if set(required).issubset(item.skill_codes)), None)
        items.append(reusable or _supplement(payload, required, bool(candidates)))
    items.sort(key=lambda item: (
        item.source_type != "GITHUB_REPOSITORY", -len(set(item.skill_codes)),
        -(item.repository.stargazers_count if item.repository else 0),
    ))
    items = [item.model_copy(update={"missing_skill_codes": [code for code in targets if code not in item.skill_codes]})
             for item in items]
    covered = {code for item in items for code in item.skill_codes}
    github_covered = {code for item in items if item.source_type == "GITHUB_REPOSITORY" for code in item.skill_codes}
    return AIProjectRecommendResult(
        items=items, targetSkillCodes=targets,
        coveredSkillCodes=[code for code in targets if code in covered],
        githubCoveredSkillCodes=[code for code in targets if code in github_covered],
        missingSkillCodes=[code for code in targets if code not in covered],
    )
