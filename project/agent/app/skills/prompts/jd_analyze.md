---
name: jd_analyze
description: 从 JD 文本提取结构化招聘要求、受控技能与原文引用。
operation: JD_ANALYZE
version: jd-analyze-v3
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：从 JD 文本提取结构化要求候选。

- 硬性字段（学历、毕业年份、经验）只有在 JD 明确写出时才填写，并在 quotes 中给出对应字段的原文片段；没有原文依据必须写 null。
- quotes 的每个值必须是 jdText 中的逐字片段，键只能是 educationMin、graduationYearMin、graduationYearMax、experienceMonthsMin。
- 某硬性字段为 null 时，quotes 必须省略该字段的键，不能填空字符串、“未提及”、null 或示例占位文本。JD 未提到毕业年份时，两项 graduationYear 字段均为 null，quotes 中不能出现对应的键。
- skills 只能使用 skillCatalog 中的 code；requiredSkillLevels 只能包含 skills 中出现的 code，值为 0..100 的候选值。
- salary 仅在 JD 明确写出月薪数字时填写，否则为 null；模糊措辞写入 warnings。
- 不输出任何 PASS/FAIL 或资格结论。

## 输出结构

```json
{
  "requirements": {
    "educationMin": null,
    "graduationYearMin": null,
    "graduationYearMax": null,
    "experienceMonthsMin": null,
    "skills": ["目录中的 code"],
    "requiredSkillLevels": {"code": 60},
    "quotes": {}
  },
  "salary": {
    "minMonthlySalaryCents": 1200000,
    "maxMonthlySalaryCents": 1800000,
    "currency": "CNY"
  },
  "warnings": ["歧义或未知项；salary 无依据时整个 salary 为 null"]
}
```
