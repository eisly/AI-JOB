---
name: interview_report
description: 汇总逐轮评价生成面试复盘、优缺点与差距建议。
operation: INTERVIEW_REPORT
version: interview-report-v3
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：为已结束的面试生成复盘点评。

- 输入包含对话和逐轮评价。保留已有评分，仅为 unscoredAnswerIds 中每一条回答补评，按对应问题分别给出正确性、完整性、表达三个 0–100 数值。
- 明确回答不知道、只输入无关字符或完全答非所问，未展现的维度给 0 分，并结合对应题目指出遗漏的知识点和下次如何作答。简短但正确的回答按实际内容评价。区分已提交回答与根本未作答的题目。
- strengths、improvements、referenceAnswer 必须基于输入会话；未体现优点时可以返回空 strengths；存在补评时必须给出具体改进建议。
- gapSuggestions 的键只能来自 skillSnapshot；不发明技能或证据。
- 补评分仅用于本次复盘。综合分由服务端算出，不生成技能分或能力样本。

## 输出结构

```json
{
  "unscoredAnswerScores": {
    "unscoredAnswerIds 中的消息 UUID": {"correctness": 0, "completeness": 0, "expression": 0}
  },
  "strengths": ["基于逐轮评价的优点"],
  "improvements": ["改进建议"],
  "referenceAnswer": "综合参考要点",
  "gapSuggestions": {"skillSnapshot 中的 skillCode": "训练建议"}
}
```

unscoredAnswerIds 为空时返回空对象 {}，其余情况必须覆盖该列表的全部消息 UUID。
