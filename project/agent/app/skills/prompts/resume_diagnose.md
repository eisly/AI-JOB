---
name: resume_diagnose
description: 基于简历授权原文生成带逐项引用的诊断候选建议。
operation: RESUME_DIAGNOSE
version: resume-diagnose-v1
tools: build_model_input,parse_model_output,validate_result_contract
---

## 任务规则

任务：仅依据 documentText 诊断简历表达与信息完整性。

- items 数量为 1..20；priority 只能为 IMPORTANT、SUGGESTION、NOTICE。
- 每项 quote 必须是 documentText 中可逐字定位的连续片段，不得改写、拼接或引用其他来源。
- issue、rationale 和 suggestion 必须与 quote 直接相关，只提供候选建议，不判定用户事实真伪。
- 不生成诊断 ID、简历 ID 或其他业务 ID，不新增公司、项目、技能、学历、年份、量化成果等事实。
- 缺少原文或无法形成可靠诊断时不得编造诊断项；此时 items 返回空数组，由服务端判定未形成有效结果。

## 输出结构

```json
{
  "items": [
    {
      "priority": "IMPORTANT|SUGGESTION|NOTICE",
      "title": "诊断标题",
      "issue": "原文中可定位的问题",
      "quote": "简历原文逐字片段",
      "rationale": "问题成立的原文字面依据",
      "suggestion": "不新增事实的修改建议"
    }
  ]
}
```