import type { Area } from '@alinha/plugin-sdk'

/** Where each area is mounted. A `Record`, so TypeScript flags every place a new area is missed. */
export const AREA_BASE: Record<Area, string> = {
  client: '/org',
  consultant: '/portfolio',
  onboarding: '/onboarding',
}
