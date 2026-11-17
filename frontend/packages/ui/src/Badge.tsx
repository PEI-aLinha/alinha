import type { ReactNode } from 'react'
import { Circle, OctagonAlert, TriangleAlert } from 'lucide-react'
import { cn } from './cn'

export type BadgeTone = 'signal' | 'neutral' | 'dark' | 'outline'

const TONE: Record<BadgeTone, string> = {
  signal: 'bg-signal-soft text-brand',
  neutral: 'bg-paper-deep text-ink-soft',
  dark: 'bg-brand-deep text-white',
  outline: 'border border-line-soft text-ink-soft bg-paper',
}

const PILL = 'inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2.5 py-1 text-[0.75rem] font-bold leading-[1.2]'

/** Short label or status (MS3 prototype "Chip"). The text carries the meaning, never the colour alone. */
export function Badge({ tone = 'signal', children, className, icon }: { tone?: BadgeTone; children: ReactNode; className?: string; icon?: ReactNode }) {
  return (
    <span className={cn(PILL, TONE[tone], className)}>
      {icon}
      {children}
    </span>
  )
}

export type Level = 'low' | 'medium' | 'high'

const LEVEL_CLASS: Record<Level, string> = {
  low: 'bg-risk-low text-risk-low-ink',
  medium: 'bg-risk-medium text-risk-medium-ink',
  high: 'bg-risk-high text-risk-high-ink',
}
const RISK_LABEL: Record<Level, string> = { low: 'Baixo', medium: 'Médio', high: 'Alto' }
const CRITICALITY_LABEL: Record<Level, string> = { low: 'Baixa', medium: 'Média', high: 'Alta' }
const RISK_ICON: Record<Level, typeof Circle> = { low: Circle, medium: TriangleAlert, high: OctagonAlert }

/** Background and text classes of a risk level, for cells that are not badges (e.g. a risk matrix). */
export const levelClass = (level: Level): string => LEVEL_CLASS[level]

/** Risk level: colour + icon + text (never colour alone, WCAG 1.4.1). */
export function RiskBadge({ level, prefix, size = 'md' }: { level: Level; prefix?: string; size?: 'md' | 'lg' }) {
  const Icon = RISK_ICON[level]
  return (
    <span className={cn('inline-flex items-center gap-1.5 whitespace-nowrap rounded-full font-bold leading-[1.2]', LEVEL_CLASS[level], size === 'lg' ? 'px-3 py-1.5 text-[0.875rem]' : 'px-2.5 py-1 text-[0.75rem]')}>
      <Icon className={size === 'lg' ? 'size-4' : 'size-3.5'} strokeWidth={2.25} aria-hidden />
      {prefix}
      {RISK_LABEL[level]}
    </span>
  )
}

/** Criticality of an asset: high = dark, medium = signal, low = neutral. */
export function CriticalityBadge({ level }: { level: Level }) {
  const tone: BadgeTone = level === 'high' ? 'dark' : level === 'medium' ? 'signal' : 'neutral'
  return <Badge tone={tone}>{CRITICALITY_LABEL[level]}</Badge>
}

/** Mandatory label on AI-generated content (AI Act). */
export function AIBadge({ kind = 'proposal', onDark }: { kind?: 'proposal' | 'info'; onDark?: boolean }) {
  return (
    <span className={cn(PILL, onDark ? 'bg-brand text-signal-soft' : 'border border-brand-soft bg-paper text-brand')}>
      <span aria-hidden>✦</span>
      {kind === 'proposal' ? 'Proposta pela IA · por validar' : 'Gerado por IA · apenas informativo'}
    </span>
  )
}
