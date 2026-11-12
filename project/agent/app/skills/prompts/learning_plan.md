---
name: learning_plan
description: 基于简历或岗位匹配差距生成受时间预算、证据来源与真实仓库候选约束的训练计划。
operation: LEARNING_PLAN
version: learning-plan-v3
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：根据输入的 `planSource` 生成一份可执行的专项训练计划。`planSource` 为 `RESUME` 时以简历事实为主、岗位匹配为可选补充；为 `MATCH` 时只使用岗位匹配差距。

### 通用约束

- items 数量为 1..20；每个 `estimatedMinutes` 为 5..120；所有 items 的 `estimatedMinutes` 总和不得超过 `dailyMinutes × days`。
- `skillCode` 只能来自 `allowedSkillCodes`；`kind` 只能为 `READING`、`PRACTICE`、`INTERVIEW`。
- 每项必须填写 `objective`（训练目标）、`reason`（推荐原因）、`method`（学习或练习方式）、`completionCriteria`（完成标准），均为 1..500 字且不可空泛。
- `resourceRefs` 只能使用 `knowledgeRefs` 中已有的引用，没有依据时为空数组。
- 不安排付费课程、自动投递或任何招聘平台的自动操作。
- 不要输出 `github` 字段，GitHub 检索结果由系统注入，模型不得自行编造仓库、链接或 star 数。

### 按计划来源

- `MATCH`：`matchItem` 中的 `gaps` 是主要依据；`category` 只能为 `BASIC`，`evidenceStatus` 只能为 `ASSESSED`。
- `RESUME`：必须结合 `resume`、`targetDirection`、`trainingHistory` 与 `limitations`。
  - `BASIC`：结合目标方向与技术栈按需覆盖编程语言、框架、数据库、数据结构与算法、计算机网络、操作系统等；只覆盖 `allowedSkillCodes` 中实际存在的技能，不要求机械覆盖全部类别。
  - `RESUME_PROJECT`：围绕 `resume.projectsText` 中真实出现的项目与技术，训练项目介绍、技术选型、个人贡献、难点与面试追问。`evidenceQuote` 必须是从 `projectsText` 中原样复制的连续片段（至少 6 个字符），不得改写或编造。当 `projectsText` 非空时至少安排 1 项。
  - `GITHUB`：仅当 `github.status` 为 `AVAILABLE` 时允许。只能从 `github.candidates` 中选择仓库；`resourceLinks` 必须包含 1..3 个 `{label,url}`，`url` 必须原样复制候选的 `htmlUrl`。推荐理由、学习重点与预计投入写入 `reason`、`method`、`completionCriteria`。`kind` 使用 `READING`，`evidenceStatus` 使用 `ASSESSED`。
  - 当 `github.status` 不是 `AVAILABLE` 时，禁止输出任何 `GITHUB` 项；在 `summary` 中明确说明 GitHub 推荐当前不可用，计划仍可继续使用其他训练内容。
  - 技能证据：简历未提及某项技能时不得断言用户不会；使用 `evidenceStatus` 为 `PENDING`，并在 `reason` 中写明“简历未提供直接证据，待评估”。`RESUME_PROJECT` 与 `GITHUB` 不允许 `PENDING`。
  - 训练反馈：`trainingHistory.evaluatedAnswers` 大于 0 时，可参考 `averageScore` 与 `weakSkillCodes` 调整顺序与难度；等于 0 时不得虚构能力评价。

## 输出结构

```json
{
  "title": "计划标题",
  "summary": "计划依据与当前限制的简要说明",
  "items": [
    {
      "skillCode": "allowedSkillCodes 中的 code",
      "title": "计划项标题",
      "kind": "READING|PRACTICE|INTERVIEW",
      "category": "BASIC|RESUME_PROJECT|GITHUB",
      "objective": "训练目标",
      "reason": "推荐原因，包含简历或岗位差距依据",
      "method": "学习或练习方式",
      "completionCriteria": "可核验的完成标准",
      "evidenceStatus": "ASSESSED|PENDING",
      "evidenceQuote": "RESUME_PROJECT 必填，来自 projectsText 的原文片段",
      "resourceLinks": [
        {
          "label": "仓库名称",
          "url": "github.candidates 中的 htmlUrl"
        }
      ],
      "estimatedMinutes": 20,
      "resourceRefs": []
    }
  ]
}
```