# 简历解析 Agent接口资料

> 本文摘录自 [完整Agent模块接口文档](../../接口设计/02-Agent模块接口文档.md)，属于内部服务设计。仅Java业务后端调用，浏览器不能直接调用；不代表已经实现。

公共鉴权、幂等、运行管理、索引及数据生命周期见 [Agent公共接口与调用规范](../../接口设计/02-Agent模块接口文档.md)，字段定义见 [公共数据模型与联调约定](../数据模型、数据库设计与联调约定.md)。

## 解析简历

**接口地址**：`/internal/ai/v1/resumes/parse`

**请求方式**：`POST`

**请求数据类型**：`application/json`

**响应数据类型**：`application/json`

**接口描述**：仅Java ai-adapter调用。校验并持久化运行后返回202；最终结果类型为 AIParseResult。

**权限**：内部。

**请求示例**：

```json
{
  "taskId": "70000000-0000-4000-8000-000000000001",
  "userId": "10000000-0000-4000-8000-000000000001",
  "inputVersions": {
    "resumeVersionId": "20000000-0000-4000-8000-000000000002"
  },
  "deadlineAt": "2026-10-27T02:35:00Z",
  "payload": {
    "document": {
      "versionId": "20000000-0000-4000-8000-000000000002",
      "fileName": "Java后端简历.pdf",
      "mimeType": "application/pdf",
      "sizeBytes": 204800,
      "downloadUrl": "https://files.example.invalid/signed/resume.pdf"
    },
    "skillCatalog": [
      {
        "code": "redis",
        "name": "Redis"
      }
    ]
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
    "operation": "RESUME_PARSE",
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

**业务规则**：document为Java签发私有文件访问凭据；只允许配置的文件存储域名，不跟随跨域重定向；5分钟过期时通过Java文件授权接口重签。限制文件10MiB、提取文本≤60000字符、解析60秒；不执行宏，不调用外网URL。只提取事实及引用，unknown返回null。

## RESUME_PARSE / AIParseResult

```json
{
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
        "confirmed": false
      }
    ],
    "warnings": [
      "实习未计入全职经验"
    ]
  },
  "textHash": "0000000000000000000000000000000000000000000000000000000000000000",
  "unmappedSkills": []
}
```
