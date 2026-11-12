from .schemas import AIProjectPlanResult, AIProjectRecommendResult, ProjectPlanPayload, ProjectRecommendPayload


def recommend_project(payload: ProjectRecommendPayload) -> AIProjectRecommendResult:
    gaps = payload.match_item.gaps if payload.match_item else []
    skill = next((gap.skill_code for gap in gaps if gap.skill_code in payload.allowed_skill_codes),
                 payload.allowed_skill_codes[0])
    java = skill.upper() == "JAVA"
    weeks = min(payload.max_duration_weeks, {"BEGINNER": 1, "INTERMEDIATE": 2, "ADVANCED": 4}[payload.difficulty])
    title = "Java 投递进度看板" if java else f"{skill} 小型交付练习"
    summary = ("用合成数据实现投递记录新增、状态更新和查询。先完成 Java 接口与自动化测试，"
               "有时间再接简单列表页。"
               if java else f"用 {skill} 完成一个可运行的小功能，明确输入输出，并用样例验证正常和异常流程。")
    if weeks >= 2 and payload.difficulty != "BEGINNER":
        summary += "加入输入校验、边界测试和一键运行说明。"
    if weeks >= 3 and payload.difficulty == "ADVANCED":
        summary += "补充分页、失败恢复或状态变更审计，按项目实际范围选一项。"
    reason = (f"本地规则建议 · 优先练习已选岗位匹配中的 {skill} 技能差距。"
              if any(gap.skill_code == skill for gap in gaps)
              else f"本地规则建议 · 根据所选技能 {skill}、难度和周期生成。")
    return AIProjectRecommendResult.model_validate({"items": [{
        "title": title, "summary": summary, "skillCodes": [skill], "difficulty": payload.difficulty,
        "estimatedWeeks": weeks, "reason": reason,
        "deliverables": ["可运行源码与样例数据", "自动化测试和异常用例记录", "运行说明与核心流程演示"],
    }]})


def plan_project(payload: ProjectPlanPayload) -> AIProjectPlanResult:
    recommendation = payload.recommendation
    steps = [
        ("跑通最小功能", f"从已选项目中选一个最小流程：{recommendation.summary[:350]}"),
        ("补齐实现与边界验证", "覆盖正常输入、边界输入与失败恢复，记录发现的问题和修复结果。"),
        ("整理交付与演示", "补齐运行说明，复跑自测，用示例数据演示核心流程。"),
    ]
    if recommendation.estimated_weeks == 1:
        steps[0] = ("完成并演示最小功能", "只实现一条核心流程，用一条样例验证并记录运行命令。")
    elif recommendation.estimated_weeks == 2:
        steps[1] = ("测试并交付", "验证正常与异常输入，补齐运行说明和核心流程演示。")
    return AIProjectPlanResult.model_validate({
        "title": recommendation.title,
        "summary": "本地规则计划 · 按项目周期和每周可投入时间拆分最小实现、验证与交付。",
        "milestones": [
            {"seq": index, "title": title, "description": description,
             "tasks": [{"title": title, "description": description, "estimatedHours": min(8, payload.weekly_hours)}]}
            for index, (title, description) in enumerate(steps[:recommendation.estimated_weeks], 1)
        ],
    })
