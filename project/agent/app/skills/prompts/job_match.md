---
name: job_match
description: 评估候选岗位语义相关性，并为本人个人 JD 生成岗位要求证据卡。
operation: JOB_MATCH
version: semantic-v4
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：对 Java 已筛选的候选岗位评估语义相关性；当候选标记 personalOwned=true 时，额外生成岗位要求证据卡。

- items 必须与 candidates 一一对应，jobId/jobVersion 必须来自输入，不得增加、遗漏或变更版本。
- semanticScore 为 0..1 语义相关性，无法可靠评价写 null；不做候选池 Min-Max，不输出最终匹配分。
- reasons 只能引用输入事实（目标方向、已确认技能、JD 描述），不得新增经历或能力结论。
- gapSuggestions 的键必须属于该候选 requirements.skills。
- requirementEvidence 只对 personalOwned=true 的候选生成，最多 12 条，按 JD 原文出现顺序排列。
- requirementQuote 必须是该候选 jdText 中逐字可定位的技术或实践要求原句，不得改写或合并多条要求；jdText 可能被截断到前 4000 字，只引用输入中可见的原文。
- skillCode 必须属于该候选 requirements.skills 且与本条要求直接相关；无法对应到技能时写 null。
- resumeQuote 必须完整照抄 resumeQuotes 中的一项已确认简历引文。没有对应引文时写 null，不得改写或拼接。
- supportLevel：resumeQuote 直接覆盖该要求写 DIRECT；只覆盖其中一部分写 PARTIAL；没有有效简历引文写 MISSING。不得因为技能分高或训练记录多就判定为 DIRECT。
- 同一条要求只输出一次；不得把 JD 要求写成用户已具备的经验。
- 不输出 eligibility 或录用概率。

## 输出结构

```json
{
  "items": [
    {
      "jobId": "输入候选中存在的 UUID",
      "jobVersion": 1,
      "semanticScore": 0.9,
      "reasons": ["仅引用输入事实的解释"],
      "gapSuggestions": {
        "输入候选技能 code": "训练建议"
      },
      "requirementEvidence": [
        {
          "requirementQuote": "JD 原文逐字片段",
          "skillCode": "输入候选技能 code 或 null",
          "supportLevel": "DIRECT",
          "resumeQuote": "简历原文逐字片段或 null"
        }
      ]
    }
  ]
}
```
