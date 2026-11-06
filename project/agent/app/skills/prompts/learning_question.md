---
name: learning_question
description: 生成带固定评分量表、参考要点与授权知识引用的训练题。
operation: LEARNING_QUESTION
version: learning-question-v2
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：生成一道训练题。

- 只生成一道题，题型为 TEXT_SCENARIO；skillCodes 必须包含 planItem.skillCode。
- rubric 必须使用 technical-answer-v1 的三个固定维度 correctness(0.5)、completeness(0.3)、expression(0.2)，不得改动权重。
- referenceAnswer 必须给出可核对的参考要点。
- citations 只能引用 knowledgeRefs 中已有的 documentId、documentVersion、chunkId 和原文，没有依据时为空数组。

## 输出结构

```json
{
  "stem": "题干",
  "skillCodes": ["planItem.skillCode"],
  "difficulty": "EASY|MEDIUM|HARD",
  "type": "TEXT_SCENARIO",
  "sourceType": "GENERATED",
  "sourceQuestionId": null,
  "referenceAnswer": "参考答案",
  "rubric": {
    "version": "technical-answer-v1",
    "dimensions": [
      {"key": "correctness", "weight": 0.5},
      {"key": "completeness", "weight": 0.3},
      {"key": "expression", "weight": 0.2}
    ]
  },
  "citations": [
    {
      "documentId": "输入知识引用 ID",
      "documentVersion": 1,
      "chunkId": "输入 chunk",
      "quote": "输入引用原文"
    }
  ]
}
```