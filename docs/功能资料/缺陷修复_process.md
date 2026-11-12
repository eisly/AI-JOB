# AI-JOB 缺陷修复过程记录

### [2026-11-11 15:37] 修复浏览器验收报告中的 7 项缺陷

**任务目标：**

依据 `docs/.md` 的全功能浏览器验收缺陷清单，逐项定位并修复 7 个产品缺陷（P1 三项、P2 四项、P3 一项），修复后补对应测试并执行验证。

**执行行为：**

1. **P1 个人校招 JD 被标成社招**：确认根因是 `JobMapper.insertJobVersion` 把 `candidate_type` 硬编码为 `'EXPERIENCED'`。改为由调用方传入，并在 `JobService` 新增 `candidateType(...)` 推断：正文/标题含实习或 intern 判为 `INTERNSHIP`，含校招/校园招聘/应届/毕业生/`20xx届` 判为 `CAMPUS`，否则保持 `EXPERIENCED`。
2. **P1 搜索结果丢失原始字段和来源证据**：确认 `AIJobSearchItem`（Agent 结果模型）未声明 `companySizeText` 与 `fieldSources`，`extra="ignore"` 导致 `validate_result_contract("JOB_SEARCH", ...)` 静默丢弃。两项字段已补入结果模型。
3. **P1 跨档公司规模被误归单档**：`parse_company_size` 对区间只做“低值/高值是否落在 21–99”这类局部判断，`50-150人` 被归为 `SIZE_21_99`。改为区间必须完整落在同一档位才返回该档，跨档区间返回 `SIZE_UNKNOWN`，原文仍保存在 `companySizeText`。
4. **P2 百度岗位发布时间与官网详情不一致**：实测百度列表接口和详情页均同时提供 `publishDate` 与 `updateDate`（例如 J99649：发布时间 2026-04-28、更新日期 2026-07-21）。新增 `updatedAt` 贯通 Agent `SourcePosting`、结果 schema、pipeline、Java 结果接收与快照、前端类型与岗位详情展示，发布时间与官网更新日期分开展示，不再把列表日期笼统标成唯一“发布时间”。
5. **P2 全局顶栏拦截简历和面试页按钮**：`Header` 使用 `position:absolute` 且覆盖页面右上区域。给顶栏容器加 `pointer-events:none`，仅通知按钮与用户菜单恢复 `pointer-events:auto`，空白区域不再参与命中测试。
6. **P2 390px 窄屏导航 11 个图标失去名称**：根因是移动端媒体查询对 `.sidebar__menu-title` 使用 `display:none`，把菜单文本移出可访问树。改为视觉隐藏（`clip:rect(0,0,0,0)` 的 sr-only 方案），并给菜单项补充 `title` 与 `aria-label`。
7. **P2 工作台目标岗位卡片丢失已知公司**：`JobResultHandler` 写入 `job_match_item.source_snapshot_json` 时只保留 title/source/sourceUrl/verifiedAt，未带 companyName。快照已补 companyName、cityCode、candidateType，`JobService.matchItemVo` 一并回传。
8. **P3 以 127.0.0.1 打开前端注册返回 403**：`CorsConfig` 与 `OriginGuard` 都只接受单个 `frontendOrigin`。两处改为解析逗号分隔的允许来源列表，`application.yml` 默认值同时包含 `http://localhost:5173` 与 `http://127.0.0.1:5173`。

**文件变更：**

