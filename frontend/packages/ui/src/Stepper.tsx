import { Check } from 'lucide-react'
import { cn } from './cn'

export interface Step {
  label: string
}

interface StepperProps {
  steps: Step[]
  /** Index of the current step (0-based). Earlier steps are done, later ones are still to do. */
  current: number
  /** Names the list for screen readers, e.g. "Passos da avaliação". */
  label: string
  /** With it, done steps are buttons that go back to that step. */
  onStepClick?: (index: number) => void
  className?: string
}

/**
 * Progress through a multi-step flow (e.g. onboarding, an assessment). An ordered list: the current step has
 * aria-current="step", and each step says in words whether it is done, so colour is never the only cue.
 */
export function Stepper({ steps, current, label, onStepClick, className }: StepperProps) {
  return (
    <nav aria-label={label} className={className}>
      <ol className="flex flex-wrap items-center gap-x-2 gap-y-3">
        {steps.map((step, i) => {
          const state = i < current ? 'done' : i === current ? 'current' : 'todo'
          const status = state === 'done' ? 'concluído' : state === 'current' ? 'passo atual' : 'por fazer'
          const body = (
            <>
              <span
                aria-hidden
                className={cn(
                  'grid size-7 shrink-0 place-items-center rounded-full border-2 text-[0.8125rem] font-bold',
                  state === 'done' && 'border-brand bg-brand text-white',
                  state === 'current' && 'border-brand bg-paper text-brand',
                  state === 'todo' && 'border-line bg-paper text-muted',
                )}
              >
                {state === 'done' ? <Check className="size-4" strokeWidth={3} /> : i + 1}
              </span>
              <span className={cn('text-[0.875rem]', state === 'current' ? 'font-bold text-ink' : 'text-ink-soft')}>
                {step.label}
                <span className="sr-only">{` (${status})`}</span>
              </span>
            </>
          )
          return (
            <li key={i} aria-current={state === 'current' ? 'step' : undefined} className="flex items-center gap-2">
              {state === 'done' && onStepClick ? (
                <button type="button" onClick={() => onStepClick(i)} className="flex items-center gap-2 rounded-full pr-1 hover:underline">
                  {body}
                </button>
              ) : (
                <span className="flex items-center gap-2">{body}</span>
              )}
              {i < steps.length - 1 && <span aria-hidden className={cn('h-0.5 w-6 rounded-full', i < current ? 'bg-brand' : 'bg-line-soft')} />}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
