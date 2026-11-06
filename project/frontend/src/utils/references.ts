const prefix = 'ai-job-resource-refs-v1'

type ReferenceKind = 'resume-versions' | 'personal-jobs'

function read(kind: ReferenceKind) {
  try {
    return JSON.parse(localStorage.getItem(`${prefix}:${kind}`) || '{}') as Record<string, string[]>
  } catch {
    return {} as Record<string, string[]>
  }
}

export function getReferences(kind: ReferenceKind, owner = 'default') {
  return read(kind)[owner] || []
}

export function addReference(kind: ReferenceKind, id: string, owner = 'default') {
  const data = read(kind)
  data[owner] = [id, ...(data[owner] || []).filter(item => item !== id)].slice(0, 100)
  try {
    localStorage.setItem(`${prefix}:${kind}`, JSON.stringify(data))
  } catch {
    return
  }
}

export function removeReference(kind: ReferenceKind, id: string, owner = 'default') {
  const data = read(kind)
  data[owner] = (data[owner] || []).filter(item => item !== id)
  try {
    localStorage.setItem(`${prefix}:${kind}`, JSON.stringify(data))
  } catch {
    return
  }
}