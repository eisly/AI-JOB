---
name: project_plan
description: 将已选真实仓库项目快照拆分为受每周工时约束的里程碑计划。
operation: PROJECT_PLAN
version: project-plan-v2
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：仅根据已选 recommendation 快照和 weeklyHours 生成项目实战计划。

- milestones 数量为 1..8，seq 必须从 1 开始连续编号；每个里程碑包含 1..10 个 tasks。
- 每个 estimatedHours 为 1..40；全部任务总工时不得超过 weeklyHours × recommendation.estimatedWeeks，任务规模应与每周可投入时间相匹配。
- title、summary、里程碑和任务只能围绕已选推荐快照展开，不扩大项目范围，不虚构用户已有经验或交付成果。
- recommendation.primarySource 存在时，计划应围绕该真实仓库的 README、运行方式和入门步骤展开；不得生成新的仓库链接或虚构仓库内容。
- recommendation.gettingStarted 是候选阶段给出的入门步骤，可拆分为任务，但不得改写成用户已完成的成果。
- 不生成项目 ID、里程碑 ID、任务 ID 或其他业务 ID，不输出 status、progress、完成百分比或完成记录；这些由 Java 负责。

## 输出结构

```json
{
  "title": "项目计划标题",
  "summary": "基于已选推荐快照的计划说明",
  "milestones": [
    {
      "seq": 1,
      "title": "里程碑标题",
      "description": "本阶段目标",
      "tasks": [
        {
          "title": "任务标题",
          "description": "任务内容与验证方式",
          "estimatedHours": 4
        }
      ]
    }
  ]
}
```