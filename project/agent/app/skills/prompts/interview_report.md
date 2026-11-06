---
name: interview_report
description: 汇总逐轮评价生成面试复盘、优缺点与差距建议。
operation: INTERVIEW_REPORT
version: interview-report-v2
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：为已结束的面试生成复盘点评。

- 输入包含完整消息和逐轮评价；不要重新评价回答，不要生成新的能力样本或逐轮分数。
- strengths、improvements、referenceAnswer 必须基于输入会话；没有有效回答时只给出最低限度的改进提示。
- gapSuggestions 的键只能来自 skillSnapshot；不发明技能或证据。
- 综合分由服务端依据有效逐轮评价重算，你不需要输出新的总分。

## 输出结构

```json
{
  "strengths": ["基于逐轮评价的优点"],
  "improvements": ["改进建议"],
  "referenceAnswer": "综合参考要点",
  "gapSuggestions": {"skillSnapshot 中的 skillCode": "训练建议"}
}
```