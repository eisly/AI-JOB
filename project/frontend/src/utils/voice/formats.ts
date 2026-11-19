export interface VoiceCandidate {
  mimeType: string
  bitsPerSecond: number
}

const CANDIDATES: VoiceCandidate[] = [
  { mimeType: 'audio/webm;codecs=opus', bitsPerSecond: 32000 },
  { mimeType: 'audio/webm', bitsPerSecond: 32000 },
  { mimeType: 'audio/ogg;codecs=opus', bitsPerSecond: 32000 },
  { mimeType: 'audio/mp4', bitsPerSecond: 32000 },
]

export function candidates(): VoiceCandidate[] {
  return [...CANDIDATES]
}

export function pickMimeType(): VoiceCandidate | null {
  if (typeof MediaRecorder === 'undefined') return null
  return CANDIDATES.find((item) => MediaRecorder.isTypeSupported(item.mimeType)) || null
}

export function baseMime(mimeType: string | undefined | null) {
  return (mimeType || '').split(';')[0].trim().toLowerCase()
}

export function fileExtension(mimeType: string) {
  const base = baseMime(mimeType)
  if (base === 'audio/ogg') return 'ogg'
  if (base === 'audio/mp4') return 'm4a'
  return 'webm'
}

/** 上传前轻量魔数预检：服务端仍会复核，前端只用于尽早提示。 */
export async function looksLikeAudio(blob: Blob) {
  const head = new Uint8Array(await blob.slice(0, 12).arrayBuffer())
  if (head.length >= 4 && head[0] === 0x1a && head[1] === 0x45 && head[2] === 0xdf && head[3] === 0xa3) return true
  if (head.length >= 4 && head[0] === 0x4f && head[1] === 0x67 && head[2] === 0x67 && head[3] === 0x53) return true
  return head.length >= 8 && head[4] === 0x66 && head[5] === 0x74 && head[6] === 0x79 && head[7] === 0x70
}