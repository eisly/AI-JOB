# 模拟面试 Agent接口资料

> 本文摘录自 [完整Agent模块接口文档](../../接口设计/02-Agent模块接口文档.md)，属于内部服务设计。仅Java业务后端调用，浏览器不能直接调用；不代表已经实现。

公共鉴权、幂等、运行管理、索引及数据生命周期见 [Agent公共接口与调用规范](../../接口设计/02-Agent模块接口文档.md)，字段定义见 [公共数据模型与联调约定](../数据模型、数据库设计与联调约定.md)。

## 生成面试首题或下一轮

**接口地址**：`/internal/ai/v1/interviews/turn`

**请求方式**：`POST`

**请求数据类型**：`application/json`

**响应数据类型**：`application/json`

**接口描述**：仅Java ai-adapter调用。校验并持久化运行后返回202；最终结果类型为 AIInterviewTurnResult。

**权限**：内部。

**请求示例**：

```json
{
  "taskId": "70000000-0000-4000-8000-000000000001",
  "userId": "10000000-0000-4000-8000-000000000001",
  "inputVersions": {
    "profileVersion": 3,
    "resumeVersionId": "20000000-0000-4000-8000-000000000002",
    "jobVersion": 1,
    "interviewVersion": 1
  },
  "deadlineAt": "2026-10-27T02:35:00Z",
  "payload": {
    "interviewId": "60000000-0000-4000-8000-000000000001",
    "expectedVersion": 1,
    "questionLimit": 5,
    "maxFollowUps": 2,
    "profile": {
      "userId": "10000000-0000-4000-8000-000000000001",
      "profileVersion": 3,
      "targetRole": "JAVA_BACKEND",
      "candidateType": "CAMPUS",
      "cities": [
        "330100"
      ],
      "minMonthlySalaryCents": 1200000,
      "maxMonthlySalaryCents": 1800000,
      "facts": {
        "education": "BACHELOR",
        "graduationYear": 2027,
        "experienceMonths": 0,
        "major": "计算机科学与技术",
        "skills": [
          {
            "skillCode": "redis",
            "claim": "了解缓存设计",
            "quote": "使用Redis实现缓存",
            "location": "page:1;paragraph:5",
            "confirmed": true
          }
        ],
        "warnings": [
          "实习未计入全职经验"
        ]
      },
      "activeResumeVersionId": "20000000-0000-4000-8000-000000000002",
      "updatedAt": "2026-10-27T02:30:00Z"
    },
    "job": {
      "id": "30000000-0000-4000-8000-000000000001",
      "jobVersion": 1,
      "jdText": "Java后端，熟悉Redis"
    },
    "messages": [],
    "latestAnswer": null,
    "questionRubricVersion": "technical-answer-v1"
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
    "operation": "INTERVIEW_TURN",
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

**业务规则**：首题messages=[]且latestAnswer=null。后续latestAnswer为{messageId,questionMessageId,content}，messages为Java已提交的有序消息，≤62条。一次运行只处理一个用户回答；追问最多2次，主问题不超过questionLimit。Java校验问题计数和状态。流式delta仅限将展示给用户的题目文本，不暴露评价答案和思维链。

## 生成面试复盘报告

**接口地址**：`/internal/ai/v1/interviews/report`

**请求方式**：`POST`

**请求数据类型**：`application/json`

**响应数据类型**：`application/json`

**接口描述**：仅Java ai-adapter调用。校验并持久化运行后返回202；最终结果类型为 AIInterviewReportResult。

**权限**：内部。

**请求示例**：

```json
{
  "taskId": "70000000-0000-4000-8000-000000000001",
  "userId": "10000000-0000-4000-8000-000000000001",
  "inputVersions": {
    "interviewVersion": 3
  },
  "deadlineAt": "2026-10-27T02:35:00Z",
  "payload": {
    "interviewId": "60000000-0000-4000-8000-000000000001",
    "completionType": "EARLY_END",
    "messages": [
      {
        "id": "60000000-0000-4000-8000-000000000002",
        "seq": 1,
        "role": "ASSISTANT",
        "content": "请介绍你在项目中使用缓存的场景。",
        "questionNo": 1,
        "followUpNo": 0,
        "createdAt": "2026-10-27T03:00:00Z"
      }
    ],
    "turnEvaluations": [],
    "skillSnapshot": []
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
    "operation": "INTERVIEW_REPORT",
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

**业务规则**：输入为完整已保存会话和逐轮评价。不再重复评价/插入能力证据。无有效回答时NO_VALID_ANSWER、overallScore=null、维度空数组。report score=有效逐轮overallScore平均值；Java重算。

## INTERVIEW_TURN / AIInterviewTurnResult

```json
{
  "action": "ASK",
  "question": {
    "content": "请介绍你在项目中使用缓存的场景。",
    "questionNo": 1,
    "followUpNo": 0,
    "skillCodes": [
      "redis"
    ]
  },
  "answerEvaluation": null,
  "stopReason": null
}
```

## INTERVIEW_REPORT / AIInterviewReportResult

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
  "answeredQuestionCount": 3,
  "completionType": "EARLY_END"
}
```
