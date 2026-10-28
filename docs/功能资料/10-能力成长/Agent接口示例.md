# 能力成长 Agent接口资料

> 本文摘录自 [完整Agent模块接口文档](../../接口设计/02-Agent模块接口文档.md)，属于内部服务设计。仅Java业务后端调用，浏览器不能直接调用；不代表已经实现。

公共鉴权、幂等、运行管理、索引及数据生命周期见 [Agent公共接口与调用规范](../../接口设计/02-Agent模块接口文档.md)，字段定义见 [公共数据模型与联调约定](../数据模型、数据库设计与联调约定.md)。

## 生成成长建议

**接口地址**：`/internal/ai/v1/growth/analyze`

**请求方式**：`POST`

**请求数据类型**：`application/json`

**响应数据类型**：`application/json`

**接口描述**：仅Java ai-adapter调用。校验并持久化运行后返回202；最终结果类型为 AIGrowthResult。

**权限**：内部。

**请求示例**：

```json
{
  "taskId": "70000000-0000-4000-8000-000000000001",
  "userId": "10000000-0000-4000-8000-000000000001",
  "inputVersions": {
    "profileVersion": 4
  },
  "deadlineAt": "2026-10-27T02:35:00Z",
  "payload": {
    "profileVersion": 4,
    "skills": [
      {
        "skillCode": "redis",
        "score": 70,
        "sampleCount": 1,
        "evidenceStatus": "LIMITED",
        "formulaVersion": "ability-v1-trial",
        "updatedAt": "2026-10-27T02:46:00Z"
      }
    ],
    "evidence": [
      {
        "id": "50000000-0000-4000-8000-000000000004",
        "skillCode": "redis",
        "sourceType": "LEARNING_ANSWER",
        "sourceId": "50000000-0000-4000-8000-000000000004",
        "score": 70,
        "confidence": 0.8,
        "status": "VALID",
        "rubricVersion": "technical-answer-v1",
        "createdAt": "2026-10-27T02:46:00Z"
      }
    ],
    "targetJobRequirements": {
      "educationMin": "BACHELOR",
      "graduationYearMin": 2026,
      "graduationYearMax": 2027,
      "experienceMonthsMin": 0,
      "skills": [
        "java",
        "mysql",
        "redis"
      ],
      "requiredSkillLevels": {
        "java": 70,
        "mysql": 65,
        "redis": 60
      },
      "quotes": {
        "educationMin": "本科及以上"
      }
    }
  }
}
```

**请求参数**：

| 参数名称 | 参数说明 | 请求类型 | 是否必须 | 数据类型 | schema |
| --- | --- | --- | --- | --- | --- |
| Authorization | Bearer 内部服务JWT | header | true | string |  |
| taskId | Java权威任务ID；operation+taskId唯一 | body | true | uuid |  |
| userId | 由Java从已认证请求上下文取得 | body | true | uuid |  |
| inputVersions | 固定版本，如profileVersion/jobVersion/resumeVersionId | body | true | object |  |
| deadlineAt | UTC绝对截止时间，不得超过接收后5分钟 | body | true | datetime |  |
| payload | 本操作专属载荷，见请求示例与规则 | body | true | object |  |
| Idempotency-Key | UUID；同键同体24小时重放原结果，同键异体409 | header | true | uuid |  |

**响应状态**：

| 状态码 | 说明 | schema |
| --- | --- | --- |
| 202 | 任务已受理；不表示结果成功 | CommonResult&lt;AIRunVO&gt; |
| 400 / 401 / 403 / 404 / 409 / 429 / 500 | 详见公共错误码 | CommonError |

**响应参数**：

| 参数名称 | 参数说明 | 类型 | schema |
| --- | --- | --- | --- |
| code | 业务码，200成功/202受理 | integer |  |
| msg | 提示信息 | string |  |
| data | 业务数据 | AIRunVO | AIRunVO |
| traceId | 关联排障ID | string |  |
| &emsp;runId | 运行ID | uuid |  |
| &emsp;taskId | Java任务 | uuid |  |
| &emsp;operation | 任务操作 | string |  |
| &emsp;status | QUEUED/RUNNING/SUCCEEDED/FAILED/CANCEL_REQUESTED/CANCELLED | string |  |
| &emsp;attempt | 重试尝试次数 | integer |  |
| &emsp;result | 按operation区分的结果 | object? |  |
| &emsp;error | code/message/retryable | object? |  |
| &emsp;lastEventId | 运行事件号 | integer |  |
| &emsp;createdAt | 接收时间 | datetime |  |

**响应示例**：

```json
{
  "code": 202,
  "msg": "任务已受理",
  "data": {
    "runId": "80000000-0000-4000-8000-000000000001",
    "taskId": "70000000-0000-4000-8000-000000000001",
    "operation": "GROWTH_ANALYZE",
    "status": "QUEUED",
    "attempt": 1,
    "result": null,
    "error": null,
    "lastEventId": 0,
    "createdAt": "2026-10-27T02:30:00Z"
  },
  "traceId": "demo-trace-20261027"
}
```

**业务规则**：最多100条脱敏证据，1..5条建议。引用必须属于输入证据；不发明项目经验、不修改事实或能力分。

## GROWTH_ANALYZE / AIGrowthResult

```json
{
  "suggestions": [
    "练习Redis失效场景"
  ],
  "citedEvidenceIds": []
}
```
