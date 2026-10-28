# 任务中心 Agent接口示例

以下为业务后端调用 Agent 的内部运行管理接口；任务中心前端应调用本目录后端接口，不能直接请求 Agent。Java 任务状态为权威状态，Agent 流的序号不能直接作为前端游标。完整约定见 [Agent公共接口与调用规范](../../接口设计/02-Agent模块接口文档.md)。

## 查询Agent运行

**接口地址**：`/internal/ai/v1/runs/{runId}`

**请求方式**：`GET`

**请求数据类型**：`无请求体`

**响应数据类型**：`application/json`

**接口描述**：供Java对账；成功时result符合operation对应结果Schema；终态结果至少保留7天。

**权限**：内部。

**请求示例**：

```http
GET /internal/ai/v1/runs/{runId} HTTP/1.1

```

**请求参数**：

| 参数名称 | 参数说明 | 请求类型 | 是否必须 | 数据类型 | schema |
| --- | --- | --- | --- | --- | --- |
| Authorization | Bearer 内部服务JWT | header | true | string |  |
| runId | 内部关联ID | path | true | uuid |  |

**响应状态**：

| 状态码 | 说明 | schema |
| --- | --- | --- |
| 200 | 操作成功 | CommonResult&lt;AIRunVO&gt; |
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
  "code": 200,
  "msg": "操作成功",
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

## 按任务ID定位运行

**接口地址**：`/internal/ai/v1/runs`

**请求方式**：`GET`

**请求数据类型**：`无请求体`

**响应数据类型**：`application/json`

**接口描述**：分发返回丢失时定位已创建运行，避免盲目重复执行。

**权限**：内部。

**请求示例**：

```http
GET /internal/ai/v1/runs HTTP/1.1

```

**请求参数**：

| 参数名称 | 参数说明 | 请求类型 | 是否必须 | 数据类型 | schema |
| --- | --- | --- | --- | --- | --- |
| Authorization | Bearer 内部服务JWT | header | true | string |  |
| taskId | Java任务ID；找不到404 | query | true | uuid |  |

**响应状态**：

| 状态码 | 说明 | schema |
| --- | --- | --- |
| 200 | 操作成功 | CommonResult&lt;AIRunVO&gt; |
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
  "code": 200,
  "msg": "操作成功",
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

## 请求取消Agent运行

**接口地址**：`/internal/ai/v1/runs/{runId}/cancel`

**请求方式**：`POST`

**请求数据类型**：`application/json`

**响应数据类型**：`application/json`

**接口描述**：协作式取消：停止后续步骤；模型请求不能立即中断时丢弃迟到结果，最终回调CANCELLED。

**权限**：内部。

**请求示例**：

```json
{}
```

**请求参数**：

| 参数名称 | 参数说明 | 请求类型 | 是否必须 | 数据类型 | schema |
| --- | --- | --- | --- | --- | --- |
| Authorization | Bearer 内部服务JWT | header | true | string |  |
| runId | 内部关联ID | path | true | uuid |  |

**响应状态**：

| 状态码 | 说明 | schema |
| --- | --- | --- |
| 200 | 操作成功 | CommonResult&lt;AIRunVO&gt; |
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
  "code": 200,
  "msg": "操作成功",
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

**业务规则**：Java是否接纳结果以Java任务行状态为准；Agent取消不能回滚已提交业务结果。

## 订阅Agent运行事件

**接口地址**：`/internal/ai/v1/runs/{runId}/events`

**请求方式**：`GET`

**请求数据类型**：无请求体。

**响应数据类型**：`text/event-stream`。

**接口描述**：仅Java订阅的运行进度与面试题目增量；最终结果必须通过回调/查询获取，不能按delta组装业务事实。

**请求参数**：

| 参数名称 | 参数说明 | 请求类型 | 是否必须 | 数据类型 | schema |
| --- | --- | --- | --- | --- | --- |
| Authorization | 内部JWT，ai:run及任务范围 | header | true | string | — |
| runId | 运行ID | path | true | uuid | — |
| Last-Event-ID | 最后收到的运行内seq | header | false | integer | — |

**请求示例**：

```http
GET /internal/ai/v1/runs/80000000-0000-4000-8000-000000000001/events HTTP/1.1
Accept: text/event-stream
Authorization: Bearer <internal-service-token>
Last-Event-ID: 5
```

**响应状态**：200流已建立；401/403鉴权失败；404运行不存在；410游标过期；429连接限额。流建立后的错误用error事件表达。

**响应参数**：id是运行内整数seq；event为accepted/stage/delta/result_ready/error/cancelled；data含runId、taskId、seq、payload。result_ready只表示Agent有结果，不表示Java已保存。delta.payload={text}；stage.payload={name,progress}；result_ready.payload={operation}；error.payload={code,message,retryable}；accepted.payload={}；cancelled.payload={}。每15秒注释心跳，心跳无id。持久事件保留24小时。

**响应示例**：

```text
id: 6
event: delta
data: {"runId":"80000000-0000-4000-8000-000000000001","taskId":"70000000-0000-4000-8000-000000000001","seq":6,"payload":{"text":"请介绍"}}

id: 7
event: result_ready
data: {"runId":"80000000-0000-4000-8000-000000000001","taskId":"70000000-0000-4000-8000-000000000001","seq":7,"payload":{"operation":"INTERVIEW_TURN"}}

```

Java对外事件必须重新映射为Java任务内seq，不能把Agent的seq直接当作浏览器续传游标。详见公共SSE协议。
