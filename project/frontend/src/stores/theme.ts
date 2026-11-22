import { readonly, ref } from 'vue'

const storageKey = 'ai-job-theme'
const systemTheme = window.matchMedia('(prefers-color-scheme: dark)')
const dark = ref(false)

function readPreference() {
  try {
    const stored = localStorage.getItem(storageKey)
    return stored === 'dark' || stored === 'light' ? stored : null
  } catch {
    return null
  }
}

let preference = readPreference()

function applyTheme() {
  dark.value = preference === null ? systemTheme.matches : preference === 'dark'
  const classes = document.documentElement.classList
  if (classes.contains('dark') === dark.value) return
  classes.add('theme-switching')
  classes.toggle('dark', dark.value)
  requestAnimationFrame(() => requestAnimationFrame(() => classes.remove('theme-switching')))
}

export function initializeTheme() {
  applyTheme()
  systemTheme.addEventListener('change', applyTheme)
  window.addEventListener('storage', (event) => {
    if (event.key !== storageKey && event.key !== null) return
    preference = readPreference()
    applyTheme()
  })
}

export const isDarkTheme = readonly(dark)

export function toggleTheme() {
  preference = dark.value ? 'light' : 'dark'
  applyTheme()
  try {
    localStorage.setItem(storageKey, preference)
  } catch {
    // Keep the current theme usable when storage is blocked.
  }
}
