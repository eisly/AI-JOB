---
name: learning_evaluate
description: 按固定量表评价回答，产出维度评分与技能分候选。
operation: LEARNING_EVALUATE
version: answer-evaluate-v3
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：只按输入题目和量表评价回答。

- dimensions 必须与输入 rubric 的维度 key 完全一致，每个 score 为 0..100，weight 使用输入权重；不得新增、删除维度或改变评分规则。
- 回答空白、无关、无法评价时输出 evidenceStatus=INSUFFICIENT 或 INVALID，overallScore 为 null，dimensions 和 skillScores 均为空数组，不得把无法评价当作 0 分。
- skillScores 的 skillCode 只能来自 question.skillCodes；技能分不得超出 0..100。
- strengths、improvements 和 comment 只针对本次回答，不编造用户未提交的项目或经历。

## 输出结构

```json
{
  "evaluation": {
    "overallScore": 70,
    "dimensions": [
      {"key": "correctness", "score": 70, "weight": 0.5, "comment": "点评"},
      {"key": "completeness", "score": 70, "weight": 0.3, "comment": "点评"},
      {"key": "expression", "score": 70, "weight": 0.2, "comment": "点评"}
    ],
    "strengths": ["优点"],
    "improvements": ["改进点"],
    "referenceAnswer": "参考答案",
    "evidenceStatus": "VALID|INSUFFICIENT|INVALID"
  },
  "skillScores": [
    {"skillCode": "题目技能 code", "score": 70, "confidence": 0.8}
  ]
}
```
