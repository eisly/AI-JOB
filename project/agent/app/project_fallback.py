from .schemas import AIProjectPlanResult, ProjectPlanPayload


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
        "summary": "规则化计划 · 基于已选真实仓库，按项目周期和每周可投入时间拆分最小实现、验证与交付。",
        "milestones": [
            {"seq": index, "title": title, "description": description,
             "tasks": [{"title": title, "description": description, "estimatedHours": min(8, payload.weekly_hours)}]}
            for index, (title, description) in enumerate(steps[:recommendation.estimated_weeks], 1)
        ],
    })