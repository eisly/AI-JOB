# 阶段 0 证据结论：智能简历优化·定向优化连续失败

## 任务信息
- 失败任务链：1376c2a6（原任务，careerObjective 段失败，2026-11-23 12:01）→ 9deb692b（重试任务，project1 段失败，12:08），retry_of_task_id 确认链路
- 资源：resume_optimization f3a46c22（先绑原任务、后重指向重试任务），resume_version c235b4ef（versionNo=1），job 3de4d03c
- 历史对照：0180089f 成功（不同简历版本）；0a24b3d2 因 finish_reason=length 失败（截断问题实证）

## 复现方式（标注：重构复现）
原始模型输出未持久化（ai_task_event 仅存 reasoning/stage/error，handlers 修复循环不在日志留全文）。
本复现用失败任务 9deb692b 的同一 input_snapshot_json（HEX 导出防转义），经与生产完全一致的
prepare→build_prompt→DeepSeek 真实调用（thinking 默认开，max_tokens=16384）→parse→validate 路径。

## 实际差异（脱敏最小差异，±12 字符上下文）
模型输出 7 段，16 项引用中 8 项失败，分两类：

### 形态 A：引文跳过交错插入字符（4 项，2 段）
- 段 internship7_punctuation / master_courses_order
- 首不匹配示例：
  - 引文侧：…ai监控设备项目集成交付工[作]，编写视联智盒ai监控设…
  - 原文侧：…ai监控设备项目集成交付工[自我评价]作，编写视联智盒a…（"自我评价"标题被提取交错嵌入）
  - 引文侧：…高级软件工程、高级人[工]智能等。…
  - 原文侧：…高级软件工程、高级人[余骏轩]工智能等。…（姓名被交错嵌入课程行）
- 差异类型：字符级真实差异（quote_in_text 已删除空白并转小写仍不匹配——排除空白/换行/大小写假设；非字面 \n）

### 形态 B：被拆散的原行被模型重组（4 项，2 段）
- 段 job_intention_order / professional_ability_rewording
- 引文前 10 字可在原文命中锚点（offset 587 / 845），但原文该位置后续是交错的其他区域内容
 （"4. 基于 vue…"技能列表项 / "1. 2022 年…训练营"获奖列表项），模型按语义重组了原行

## 根因结论
pdf-layout-v2 文本提取的阅读顺序交错（姓名/自我评价标题/技能列表/获奖列表等区域内容被嵌入
主栏文本行中，readOrderVerified=true 与实际交错矛盾）。模型按人类阅读序引用/重组 → 与快照
逐字不一致 → Agent 宽松校验（忽略空白+大小写）也失败 → MODEL_OUTPUT_INVALID。
修复重试失败的原因：repair prompt 只报"必须来自简历授权原文"，未提供差异位置与可引用锚点。

## 佐证
- 快照 document.text（1436 chars，[page:N] 标记，pdf-layout-v2）≠ resume_version.extracted_text
 （1427 chars，自然阅读序）——ResumeOptimizationService.snapshot() 每次建任务 reExtract 重提
 （本任务内部自洽：Agent 与 Java requireSource 均用快照文本，同源）
- 本次复现无截断（9656 output tokens，JSON 完整）；历史 0a24b3d2 截断实证（thinking 消耗预算）

## 修复决策（依据根因与成本比较）
行级片段 ID（Agent 内部实现，对外接口零变更）：
- prepare() 将 documentText 逐行编号并内联标记（[L12] 原文行），模型引用行 ID
- 服务端按行 offset 从原文逐字切取回填 originalText / sourceQuotes（不改标点、不补文字、不跨段拼接）
- 重复片段天然无歧义（ID 即位置）；未知 ID / 越界 / 不相邻 → 明确可执行错误（含合法 ID 范围）
- 双端校验保留为红线兜底（回填内容必为快照子串，Agent 宽松与 Java 严格 contains 均必过）
- RESUME_OPTIMIZE 关闭 thinking（仅此操作，稳定性：避免 thinking 消耗 16K 输出预算）

## 页面状态核对（批次 4 输入）
DB 中该用户 3 条 optimization：f3a46c22=FAILED（绑重试任务）、10f2e6d2=READY、de330495=FAILED。
当前无 GENERATING 记录；"历史优化稿显示生成中"待读前端代码定位（疑为前端对失败任务 stage
progress 或轮询状态的展示问题），只修改确认存在的问题。