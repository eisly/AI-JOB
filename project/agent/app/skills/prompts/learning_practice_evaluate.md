---
name: learning_practice_evaluate
description: 对整组训练中的主观题作答逐题评估，并汇总整组训练反馈报告。
operation: LEARNING_PRACTICE_EVALUATE
version: learning-practice-evaluate-v1
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：对一组训练中的主观题回答逐题评估，并给出整组反馈。

- 必须为 answers 中每一道题返回且只返回一条 evaluations，answerId 原样回传，不得遗漏、合并或新增。
- 若 answers 为空（全客观题训练组），evaluations 返回空数组，只输出 report。
- 每题评估的 rubricVersion 必须等于该题输入 rubric.version（technical-answer-v1），维度只能包含 correctness、completeness、expression，权重不得改动。
- 对每题：score 按 0-100 打分，且必须与输入 rubric 维度一致；说明具体扣分点；referenceAnswer 使用题目给定的参考答案要点。
- 回答明显无效（空白、乱输入、与题目无关）时：evidenceStatus 使用 INVALID，dimensions 为空数组，overallScore 为 null，并说明原因。
- 回答明确表示"不会/不知道"时：evidenceStatus 使用 INVALID，并在 evaluation.comment 中记录，便于后端进入错题本。
- 回答相关但要点不足时：evidenceStatus 使用 INSUFFICIENT，整体分可为 null，说明缺口。
- skillScores 只能为本题 skillCodes 中的技能打分，score 0-100、confidence 0-1。
- report.summary 用 1-3 句话总结整组表现；strengths 与 improvements 各不超过 5 条，且必须结合本次作答，不要写空话。
- 不输出录用结论、通过/不通过判定，也不编造未作答的内容。

## 输出结构

```json
{
  "evaluations": [
    {
      "answerId": "输入 answers[].answerId",
      "evaluation": {
        "overallScore": 78.5,
        "dimensions": [
          {"key": "correctness", "score": 80, "weight": 0.5, "comment": "扣分点说明"},
          {"key": "completeness", "score": 75, "weight": 0.3, "comment": "缺失要点"},
          {"key": "expression", "score": 80, "weight": 0.2, "comment": "表达情况"}
        ],
        "strengths": ["做得好的点"],
        "improvements": ["可改进的点"],
        "referenceAnswer": "本题参考要点",
        "evidenceStatus": "VALID|INSUFFICIENT|INVALID"
      },
      "skillScores": [
        {"skillCode": "题目技能编码", "score": 75, "confidence": 0.7}
      ]
    }
  ],
  "report": {
    "summary": "整组训练总结",
    "strengths": ["整体优点"],
    "improvements": ["整体改进建议"]
  }
}
```
