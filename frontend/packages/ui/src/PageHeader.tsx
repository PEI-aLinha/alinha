import type { ReactNode } from 'react'

interface PageHeaderProps {
  title: string
  /**
   * @deprecated Not rendered. A kicker above the title repeats what the sidebar already says; put context in
   * `subtitle` instead. Kept so plugins can drop it in their own PRs; remove once none passes it.
   */
  eyebrow?: ReactNode
  subtitle?: ReactNode
  actions?: ReactNode
  /** Tertiary help (e.g. a guided tour), on the title row, before the actions. */
  help?: ReactNode
}

/** The page's only h1, with nothing above it. Subtitle below, help and actions on the right. */
export function PageHeader({ title, subtitle, actions, help }: PageHeaderProps) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-x-6 gap-y-4">
      <div className="flex min-w-0 flex-col gap-2">
        <h1 className="text-[1.875rem] font-extrabold title-tight max-md:text-[1.625rem]">{title}</h1>
        {subtitle && <p className="text-[1rem] text-ink-soft max-md:text-[0.9375rem]">{subtitle}</p>}
      </div>
      {(help || actions) && (
        <div className="flex flex-wrap items-center gap-3 max-md:w-full max-md:flex-col max-md:items-stretch">
          {/* On mobile the actions stack at full width, main action reachable first; help goes under them at its own size. */}
          {help && <div className="max-md:order-last max-md:self-start">{help}</div>}
          {actions && <div className="flex flex-wrap items-center gap-3 max-md:flex-col max-md:items-stretch">{actions}</div>}
        </div>
      )}
    </div>
  )
}
