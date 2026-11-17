---
name: learning_practice_questions
description: 一次生成一组专项训练题，按配置混合选择题、填空题与主观题，并给出标准答案与评分量表。
operation: LEARNING_PRACTICE_QUESTIONS
version: learning-practice-questions-v1
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：按 config 一次生成一整组训练题（不要只生成一道题）。

- 严格生成 config.totalQuestions 道题，且题型数量必须与 config.typeDistribution 完全一致：choice / fill / subjective。
- 所有题的 skillCodes 必须包含 planItem.skillCode；难度必须遵循 config.difficulty：EASY / MEDIUM / HARD 时所有题统一使用该难度，MIXED 时可按题分配 EASY / MEDIUM / HARD。
- 若 config.direction 非空，题目内容必须围绕该技术方向与 planItem.skillCode 展开，不得生成与该方向无关的主题。
- 选择题（CHOICE）：考察基础概念与常识性知识点；必须提供 4 个 options（key 依次为 A/B/C/D），answerKey 只放一个正确选项 key，referenceAnswer 说明为什么正确及其他选项错在哪里。
- 填空题（FILL）：考察基础概念、关键词、语法或术语；必须给出 blankCount（1-5），answerKey 按空位顺序给出标准关键词（每空一个字符串），题干中用 ____ 表示空位。
- 主观题（SUBJECTIVE）：考察场景题、项目题、设计题或代码题；options 与 answerKey 为空数组、blankCount 为 0；referenceAnswer 必须给出可核对的要点、常见错误与验证清单；代码题明确只评估文字方案，不声称代码已运行通过。
- 每题都必须提供 rubric，固定使用 technical-answer-v1 的三个维度 correctness(0.5)、completeness(0.3)、expression(0.2)，不得改动权重。
- 题与题之间不得重复题干，也不得与 excludedQuestionStems 中的题干等价（不能只换名字/数字）。
- 题目要围绕 planItem.objective、reason、completionCriteria 展开，不能只根据技能名生成通用题。
- citations 只能引用 knowledgeRefs 中已有的 documentId、documentVersion、chunkId 与原文；没有依据时为空数组。
- score 为本题分值（1-100），同一组题目总分建议在 10 * totalQuestions 附近。

## 输出结构

```json
{
  "questions": [
    {
      "stem": "题干，填空题用 ____ 表示空位",
      "type": "CHOICE|FILL|SUBJECTIVE",
      "skillCodes": ["planItem.skillCode"],
      "difficulty": "EASY|MEDIUM|HARD",
      "options": [
        {"key": "A", "content": "选项内容"}
      ],
      "answerKey": ["A 或填空关键词"],
      "blankCount": 0,
      "score": 10,
      "referenceAnswer": "参考答案 / 判定依据",
      "rubric": {
        "version": "technical-answer-v1",
        "dimensions": [
          {"key": "correctness", "weight": 0.5},
          {"key": "completeness", "weight": 0.3},
          {"key": "expression", "weight": 0.2}
        ]
      },
      "citations": []
    }
  ]
}
```
