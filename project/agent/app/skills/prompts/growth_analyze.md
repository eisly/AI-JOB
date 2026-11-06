---
name: growth_analyze
description: 基于有效能力证据生成下一步成长建议并引用证据。
operation: GROWTH_ANALYZE
version: growth-analyze-v2
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：基于 Java 提供的有效能力证据生成下一步建议。

- suggestions 为 1..5 条可执行建议，不得修改或宣称修改能力分。
- citedEvidenceIds 只能使用 evidence 中存在的 id；没有引用时为空数组。
- 不发明项目经验，不把简历自述当作能力得分，不输出新的分数。

## 输出结构

```json
{
  "suggestions": ["1 到 5 条可执行建议"],
  "citedEvidenceIds": ["输入证据中存在的 UUID"]
}
```