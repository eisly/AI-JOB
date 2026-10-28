# 专项训练 Agent接口资料

> 本文摘录自 [完整Agent模块接口文档](../../接口设计/02-Agent模块接口文档.md)，属于内部服务设计。仅Java业务后端调用，浏览器不能直接调用；不代表已经实现。

公共鉴权、幂等、运行管理、索引及数据生命周期见 [Agent公共接口与调用规范](../../接口设计/02-Agent模块接口文档.md)，字段定义见 [公共数据模型与联调约定](../数据模型、数据库设计与联调约定.md)。

## 生成差距训练计划

**接口地址**：`/internal/ai/v1/learning/plans`

**请求方式**：`POST`

**请求数据类型**：`application/json`

**响应数据类型**：`application/json`

**接口描述**：仅Java ai-adapter调用。校验并持久化运行后返回202；最终结果类型为 AIPlanResult。

**权限**：内部。

**请求示例**：

```json
{
  "taskId": "70000000-0000-4000-8000-000000000001",
  "userId": "10000000-0000-4000-8000-000000000001",
  "inputVersions": {
    "profileVersion": 3
  },
  "deadlineAt": "2026-10-27T02:35:00Z",
  "payload": {
    "matchItem": {
      "id": "40000000-0000-4000-8000-000000000002",
      "jobId": "30000000-0000-4000-8000-000000000001",
      "jobVersion": 1,
      "title": "Java 后端开发",
      "eligibility": {
        "status": "PASS",
        "ruleVersion": "eligibility-v1",
        "checks": [
          {
            "field": "education",
            "result": "PASS",
            "actual": "BACHELOR",
            "expected": "BACHELOR",
            "reason": "学历满足最低要求",
            "sourceQuote": "本科及以上"
          }
        ]
      },
      "matchScore": 86,
      "scoreVersion": "match-v1-trial",
      "components": [
        {
          "key": "skill",
          "value": 0.8,
          "weight": 0.6,
          "provenance": "Java canonical-skill-v1"
        },
        {
          "key": "semantic",
          "value": 0.9,
          "weight": 0.25,
          "provenance": "Agent semantic-v1"
        },
        {
          "key": "preference",
          "value": 1,
          "weight": 0.15,
          "provenance": "Java city-role-v1"
        }
      ],
      "gaps": [
        {
          "skillCode": "redis",
          "currentScore": null,
          "targetScore": 60,
          "gap": null,
          "evidenceStatus": "MISSING",
          "evidenceIds": [],
          "suggestion": "补充Redis缓存设计场景训练"
        }
      ],
      "reasons": [
        "方向一致，Redis实战证据不足"
      ],
      "source": {
        "id": "90000000-0000-4000-8000-000000000001",
        "name": "企业官网（演示）",
        "type": "OFFICIAL",
        "baseUrl": "https://jobs.example.invalid",
        "verificationStatus": "VERIFIED",
        "verifiedAt": "2026-10-27T01:00:00Z"
      },
      "sourceUrl": "https://jobs.example.invalid/java-001",
      "verifiedAt": "2026-10-27T01:30:00Z"
    },
    "profileVersion": 3,
    "dailyMinutes": 30,
    "days": 7,
    "allowedSkillCodes": [
      "redis"
    ],
    "knowledgeRefs": []
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
    "operation": "LEARNING_PLAN",
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

**业务规则**：计划1..20项，每项5..120分钟，总时长≤dailyMinutes×days。仅使用允许技能和经授权知识引用。READING/PRACTICE/INTERVIEW三种计划项，无GitHub实战、付费课程和自动投递。

## 选题或动态出题

**接口地址**：`/internal/ai/v1/learning/questions`

**请求方式**：`POST`

**请求数据类型**：`application/json`

**响应数据类型**：`application/json`

**接口描述**：仅Java ai-adapter调用。校验并持久化运行后返回202；最终结果类型为 AIQuestionResult。

**权限**：内部。

**请求示例**：

```json
{
  "taskId": "70000000-0000-4000-8000-000000000001",
  "userId": "10000000-0000-4000-8000-000000000001",
  "inputVersions": {
    "profileVersion": 3
  },
  "deadlineAt": "2026-10-27T02:35:00Z",
  "payload": {
    "sessionId": "50000000-0000-4000-8000-000000000002",
    "planItem": {
      "skillCode": "redis",
      "difficulty": "MEDIUM"
    },
    "candidateQuestions": [],
    "excludedQuestionIds": [],
    "knowledgeRefs": []
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
    "operation": "LEARNING_QUESTION",
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

**业务规则**：payload.mode为SELECT_OR_GENERATE（默认）或REUSE_ONLY。前者优先候选库题；候选不适用时可生成题，必须输出参考答案和完整量表。错题重练使用REUSE_ONLY并传唯一原题候选，必须返回同一sourceQuestionId，不得改题。每次仅一道题。Java复用库题时以库内版本为准，不采信模型改写的题干/答案；生成题经校验后保存。前端QuestionVO不含隐藏答案。知识引用须有授权、版本和chunk定位。

## 评价训练回答

**接口地址**：`/internal/ai/v1/learning/evaluate`

**请求方式**：`POST`

**请求数据类型**：`application/json`

**响应数据类型**：`application/json`

**接口描述**：仅Java ai-adapter调用。校验并持久化运行后返回202；最终结果类型为 AIEvaluateResult。

**权限**：内部。

**请求示例**：

```json
{
  "taskId": "70000000-0000-4000-8000-000000000001",
  "userId": "10000000-0000-4000-8000-000000000001",
  "inputVersions": {
    "profileVersion": 3,
    "questionVersion": 1
  },
  "deadlineAt": "2026-10-27T02:35:00Z",
  "payload": {
    "answerId": "50000000-0000-4000-8000-000000000004",
    "question": {
      "id": "50000000-0000-4000-8000-000000000003",
      "questionVersion": 1,
      "stem": "如何处理缓存穿透？",
      "skillCodes": [
        "redis"
      ],
      "referenceAnswer": "参数校验、布隆过滤器与空值缓存",
      "rubric": {
        "version": "technical-answer-v1",
        "dimensions": [
          {
            "key": "correctness",
            "weight": 0.5
          },
          {
            "key": "completeness",
            "weight": 0.3
          },
          {
            "key": "expression",
            "weight": 0.2
          }
        ]
      }
    },
    "answer": "先校验参数，再用布隆过滤器和空值缓存。"
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
    "operation": "LEARNING_EVALUATE",
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

**业务规则**：回答1..10000字符；仅按输入量表评价，不接受用户改变评分规则。每个技能必须能追溯至当前answerId；无关/空白/无法评价内容输出INSUFFICIENT或INVALID，不创建计分证据。

## LEARNING_PLAN / AIPlanResult

```json
{
  "title": "Redis场景专项",
  "items": [
    {
      "skillCode": "redis",
      "title": "缓存穿透训练",
      "kind": "PRACTICE",
      "estimatedMinutes": 20,
      "resourceRefs": []
    }
  ]
}
```

## LEARNING_QUESTION / AIQuestionResult

```json
{
  "stem": "如何处理缓存穿透？",
  "skillCodes": [
    "redis"
  ],
  "difficulty": "MEDIUM",
  "type": "TEXT_SCENARIO",
  "sourceType": "GENERATED",
  "sourceQuestionId": null,
  "referenceAnswer": "参数校验、布隆过滤器和短期空值缓存。",
  "rubric": {
    "version": "technical-answer-v1",
    "dimensions": [
      {
        "key": "correctness",
        "weight": 0.5
      },
      {
        "key": "completeness",
        "weight": 0.3
      },
      {
        "key": "expression",
        "weight": 0.2
      }
    ]
  },
  "citations": []
}
```

## LEARNING_EVALUATE / AIEvaluateResult

```json
{
  "evaluation": {
    "overallScore": 70,
    "dimensions": [
      {
        "key": "correctness",
        "score": 70,
        "weight": 0.5,
        "comment": "关键方案正确，需补充边界"
      },
      {
        "key": "completeness",
        "score": 70,
        "weight": 0.3,
        "comment": "补充过期策略"
      },
      {
        "key": "expression",
        "score": 70,
        "weight": 0.2,
        "comment": "结构清晰"
      }
    ],
    "strengths": [
      "提出布隆过滤器"
    ],
    "improvements": [
      "补充空值缓存过期策略"
    ],
    "referenceAnswer": "结合参数校验、布隆过滤器和短期空值缓存。",
    "rubricVersion": "technical-answer-v1",
    "modelVersion": "configured-qwen-model",
    "promptVersion": "answer-evaluate-v1",
    "evidenceStatus": "VALID"
  },
  "skillScores": [
    {
      "skillCode": "redis",
      "score": 70,
      "confidence": 0.8,
      "sourceId": "50000000-0000-4000-8000-000000000004"
    }
  ]
}
```
