import type { ComponentType } from 'react'
import { matchPath } from 'react-router'

export interface FrameLink {
  to: string
  label: string
  /** Shorter text for the mobile tab bar. */
  shortLabel?: string
  icon?: ComponentType<{ className?: string; strokeWidth?: number }>
  /** Active only on this exact path (e.g. the portfolio, which is the parent of every client page). */
  end?: boolean
}

export interface FrameSection {
  heading?: string
  links: FrameLink[]
}

/** Whether a menu entry is the current page: its path, or any page under it unless `end`. */
export function isLinkActive(link: FrameLink, pathname: string): boolean {
  return !!matchPath({ path: link.to, end: !!link.end }, pathname)
}
