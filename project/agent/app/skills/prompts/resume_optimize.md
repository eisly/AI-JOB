---
name: resume_optimize
description: 针对目标岗位以行号引用方式生成严格引用简历原文的分段优化候选建议。
operation: RESUME_OPTIMIZE
version: resume-optimize-v2
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：依据简历授权原文（带行号标记）、目标 JD 和可选诊断上下文，生成分段优化候选建议。

- 引用协议：documentText 中每一行以 `[L行号]` 开头。你**不得抄写原文**，只能通过行号区间引用原文。
- `originalSpan` 为 `{start, end}`（闭区间，1 起始）：指向被优化段落的原文行区间，建议覆盖完整语义单元（1..6 行为宜，总长不超过 6000 字符）。
- `sourceSpans` 为行号区间数组（0..10 项）：指向支撑该建议的原文证据行区间（例如对应职责、项目、技能描述）。
- 行号必须来自 documentText 中实际存在的 `[L行号]` 标记；禁止编造、外推或使用不在标记中的行号；同一区间内 start 不得大于 end。
- `sectionKey` 在全部段落中必须唯一；sections 数量 1..20。
- `suggestedText` 只是候选改写，只能改写用户已经提供的简历、JD 与诊断上下文中的事实，不得新增或推测公司、雇主、项目、职责、技能、学历、年份、量化成果、指标、奖项或任何其他事实。
- 不得把 JD 要求写成用户已经具备的经验，不得把诊断建议写成已发生事实；原文缺少职责、成果等事实时，应在 suggestedText 中明确提示本人补充，不得编造。
- 每段建议应聚焦一条 JD 岗位要求或一条诊断问题；原文确实不包含对应事实时宁可不生成该段，也不得虚构。
- 不生成优化 ID、诊断 ID、简历 ID、岗位业务 ID 或其他业务 ID。

## 输出结构

```json
{
  "title": "定向优化候选标题",
  "sections": [
    {
      "sectionKey": "唯一段落键",
      "title": "段落标题",
      "originalSpan": {"start": 12, "end": 14},
      "suggestedText": "仅基于用户已提供事实的候选改写",
      "sourceSpans": [{"start": 12, "end": 12}]
    }
  ]
}
```