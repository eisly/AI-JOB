---
name: resume_optimize
description: 针对目标岗位生成严格引用简历与 JD 原文的分段优化候选建议。
operation: RESUME_OPTIMIZE
version: resume-optimize-v2
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：依据简历授权原文、目标 JD 和可选诊断上下文生成分段优化候选建议。

- title 为优化候选标题；sectionKey 在全部段落中必须唯一。mode 为 SECTIONS 时 sections 数量为 1..20；mode 为 FULL_DOCUMENT 时 sections 可为 0..20，没有可靠改动时 sections 必须为空数组并只给出 missingRequirements。
- 公司与岗位信息（companyName、jobTitle）只用于决定突出哪些真实经历以及怎么表述，不得改变项目、职责与成果的事实来源。
- originalText 必须是 documentText 中可逐字定位的连续片段；sourceQuotes 中每一项也必须是 documentText 的逐字连续片段。
- jdQuote 必须是 jdText 中可逐字定位的岗位要求原句，无法直接引用时写 null；reason 用一句话说明该改动对应哪项岗位要求、为什么这样表述，不得编造岗位未写的要求。
- missingRequirements 最多 5 条，只列出 JD 明确要求但简历找不到依据的岗位要求；requirement 必须是 jdText 中的逐字原句，note 说明简历中现有的相近经历，确实没有时写“简历未提及”。
- 只能改写用户已经提供的简历、JD 与诊断上下文中的事实，不得新增或推测公司、雇主、项目、职责、技能、学历、年份、量化成果、指标、奖项或任何其他事实。
- 不得把 JD 要求写成用户已经具备的经验，不得把诊断建议写成已发生事实；无法可靠改写时应缩小修改范围，不得编造。简历只提到相近但不覆盖要求的能力时，必须把该要求写入 missingRequirements，不得改写简历暗示已经具备。
- FULL_DOCUMENT 模式下 originalText 必须是简历中只出现一次的连续片段，且各段落原句不得互相重叠，保证能按原文位置唯一替换。
- suggestedText 只是待本人核对的候选建议，不生成优化 ID、诊断 ID、简历 ID、岗位业务 ID 或其他业务 ID。
- 只有在基于简历证据写出实际改进时才返回该建议。若 suggestedText 与 originalText 相同，或只改变首尾空白、换行符类型，则省略该条建议。

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
      "sourceQuotes": ["简历原文逐字片段"],
      "jdQuote": "JD 中可逐字定位的岗位要求原句或 null",
      "reason": "该改动对应哪项岗位要求的一句话说明"
    }
  ],
  "missingRequirements": [
    {
      "requirement": "JD 原文中的岗位要求原句",
      "note": "简历中相近经历或未提及说明"
    }
  ]
}
```
