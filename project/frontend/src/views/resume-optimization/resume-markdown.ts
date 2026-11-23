import MarkdownIt from 'markdown-it'

const markdown = new MarkdownIt({ html: false, breaks: true })
const resumeHeading = /^(?:[•●○■□▪▫◆◇�\p{Co}]\s*)?(教育背景|教育经历|实习经历|实习经验|工作经历|工作经验|项目经历|项目经验|校园经历|科研经历|专业技能|技术技能|技能特长|获奖经历|荣誉奖项|自我评价)[：:]?$/u

// PDF/OCR exports include page markers and may contain unformatted section headings.
markdown.block.ruler.before('paragraph', 'resume_section', (state, startLine, _endLine, silent) => {
  if (state.sCount[startLine] - state.blkIndent >= 4) return false
  const text = state.src.slice(state.bMarks[startLine] + state.tShift[startLine], state.eMarks[startLine]).trim()
  const pageMarker = /^\[page:\d+\]$/.test(text)
  const heading = resumeHeading.exec(text)?.[1]
  if (!pageMarker && !heading) return false
  if (silent) return true

  state.line = startLine + 1
  if (heading) {
    state.push('heading_open', 'h2', 1).map = [startLine, state.line]
    const inline = state.push('inline', '', 0)
    inline.content = heading
    inline.children = []
    state.push('heading_close', 'h2', -1)
  }
  return true
}, { alt: ['paragraph'] })

const highlightable = new Set(['paragraph_open', 'heading_open', 'list_item_open', 'table_open', 'fence', 'code_block'])

export function renderResumeMarkdown(blocks: { text: string; changed: boolean }[]) {
  const source = blocks.map(block => block.text).join('')
  const changes: { start: number; end: number }[] = []
  let offset = 0
  for (const block of blocks) {
    const end = offset + block.text.length
    if (block.changed && offset < end) changes.push({ start: offset, end })
    offset = end
  }
  if (!changes.length) return markdown.render(source)

  const changedLines = Array.from(source.matchAll(/[^\r\n]*(?:\r\n|\r|\n|$)/g), line =>
    changes.some(change => change.start < line.index + line[0].length && line.index < change.end),
  )
  const env = {}
  const tokens = markdown.parse(source, env)
  for (const token of tokens) {
    if (!token.map || token.hidden || !highlightable.has(token.type)) continue
    const [start, end] = token.map
    if (changedLines.slice(start, end).some(Boolean)) token.attrJoin('class', 'markdown-changed')
  }
  return markdown.renderer.render(tokens, markdown.options, env)
}
