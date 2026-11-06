---
name: learning_plan
description: 基于技能差距生成受时间预算与知识引用约束的训练计划。
operation: LEARNING_PLAN
version: learning-plan-v2
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：基于 Java 提供的匹配差距生成训练计划。

- items 数量为 1..20；每个 estimatedMinutes 为 5..120；所有 items 的 estimatedMinutes 总和不得超过 dailyMinutes × days。
- skillCode 只能来自 allowedSkillCodes；kind 只能为 READING、PRACTICE、INTERVIEW。
- resourceRefs 只能使用 knowledgeRefs 中已有的引用，没有依据时为空数组。
- 不安排 GitHub 项目实战、付费课程或自动投递。

## 输出结构

```json
{
  "title": "计划标题",
  "items": [
    {
      "skillCode": "allowedSkillCodes 中的 code",
      "title": "计划项标题",
      "kind": "READING|PRACTICE|INTERVIEW",
      "estimatedMinutes": 20,
      "resourceRefs": []
    }
  ]
}
```