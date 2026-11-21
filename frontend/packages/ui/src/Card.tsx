import { useId, type HTMLAttributes, type ReactNode } from 'react'
import { Info } from 'lucide-react'
import { cn } from './cn'

export function Card({ className, soft, ...rest }: HTMLAttributes<HTMLDivElement> & { soft?: boolean }) {
  return <div className={cn('rounded-[14px] border border-line-soft px-5 py-[18px]', soft ? 'bg-paper-soft' : 'bg-paper', className)} {...rest} />
}

export function Eyebrow({ children, className, as: Tag = 'p', id }: { children: ReactNode; className?: string; as?: 'p' | 'span' | 'h2' | 'h3'; id?: string }) {
  return <Tag id={id} className={cn('eyebrow text-xs text-muted', className)}>{children}</Tag>
}

interface StatCardProps {
  label: string
  value: ReactNode
  caption?: ReactNode
  children?: ReactNode
  onClick?: () => void
  accent?: ReactNode
  className?: string
  /** Heading level for the label, when the card sits under a section heading. */
  labelAs?: 'p' | 'h3'
}

/**
 * Summary card: eyebrow label, large value, short caption. With `onClick` the whole card is clickable through one
 * button stretched over it (like DataTable rows), so the label keeps its heading level and no block content ends up
 * inside the button. The button is named by the label and the value; links or buttons in `children` stay usable.
 */
export function StatCard({ label, value, caption, children, onClick, accent, className, labelAs = 'p' }: StatCardProps) {
  const id = useId()
  const cls = cn('relative flex-1 min-w-0 rounded-[14px] border border-line-soft bg-paper px-5 py-[18px] text-left', className)
  return (
    <div className={cn(cls, onClick && 'transition-[border-color,box-shadow] hover:border-line hover:shadow-[0_2px_10px_rgb(var(--shadow-rgb)/0.06)]')}>
      <div className="flex items-start justify-between gap-2">
        <Eyebrow as={labelAs} id={`${id}-label`}>{label}</Eyebrow>
        {accent}
      </div>
      <p id={`${id}-value`} className="mt-1.5 text-lg font-extrabold leading-tight">{value}</p>
      {caption && <p className="mt-1.5 text-sm text-ink-soft">{caption}</p>}
      {onClick && (
        <button
          type="button"
          onClick={onClick}
          aria-labelledby={`${id}-label ${id}-value`}
          className="absolute inset-0 rounded-[14px]"
        />
      )}
      {children && <div className="relative z-10 mt-3">{children}</div>}
    </div>
  )
}

/** Notice across the full width: signal-soft background, brand text. */
export function Note({ children, icon = true, className }: { children: ReactNode; icon?: boolean; className?: string }) {
  return (
    <div role="note" className={cn('flex items-start gap-2.5 rounded-[10px] bg-signal-soft px-3.5 py-2.5 text-sm font-semibold text-brand', className)}>
      {icon && <Info className="mt-[2px] size-4 shrink-0" aria-hidden />}
      <div className="min-w-0">{children}</div>
    </div>
  )
}
