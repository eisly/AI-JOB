export type StemSegment = { type: 'text'; content: string } | { type: 'code'; content: string; language: string }

export function stemSegments(stem: string): StemSegment[] {
  const segments: StemSegment[] = []
  const fences = /```([^`\r\n]*)\r?\n([\s\S]*?)```/g
  let offset = 0
  for (const match of stem.matchAll(fences)) {
    if (match.index > offset) segments.push({ type: 'text', content: stem.slice(offset, match.index) })
    segments.push({ type: 'code', content: match[2]!.replace(/\r?\n$/, ''), language: match[1]!.trim() })
    offset = match.index + match[0].length
  }
  if (offset < stem.length) segments.push({ type: 'text', content: stem.slice(offset) })
  return segments
}
