import { computed, getCurrentScope, onScopeDispose, ref, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { taskApi } from '@/api/tasks'
import { ApiError } from '@/api/request'
import { apiUrl } from '@/api/request'
import { createAuthHeaders } from '@/api/session'
import type { TaskStatus, TaskVO } from '@/api/types'

interface TaskEventPayload {
  text?: string
  name?: string
  progress?: number | null
  code?: string
  message?: string
  retryable?: boolean
}

interface UseTaskOptions {
  onDone?: (task: TaskVO) => void | Promise<void>
  onTerminal?: (task: TaskVO) => void | Promise<void>
  onTask?: (task: TaskVO) => void
  pollInterval?: number
  maxReconnects?: number
}

const terminalStatuses: TaskStatus[] = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED']

export function isTerminalTask(status?: TaskStatus | null) {
  return !!status && terminalStatuses.includes(status)
}

export function useTask(taskIdSource: MaybeRefOrGetter<string | null | undefined>, options: UseTaskOptions = {}) {
  const taskId = computed(() => toValue(taskIdSource) || '')
  const task = ref<TaskVO | null>(null)
  const deltaText = ref('')
  const reasoningText = ref('')
  const loading = ref(false)
  const connected = ref(false)
  const error = ref<unknown>(null)
  const lastSeq = ref(0)
  let controller: AbortController | null = null
  let pollTimer: number | undefined
  let reconnectTimer: number | undefined
  let generation = 0
  let reconnects = 0
  let polling = false
  let notifiedTerminal = ''
  const seen = new Set<string>()

  function clearTimers() {
    if (pollTimer !== undefined) window.clearInterval(pollTimer)
    if (reconnectTimer !== undefined) window.clearTimeout(reconnectTimer)
    pollTimer = undefined
    reconnectTimer = undefined
  }

  function stop() {
    generation += 1
    controller?.abort()
    controller = null
    clearTimers()
    connected.value = false
  }

  function applyTask(value: TaskVO) {
    task.value = value
    options.onTask?.(value)
  }

  async function finish(run: number, value: TaskVO) {
    if (run !== generation) return
    applyTask(value)
    if (!isTerminalTask(value.status)) return
    clearTimers()
    controller?.abort()
    connected.value = false
    const terminalKey = `${value.id}:${value.status}:${value.finishedAt || ''}`
    if (notifiedTerminal === terminalKey) return
    notifiedTerminal = terminalKey
    await options.onTerminal?.(value)
    if (run === generation && value.status === 'SUCCEEDED') await options.onDone?.(value)
  }

  async function refreshAuthority(run: number) {
    try {
      const value = await taskApi.get(taskId.value)
      if (run !== generation) return value
      await finish(run, value)
      return value
    } catch (reason) {
      if (run === generation) error.value = reason
      throw reason
    }
  }

  function scheduleReconnect(run: number) {
    if (run !== generation || isTerminalTask(task.value?.status)) return
    const maximum = options.maxReconnects ?? 3
    if (reconnects >= maximum) {
      error.value = new ApiError('事件流已断开，已停止自动重连；任务仍会通过轮询恢复', { key: 'SSE_RECONNECT_LIMIT', retryable: true })
      return
    }
    reconnects += 1
    const delay = 1000 * 2 ** (reconnects - 1)
    reconnectTimer = window.setTimeout(() => void openStream(run), delay)
  }

  async function handleEvent(run: number, eventName: string, eventId: string, rawData: string) {
    if (run !== generation || !rawData) return
    let data: { taskId?: string; seq?: number; payload?: TaskEventPayload }
    try {
      data = JSON.parse(rawData)
    } catch {
      return
    }
    const seq = Number(data.seq ?? eventId)
    if (!Number.isInteger(seq) || seq <= 0 || data.taskId !== taskId.value) return
    const key = `${taskId.value}:${seq}`
    if (seen.has(key) || seq <= lastSeq.value) return
    seen.add(key)
    lastSeq.value = seq
    const payload = data.payload || {}
    if (eventName === 'stage' && task.value) {
      applyTask({ ...task.value, stage: payload.name ?? task.value.stage, progress: payload.progress ?? null, lastEventId: Math.max(task.value.lastEventId, seq) })
    } else if (eventName === 'delta') {
      deltaText.value += payload.text || ''
    } else if (eventName === 'reasoning') {
      reasoningText.value = payload.text || ''
    } else if (eventName === 'done' || eventName === 'error' || eventName === 'cancelled') {
      try {
        await refreshAuthority(run)
      } catch {
        return
      }
    }
  }

  async function consumeStream(run: number, body: ReadableStream<Uint8Array>) {
    const reader = body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (run === generation) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done }).replace(/\r\n/g, '\n')
      const blocks = buffer.split('\n\n')
      buffer = blocks.pop() || ''
      for (const block of blocks) {
        let eventName = 'message'
        let eventId = ''
        const data: string[] = []
        for (const line of block.split('\n')) {
          if (!line || line.startsWith(':')) continue
          const separator = line.indexOf(':')
          const field = separator < 0 ? line : line.slice(0, separator)
          const content = separator < 0 ? '' : line.slice(separator + 1).replace(/^ /, '')
          if (field === 'event') eventName = content
          if (field === 'id') eventId = content
          if (field === 'data') data.push(content)
        }
        await handleEvent(run, eventName, eventId, data.join('\n'))
      }
      if (done) break
    }
  }

  async function openStream(run: number) {
    if (run !== generation || !taskId.value || isTerminalTask(task.value?.status)) return
    controller?.abort()
    controller = new AbortController()
    try {
      const response = await fetch(apiUrl(`/api/v1/tasks/${taskId.value}/events`), {
        headers: {
          Accept: 'text/event-stream',
          ...createAuthHeaders(lastSeq.value || undefined),
        },
        credentials: 'include',
        signal: controller.signal,
      })
      if (run !== generation) return
      if (response.status === 410) {
        const value = await refreshAuthority(run)
        if (!isTerminalTask(value.status)) {
          lastSeq.value = value.lastEventId
          seen.clear()
          deltaText.value = ''
          scheduleReconnect(run)
        }
        return
      }
      if (response.status === 401) {
        await refreshAuthority(run)
        scheduleReconnect(run)
        return
      }
      if (!response.ok) {
        let message = `事件流连接失败（HTTP ${response.status}）`
        let traceId = response.headers.get('x-trace-id') || ''
        try {
          const payload = await response.json()
          message = payload.msg || message
          traceId = payload.traceId || traceId
        } catch {
          message = `事件流连接失败（HTTP ${response.status}）`
        }
        error.value = new ApiError(message, { status: response.status, traceId, retryable: response.status === 429 || response.status >= 500 })
        if (response.status === 429 || response.status >= 500) scheduleReconnect(run)
        return
      }
      if (!response.body) throw new ApiError('事件流响应为空', { key: 'EMPTY_SSE', retryable: true })
      connected.value = true
      error.value = null
      await consumeStream(run, response.body)
      connected.value = false
      if (run === generation && !isTerminalTask(task.value?.status)) scheduleReconnect(run)
    } catch (reason) {
      connected.value = false
      if (run !== generation || (reason instanceof DOMException && reason.name === 'AbortError')) return
      error.value = reason
      scheduleReconnect(run)
    }
  }

  async function poll(run: number) {
    if (polling || run !== generation || isTerminalTask(task.value?.status)) return
    polling = true
    try {
      await refreshAuthority(run)
    } catch {
      return
    } finally {
      polling = false
    }
  }

  async function start() {
    stop()
    const run = generation
    task.value = null
    deltaText.value = ''
    reasoningText.value = ''
    error.value = null
    connected.value = false
    lastSeq.value = 0
    reconnects = 0
    notifiedTerminal = ''
    seen.clear()
    if (!taskId.value) return
    loading.value = true
    try {
      const value = await refreshAuthority(run)
      if (run !== generation || isTerminalTask(value.status)) return
      void openStream(run)
      pollTimer = window.setInterval(() => void poll(run), options.pollInterval ?? 4000)
    } catch {
      return
    } finally {
      if (run === generation) loading.value = false
    }
  }

  watch(taskId, () => void start(), { immediate: true })
  if (getCurrentScope()) onScopeDispose(stop)

  return {
    task,
    deltaText,
    reasoningText,
    loading,
    connected,
    error,
    lastSeq,
    restart: start,
    stop,
  }
}