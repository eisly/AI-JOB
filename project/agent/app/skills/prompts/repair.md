---
name: repair
description: 模型输出未通过校验时使用的修复提示模板。
version: repair-v2
---

上一次输出未通过{error_kind} 校验。请根据校验错误修正结果，并严格遵守原任务规则与输出结构。

校验错误：
{validation_error}

上一次输出（截断）：
{previous_output}

现在只输出修正后的 JSON 对象，不要输出 Markdown 代码围栏、解释或思维过程。