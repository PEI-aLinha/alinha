import type { ReactNode } from 'react'
import { Eyebrow } from './Card'

interface PageHeaderProps {
  title: string
  eyebrow?: ReactNode
  subtitle?: ReactNode
  actions?: ReactNode
  /** Tertiary help (e.g. a guided tour), on the eyebrow line so it never competes with the actions. */
  help?: ReactNode
}

/** The page's only h1. Optional eyebrow (+ help) above, subtitle below, actions on the right. */
export function PageHeader({ eyebrow, title, subtitle, actions, help }: PageHeaderProps) {
  return (
    <div className="mb-6 flex flex-col gap-2">
      {(eyebrow || help) && (
        <div className="flex min-h-8 items-center justify-between gap-3">
          {eyebrow && <Eyebrow className="text-[0.75rem]">{eyebrow}</Eyebrow>}
          {help && <div className="-my-1 ml-auto shrink-0">{help}</div>}
        </div>
      )}
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-4">
        <div className="flex min-w-0 flex-col gap-2">
          <h1 className="text-[1.875rem] font-extrabold title-tight max-md:text-[1.625rem]">{title}</h1>
          {subtitle && <p className="text-[1rem] text-ink-soft max-md:text-[0.9375rem]">{subtitle}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-3 max-md:w-full max-md:[&>*]:flex-1">{actions}</div>}
      </div>
    </div>
  )
}
