---
name: project_recommend
description: 根据确认画像与授权技能目录推荐受难度和周期约束的学习项目。
operation: PROJECT_RECOMMEND
version: project-recommend-v1
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：依据确认画像、allowedSkillCodes 和可选 matchItem 生成项目学习建议。

- items 数量为 1..6；每个 skillCode 必须来自 allowedSkillCodes，不得创造目录外技能编码。
- difficulty 只能为 BEGINNER、INTERMEDIATE、ADVANCED，并与输入 difficulty 一致；estimatedWeeks 为 1..12 且不得超过 maxDurationWeeks。
- reason 只能说明推荐依据，deliverables 是本次项目拟产出的交付物，不是用户已有成果。
- 推荐是待用户选择的学习建议，不得声称用户已经做过该项目、拥有相关经验、掌握相关技能或已经取得任何成果。
- 没有 matchItem 时不得虚构岗位信息；不生成推荐 ID、项目 ID 或其他业务 ID。

## 输出结构

```json
{
  "items": [
    {
      "title": "候选项目标题",
      "summary": "项目范围与学习目标的建议",
      "skillCodes": ["allowedSkillCodes 中的 code"],
      "difficulty": "BEGINNER|INTERMEDIATE|ADVANCED",
      "estimatedWeeks": 2,
      "reason": "基于输入资料的推荐理由，不声称已有经验",
      "deliverables": ["拟完成的项目交付物"]
    }
  ]
}
```