---
name: resume_parse
description: 从简历文本提取候选事实、受控技能与原文证据。
operation: RESUME_PARSE
version: resume-parse-v2
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：从简历文本提取候选事实。

- 只提取文本中明确出现的信息；毕业年份、专业、学历无法确定时写 null。
- skills 的 skillCode 必须来自 skillCatalog；目录中没有对应项时写入 unmappedSkills，不得自造 code。
- 每条 skill 的 quote 必须是 documentText 中的逐字片段，保持原文，不要改写；location 使用 page:N、paragraph:N 或 text:N。
- confirmed 必须为 false，不要输出 true。
- 实习不得自动计入全职 experienceMonths；如有歧义写入 warnings。

## 输出结构

```json
{
  "facts": {
    "education": "HIGH_SCHOOL|ASSOCIATE|BACHELOR|MASTER|DOCTOR|null",
    "graduationYear": 2027,
    "experienceMonths": 0,
    "major": "专业或 null",
    "skills": [
      {
        "skillCode": "目录中的 code",
        "claim": "简历自述",
        "quote": "原文逐字片段",
        "location": "page:1;paragraph:5",
        "confirmed": false
      }
    ],
    "warnings": ["歧义或未计入项"]
  },
  "unmappedSkills": ["无法映射到目录的技能原文"]
}
```