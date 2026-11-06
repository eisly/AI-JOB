import type { NavigationTarget } from '@/types/workbench'

export type HasRoute = (name: string) => boolean

export function resolveRoute(target: NavigationTarget | undefined, hasRoute: HasRoute): NavigationTarget | null {
  if (!target) {
    return null
  }
  if (hasRoute(target.name)) {
    return target
  }
  if (target.fallback) {
    return resolveRoute(target.fallback, hasRoute)
  }
  return null
}