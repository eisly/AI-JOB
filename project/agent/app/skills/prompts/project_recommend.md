---
name: project_recommend
description: 从真实公开仓库候选中选择适合当前技能的项目，并补充学习建议。
operation: PROJECT_RECOMMEND
version: project-recommend-v2
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：只能从 repositoryCandidates 中选择真实公开仓库，并为每个仓库补充学习建议。

- repositoryCandidates 为空时输出 `{"items": []}`，禁止凭空推荐 GitHub、Gitee 或 GitCode 项目。
- 每项的 sourceUrl 必须原样复制某个候选的 sourceUrl，不得改写、拼接或生成新链接；同一个 sourceUrl 最多出现一次，items 为 0..6。
- 不输出 title、summary、primarySource、alternateSources，这些字段由系统从仓库资料填充。
- skillCodes 必须来自 allowedSkillCodes，不得创造目录外技能编码；优先选择候选资料中真实出现的技能。
- 输入 difficulty 为空表示不限，可为不同候选给出不同难度；非空时 difficulty 必须与输入一致。
- estimatedWeeks 为 1..maxDurationWeeks 的整数。
- gettingStarted 为 1..5 条可执行步骤，只能依据候选的仓库简介和 README 入口描述入门动作，不得编造仓库中不存在的命令或文件。
- reason 说明该仓库与目标技能、求职方向的匹配关系；deliverables 是本次项目拟产出的交付物。
- stackEvidence 记录检索时找到的技术证据。DOCUMENTED 表示文档声明，描述时保留待核验状态。
- 推荐是待用户选择的学习建议，不得声称用户已经做过该项目、拥有相关经验、掌握相关技能或已经取得任何成果。
- 不得编造 Star 数、许可证、仓库内容或 README 信息；没有 matchItem 时不得虚构岗位信息。

## 输出结构

```json
{
  "items": [
    {
      "sourceUrl": "repositoryCandidates 中的 sourceUrl，原样复制",
      "skillCodes": ["allowedSkillCodes 中的 code"],
      "difficulty": "BEGINNER|INTERMEDIATE|ADVANCED",
      "estimatedWeeks": 2,
      "reason": "仓库资料与目标技能的匹配理由，不声称已有经验",
      "gettingStarted": ["依据 README 入口的入门步骤"],
      "deliverables": ["拟完成的项目交付物"]
    }
  ]
}
```
