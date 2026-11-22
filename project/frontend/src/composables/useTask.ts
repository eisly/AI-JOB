import { computed, getCurrentScope, onScopeDispose, ref, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { taskApi } from '@/api/tasks'
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
  const refreshing = ref(false)
  const connected = ref(false)
  const error = ref<unknown>(null)
  const lastSeq = ref(0)
  let controller: AbortController | null = null
  let pollTimer: number | undefined
  let reconnectTimer: number | undefined
  let generation = 0
  let reconnects = 0
  let authorityRequest: { run: number; promise: Promise<TaskVO> } | undefined
  let streamedStage: { stage: string; seq: number } | undefined
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
    if (run !== generation || value.id !== taskId.value) return
    const previous = task.value
    if (isTerminalTask(previous?.status) && !isTerminalTask(value.status)) return
    if (previous?.status === 'CANCEL_REQUESTED' && ['QUEUED', 'RUNNING'].includes(value.status)) return
    if (previous?.status === 'RUNNING' && value.status === 'QUEUED') return
    if (value.status === 'RUNNING' && streamedStage) {
      value = { ...value, stage: streamedStage.stage, lastEventId: Math.max(value.lastEventId, streamedStage.seq) }
    }
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

  function refreshAuthority(run: number) {
    if (authorityRequest?.run === run) return authorityRequest.promise
    const promise = taskApi.get(taskId.value).then(async value => {
      if (run !== generation) return value
      error.value = null
      await finish(run, value)
      return value
    }, reason => {
      if (run === generation) error.value = reason
      throw reason
    }).finally(() => {
      if (authorityRequest?.promise === promise) authorityRequest = undefined
    })
    authorityRequest = { run, promise }
    return promise
  }

  function scheduleReconnect(run: number) {
    if (run !== generation || isTerminalTask(task.value?.status)) return
    const maximum = options.maxReconnects ?? 3
    if (reconnects >= maximum) return
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
    if (eventName === 'stage' && payload.name) {
      streamedStage = { stage: payload.name, seq }
      if (task.value?.status === 'RUNNING') {
        applyTask({ ...task.value, stage: payload.name, lastEventId: Math.max(task.value.lastEventId, seq) })
      } else if (!isTerminalTask(task.value?.status)) {
        await poll(run)
      }
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
        if (run !== generation) return
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
        if (response.status === 429 || response.status >= 500) scheduleReconnect(run)
        return
      }
      if (!response.body) {
        scheduleReconnect(run)
        return
      }
      connected.value = true
      await consumeStream(run, response.body)
      if (run !== generation) return
      connected.value = false
      scheduleReconnect(run)
    } catch (reason) {
      if (run !== generation || (reason instanceof DOMException && reason.name === 'AbortError')) return
      connected.value = false
      scheduleReconnect(run)
    }
  }

  async function poll(run: number) {
    if (run !== generation || isTerminalTask(task.value?.status)) return
    try {
      await refreshAuthority(run)
    } catch {
      return
    }
  }

  async function refresh() {
    if (!taskId.value || refreshing.value) return
    const run = generation
    refreshing.value = true
    try {
      await refreshAuthority(run)
    } catch {
      // 保留最后一次任务状态，由调用方展示刷新入口。
    } finally {
      if (run === generation) refreshing.value = false
    }
  }

  async function acceptSnapshot(value: TaskVO) {
    if (value.id !== taskId.value) return
    error.value = null
    await finish(generation, value)
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
    streamedStage = undefined
    notifiedTerminal = ''
    seen.clear()
    refreshing.value = false
    loading.value = false
    if (!taskId.value) return
    loading.value = true
    pollTimer = window.setInterval(() => void poll(run), options.pollInterval ?? 4000)
    try {
      await poll(run)
    } finally {
      if (run === generation) {
        loading.value = false
        void openStream(run)
      }
    }
  }

  watch(taskId, () => void start(), { immediate: true })
  if (getCurrentScope()) onScopeDispose(stop)

  return {
    task,
    deltaText,
    reasoningText,
    loading,
    refreshing,
    connected,
    error,
    lastSeq,
    refresh,
    acceptSnapshot,
    restart: start,
    stop,
  }
}
