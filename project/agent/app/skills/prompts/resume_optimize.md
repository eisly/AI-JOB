---
name: resume_optimize
description: 针对目标岗位生成严格引用简历原文的分段优化候选建议。
operation: RESUME_OPTIMIZE
version: resume-optimize-v1
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：依据简历授权原文、目标 JD 和可选诊断上下文生成分段优化候选建议。

- title 为优化候选标题；sections 数量为 1..20，sectionKey 在全部段落中必须唯一。
- originalText 必须是 documentText 中可逐字定位的连续片段；sourceQuotes 中每一项也必须是 documentText 的逐字连续片段。
- 只能改写用户已经提供的简历、JD 与诊断上下文中的事实，不得新增或推测公司、雇主、项目、职责、技能、学历、年份、量化成果、指标、奖项或任何其他事实。
- 不得把 JD 要求写成用户已经具备的经验，不得把诊断建议写成已发生事实；无法可靠改写时应缩小修改范围，不得编造。
- suggestedText 只是待本人核对的候选建议，不生成优化 ID、诊断 ID、简历 ID、岗位业务 ID 或其他业务 ID。

## 输出结构

```json
{
  "title": "定向优化候选标题",
  "sections": [
    {
      "sectionKey": "唯一段落键",
      "title": "段落标题",
      "originalText": "简历原文逐字片段",
      "suggestedText": "仅基于用户已提供事实的候选改写",
      "sourceQuotes": ["简历原文逐字片段"]
    }
  ]
}
```