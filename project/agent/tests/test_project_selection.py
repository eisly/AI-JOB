from datetime import datetime, timezone
from uuid import uuid4

from app.handlers import ProjectRecommendHandler
from app.project_selection import complete_recommendations, repository_recommendation, select_repositories
from app.schemas import AIProjectRecommendResult, GitHubCandidate, GitHubLookup, ProjectRecommendPayload
from tests.factories import project_recommend_envelope
from types import SimpleNamespace


TARGETS = ["DOCKER", "FASTAPI", "JAVA", "REDIS", "MYSQL"]


def payload(targets=TARGETS):
    data = project_recommend_envelope(uuid4())["payload"]
    data.update(allowedSkillCodes=targets, matchItem=None)
    return ProjectRecommendPayload.model_validate(data)


def repo(name, skills, stars=100):
    return GitHubCandidate(fullName=f"example/{name}", htmlUrl=f"https://github.com/example/{name}",
                           description="A runnable practice project", matchedSkillCodes=skills,
                           stargazersCount=stars, verifiedAt=datetime(2026, 1, 1, tzinfo=timezone.utc))


def pool():
    return [repo(f"mall{i}", ["DOCKER", "JAVA", "REDIS", "MYSQL"], 10000 - i) for i in range(8)] + [
        repo("fastapi", ["DOCKER", "FASTAPI", "REDIS"], 100),
    ]


def test_candidate_limit_preserves_rare_skill_instead_of_only_high_coverage_ties():
    selected = select_repositories(pool(), TARGETS, limit=6)
    assert len(selected) == 6
    assert selected[0].full_name == "example/mall0"
    assert "example/fastapi" in [item.full_name for item in selected]
    assert {code for item in selected for code in item.matched_skill_codes} == set(TARGETS)


def test_model_one_item_is_completed_to_six_complementary_options_without_false_coverage():
    data = payload()
    candidates = pool()
    github = GitHubLookup(status="AVAILABLE", candidates=candidates)
    model_item = repository_recommendation(data, candidates[0]).model_copy(update={"title": "保留模型项目范围"})
    result = ProjectRecommendHandler().validate_business(
        SimpleNamespace(payload=data), AIProjectRecommendResult(items=[model_item]),
        {"github": github.model_dump(by_alias=True)}, {},
    )
    assert len(result.items) == 6
    assert result.items[0].title == "保留模型项目范围"
    assert result.items[0].missing_skill_codes == ["FASTAPI"]
    assert "FASTAPI" not in result.items[0].skill_codes
    assert result.covered_skill_codes == TARGETS
    assert result.github_covered_skill_codes == TARGETS
    assert result.missing_skill_codes == []
    assert len({item.repository.html_url for item in result.items}) == 6
    assert all(item.source_type == "GITHUB_REPOSITORY" for item in result.items)


def test_github_missing_skill_adds_explicit_ai_supplement_and_reserves_slot():
    candidates = pool()[:-1]
    result = complete_recommendations(payload(), GitHubLookup(status="AVAILABLE", candidates=candidates), [])
    assert len(result.items) == 6
    assert len([item for item in result.items if item.source_type == "GITHUB_REPOSITORY"]) == 5
    supplement = result.items[-1]
    assert supplement.source_type == "AI_GENERATED"
    assert supplement.repository is None
    assert supplement.skill_codes == ["FASTAPI"]
    assert result.covered_skill_codes == TARGETS
    assert result.github_covered_skill_codes == ["DOCKER", "JAVA", "REDIS", "MYSQL"]
    assert result.missing_skill_codes == []


def test_three_available_repositories_returns_three_and_small_pool_does_not_duplicate():
    for size in (1, 2, 3, 6):
        candidates = [repo(f"all{i}", TARGETS, 100 + i) for i in range(size)]
        result = complete_recommendations(payload(), GitHubLookup(status="AVAILABLE", candidates=candidates), [])
        assert len(result.items) == size
        assert len({item.repository.html_url for item in result.items}) == size
        assert result.items[0].missing_skill_codes == []


def test_duplicate_urls_and_case_mapping_do_not_inflate_count_or_lose_skills():
    candidate = repo("api", ["DOCKER", "FASTAPI"])
    duplicate = candidate.model_copy(update={"html_url": candidate.html_url.upper() + "/"})
    result = complete_recommendations(payload(["docker", "fastapi"]),
                                      GitHubLookup(status="AVAILABLE", candidates=[candidate, duplicate]), [])
    assert len(result.items) == 1
    assert result.items[0].skill_codes == ["docker", "fastapi"]
    assert result.covered_skill_codes == ["docker", "fastapi"]


def test_github_disabled_can_cover_twenty_targets_with_bounded_ai_supplements():
    targets = [f"SKILL_{i}" for i in range(20)]
    result = complete_recommendations(payload(targets), GitHubLookup(status="DISABLED"), [])
    assert len(result.items) == 2
    assert result.covered_skill_codes == targets
    assert result.github_covered_skill_codes == []
    assert all(len(item.skill_codes) <= 10 and item.repository is None for item in result.items)


def test_only_one_github_candidate_plus_missing_skill_remains_two_honest_options():
    candidate = repo("mall", ["DOCKER", "JAVA", "REDIS", "MYSQL"])
    result = complete_recommendations(payload(), GitHubLookup(status="AVAILABLE", candidates=[candidate]), [])
    assert len(result.items) == 2
    assert result.items[0].missing_skill_codes == ["FASTAPI"]
    assert result.items[1].skill_codes == ["FASTAPI"]
    assert result.missing_skill_codes == []
