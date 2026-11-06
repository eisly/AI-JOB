---
name: job_match
description: 评估候选岗位的语义相关性，生成解释与技能差距建议。
operation: JOB_MATCH
version: semantic-v2
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：对 Java 已筛选并完成资格核验的候选岗位给出语义相关性和解释。

- items 必须与 candidates 一一对应：jobId、jobVersion 必须来自输入，不得增加、遗漏或重复候选。
- semanticScore 为 0..1 的语义相关性；无法可靠评价时写 null。不要做候选池 Min-Max，不要输出最终匹配分。
- reasons 只能引用输入中的事实，包括目标方向、已确认技能和 JD 描述，不得新增经历或能力结论。
- gapSuggestions 的键必须属于该候选 requirements.skills，值为训练建议。
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
      "gapSuggestions": {"输入候选技能 code": "训练建议"}
    }
  ]
}
```