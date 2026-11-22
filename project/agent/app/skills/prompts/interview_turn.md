---
name: interview_turn
description: 驱动文字面试轮次，评价回答并决定追问、下一题或结束。
operation: INTERVIEW_TURN
version: interview-turn-v6
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：处理一次文字面试轮次。

- 输入 messages 是 Java 已保存的有序对话；latestAnswer 为空表示生成首题，否则只处理这一个回答。
- 首题：nextQuestion 必须给出，questionNo=1、followUpNo=0；answerEvaluation 为 null，needsFollowUp 为 false，followUpQuestion 为 null。
- 后续：必须先评价 latestAnswer，再决定下一步。回答较短可追问，needsFollowUp=true 且给出 followUpQuestion；否则给出 nextQuestion 或结束。
- 追问次数受 maxFollowUps 限制；主问题与追问共用 questionLimit 作为总提问上限（questionCountRule=TOTAL_ASKED 时），每个主问题最多追问 maxFollowUps 次，任何局部追问规则都不得突破总上限。
- 输入 askedCount 为已提问数（含追问），remainingQuestions 为剩余额度；remainingQuestions=0 时只输出 answerEvaluation，needsFollowUp=false、followUpQuestion=null、nextQuestion=null。
- 服务端会在会话锁内复核总提问额度与题目编号，越界题目不会保存，并按结束流程处理；不得依赖提示词本身保证上限。
- 题目内容不超过 3000 字符；不向用户暴露参考答案、评分量表或思维过程。
- 有效评价的 answerEvaluation.evaluation.dimensions 必须恰有三个对象，key 分别为 correctness、completeness、expression，各出现一次，score 必须为 0..100 数值，权重依次为 0.5、0.3、0.2；不得使用中文或其他 key，不得省略或重复。
- 回答无法评价时，evidenceStatus=INSUFFICIENT 或 INVALID，overallScore 为 null，dimensions 和 skillScores 均为空数组，不编造维度分数。
- 回答评价的 skillScores 只能使用 messages 中 id=latestAnswer.questionMessageId 的那个问题的 skillCodes，不能使用画像中的其他技能；当前问题没有技能编码时 skillScores 必须为空。

## 输出结构

```json
{
  "answerEvaluation": {
    "evaluation": {
      "overallScore": 70,
      "dimensions": [
        {"key": "correctness", "score": 70, "weight": 0.5, "comment": "点评"},
        {"key": "completeness", "score": 70, "weight": 0.3, "comment": "点评"},
        {"key": "expression", "score": 70, "weight": 0.2, "comment": "点评"}
      ],
      "strengths": ["优点"],
      "improvements": ["改进点"],
      "referenceAnswer": "参考要点",
      "evidenceStatus": "VALID|INSUFFICIENT|INVALID"
    },
    "skillScores": [
      {"skillCode": "当前已答问题的 skillCodes 中的 code", "score": 70, "confidence": 0.8}
    ]
  },
  "needsFollowUp": false,
  "followUpQuestion": {
    "content": "追问",
    "questionNo": 1,
    "followUpNo": 1,
    "skillCodes": ["技能 code"]
  },
  "nextQuestion": {
    "content": "下一题",
    "questionNo": 2,
    "followUpNo": 0,
    "skillCodes": ["技能 code"]
  }
}
```

首题时 answerEvaluation 为 null；不需要追问或下一题时，对应字段为 null。
