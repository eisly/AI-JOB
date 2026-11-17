---
name: project_recommend
description: 根据确认画像与授权技能目录推荐受难度和周期约束的学习项目。
operation: PROJECT_RECOMMEND
version: project-recommend-v3
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：依据确认画像、allowedSkillCodes 和可选 matchItem 生成项目学习建议。

- items 数量为 1..6；每个 skillCode 必须来自 allowedSkillCodes，不得创造目录外技能编码。
- difficulty 只能为 BEGINNER、INTERMEDIATE、ADVANCED，并与输入 difficulty 一致；estimatedWeeks 为 1..12 且不得超过 maxDurationWeeks。
- reason 只能说明推荐依据，deliverables 是本次项目拟产出的交付物，不是用户已有成果。
- 推荐是待用户选择的学习建议，不得声称用户已经做过该项目、拥有相关经验、掌握相关技能或已经取得任何成果。
- 没有 matchItem 时不得虚构岗位信息；不生成推荐 ID、项目 ID 或其他业务 ID。
- `github.status=AVAILABLE` 时，可以推荐真实 GitHub 仓库，但只能从 `github.candidates` 中选择；必须原样复制候选的 `fullName`、`htmlUrl`、描述、Star、语言、更新时间、来源和验证时间，并将 `sourceType` 设为 `GITHUB_REPOSITORY`、`source` 设为 `GITHUB_API`。
- `github.candidates` 已按仓库能够覆盖的技能交集从高到低排序；优先选择覆盖技能数量最多的仓库。一个项目可以同时覆盖多个目标技能，必须把该仓库的全部 `matchedSkillCodes` 写入 `skillCodes`，不要拆成“每个技能一个项目”。
- 推荐集合需要互补：第一个项目尽可能多覆盖目标技能，其他候选优先补齐尚未覆盖的技能，不能只保留与第一名覆盖数相同的仓库。有至少 3 个不同合格仓库时返回 3..6 个备选，不足 3 个时返回实际可用数量，不重复仓库凑数。
- 全部候选的 `skillCodes` 并集应包含全部 `allowedSkillCodes`。某技能在本轮 GitHub 候选中没有匹配时，另给明确标记为 `AI_GENERATED/MODEL` 的综合或补充练习方案；不得把该技能添加到不匹配的真实仓库。
- 每项 `missingSkillCodes` 为目标技能减去该项目的 `skillCodes`。覆盖表示仓库元数据匹配或 AI 方案的练习目标，均不代表已运行验证代码；用户选择一个候选后仍生成一个独立计划。
- `github.status` 不是 `AVAILABLE` 时，禁止输出 `GITHUB_REPOSITORY`；请输出 `AI_GENERATED` 且 `source` 为 `MODEL` 的练习项目。AI 练习项目不代表现实中已有同名项目。
- AI 练习项目也应尽可能把多个已选目标技能整合到同一个业务场景，说明各技能用途，不按每项技能分别创建一个候选。真实仓库只能声明元数据实际匹配的技能，不得为凑齐目标技能伪造覆盖关系。
- 不得编造 GitHub URL、仓库名称、Star 数、更新时间、来源或验证时间；不得把 AI 练习项目伪装成真实仓库。

## 输出结构

```json
{
  "items": [
    {
      "title": "候选项目标题",
      "summary": "项目范围与学习目标的建议",
      "skillCodes": ["allowedSkillCodes 中的 code"],
      "missingSkillCodes": ["本项目未覆盖的目标技能"],
      "difficulty": "BEGINNER|INTERMEDIATE|ADVANCED",
      "estimatedWeeks": 2,
      "reason": "基于输入资料的推荐理由，不声称已有经验",
      "deliverables": ["拟完成的项目交付物"],
      "sourceType": "GITHUB_REPOSITORY|AI_GENERATED",
      "source": "GITHUB_API|MODEL",
      "repository": null
    }
  ]
}
```