| 操作类型 | 文件路径 | 具体变更 | 修改原因 |
| -------- | -------- | -------- | -------- |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobMapper.java` | `insertJobVersion` 增加 `candidateType` 参数，移除硬编码 `'EXPERIENCED'` | P1 校招误标 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobService.java` | 新增 `candidateType(String)` 推断；个人 JD 入库传入推断值；`matchItemVo` 回传 companyName/cityCode/candidateType | P1 校招误标、P2 工作台公司缺失 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobResultHandler.java` | 匹配项快照补充 companyName、cityCode、candidateType | P2 工作台公司缺失 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobSearchResultHandler.java` | 接收并快照 `updatedAt`，合并逻辑同步；`fieldSources` 白名单加入 `updatedAt` | P2 百度日期口径 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/job/JobSearchService.java` | 搜索结果项输出 `updatedAt` | P2 百度日期口径 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/auth/security/OriginGuard.java` | 允许来源改为逗号分隔列表校验 | P3 127.0.0.1 注册 403 |
| 修改 | `project/backend/src/main/java/com/aijob/backend/config/CorsConfig.java` | CORS 允许来源改为逗号分隔列表 | P3 127.0.0.1 注册 403 |
| 修改 | `project/backend/src/main/resources/application.yml` | 默认 `frontend-origin` 增加 `http://127.0.0.1:5173` | P3 127.0.0.1 注册 403 |
| 修改 | `project/agent/app/schemas.py` | `AIJobSearchItem` 增加 `companySizeText`、`fieldSources`、`updatedAt` | P1 字段/证据丢失、P2 日期口径 |
| 修改 | `project/agent/app/search/models.py` | `SourcePosting` 增加 `updated_at`；`parse_company_size` 跨档区间返回 `SIZE_UNKNOWN` | P1 跨档规模、P2 日期口径 |
| 修改 | `project/agent/app/search/sources/baidu.py` | 解析列表接口 `updateDate` 并写入 `updated_at` | P2 百度日期口径 |
| 修改 | `project/agent/app/search/pipeline.py` | 结果项输出 `updatedAt` | P2 百度日期口径 |
| 修改 | `project/agent/app/search/dedupe.py` | 来源别名记录 `updatedAt` | P2 日期可追溯 |
| 修改 | `project/agent/app/search/evidence.py` | 字段证据分组加入 `updatedAt` | P2 日期可追溯 |
| 修改 | `project/frontend/src/api/types.ts` | `JobSearchItemVO` 增加 `updatedAt` | P2 日期口径 |
| 修改 | `project/frontend/src/views/job-search/JobSearchView.vue` | 岗位详情增加“官网更新”，字段依据支持“更新时间” | P2 日期口径 |
| 修改 | `project/frontend/src/layouts/components/Header.vue` | 顶栏容器 `pointer-events:none`，交互子元素恢复可点击 | P2 顶栏遮挡 |
| 修改 | `project/frontend/src/layouts/components/Sidebar.vue` | 窄屏菜单标题改 sr-only；菜单项补 title/aria-label | P2 窄屏可访问名称 |
| 修改 | `project/agent/tests/test_job_search.py` | 新增跨档规模用例 `50-150人 -> SIZE_UNKNOWN`、`2000-5000人 -> SIZE_1000_9999` | 回归覆盖 |
| 修改 | `project/agent/tests/test_job_search_sources.py` | 百度用例补充 publishDate/updateDate 断言 | 回归覆盖 |
| 修改 | `project/agent/tests/test_job_search_field_extraction.py` | 新增结果合约保留 companySizeText/fieldSources/updatedAt 的用例 | 回归覆盖 |
| 修改 | `project/backend/src/test/java/com/aijob/backend/job/JobSearchResultHandlerTest.java` | 断言快照保留 `updatedAt` | 回归覆盖 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/job/JobServiceCandidateTypeTest.java` | 覆盖校招、实习、社招三种推断 | 回归覆盖 |
| 新增 | `project/backend/src/test/java/com/aijob/backend/job/JobServiceMatchItemTest.java` | 断言匹配项快照中的 companyName/cityCode/candidateType 能回传给工作台卡片 | P2 工作台公司缺失回归 |

**执行命令与验证：**

- 命令：`conda run -n ai-job python -m pytest tests/test_job_search.py tests/test_job_search_sources.py tests/test_job_search_field_extraction.py tests/test_job_search_official_priority.py -q`
- 结果：通过，88 项。
- 命令：`conda run -n ai-job python -m pytest tests -q`
- 结果：失败 3 项，全部位于 `tests/test_job_search_render.py`，失败原因为“浏览器依赖未安装”（`PAGE_ACCESS_FAILED`），本次未改动渲染模块，属环境性失败；其余用例通过。
- 命令：`mvn -q "-Dtest=JobServiceCandidateTypeTest,JobSearchResultHandlerTest,JobMapperSqlTest,CompanySizesTest" test`
- 结果：通过（无编译错误、无测试失败）。
- 命令：`mvn -q -Dtest=JobServiceMatchItemTest test`
- 结果：通过。快照中的 companyName、cityCode、candidateType 均能回传。
- 命令：`npm run type-check`
- 结果：通过。
- 命令：`npm run build`
- 结果：通过，产物构建成功（仅既有 chunk 体积警告）。
- 命令：真实请求百度招聘列表接口（`recruitType=INTERN search=AI 应用开发`）+ 调用 `_postings`
- 结果：通过。实测 `智能体应用开发实习生（J99649）` 输出 `publishedAt=2026-04-28`、`updatedAt=2026-07-21`，与缺陷报告中的官网详情日期一致；`parse_company_size("50-150人")` 返回 `SIZE_UNKNOWN`。
- 未执行：P2-5、P2-6 的浏览器复跑（需要启动前后端并使用 Playwright）；P2-7 的 UI 端到端复跑（逻辑层已有 `JobServiceMatchItemTest` 覆盖，UI 卡片未复跑）。上述部分仅完成代码修复与编译/类型检查验证。

**影响范围：**

- 个人 JD 导入后的 `candidate_type` 不再固定为社招，会影响后续按求职类型筛选与匹配判断；标题/正文无校招或实习证据时仍默认 `EXPERIENCED`。
- Agent 搜索结果新增字段属于向后兼容扩展；历史结果不受影响。
- 跨档公司规模改判为 `SIZE_UNKNOWN` 后，若用户未勾选“包含规模未知”，该类岗位会被规模筛选排除；原文仍保留用于展示。
- 搜索岗位新增“官网更新”展示，仅百度等提供 `updateDate` 的来源有值。
- 后端允许来源默认增加 127.0.0.1；显式设置 `APP_FRONTEND_ORIGIN` 时仍按其逗号分隔值生效。

**遗留问题：**

- P2-5、P2-6、P2-7 尚未在浏览器中复跑（涉及响应式布局、可访问树与工作台卡片），建议后续按验收报告的复现步骤补一次 UI 验证；P2-7 的 Java 逻辑层已由 `JobServiceMatchItemTest` 覆盖。
- 全量 Python 测试的 3 项渲染用例失败依赖本机 Playwright 浏览器依赖，与本轮改动无关，未在本次修复。