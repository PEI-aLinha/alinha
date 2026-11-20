import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react'
import { cn } from './cn'

interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label: ReactNode
  hint?: ReactNode
}

/** Native checkbox with its label and an optional hint: keyboard (Space) and screen readers work as usual. */
export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox({ label, hint, className, id, ...rest }, ref) {
  const own = useId()
  const inputId = id ?? own
  const hintId = hint ? `${inputId}-hint` : undefined
  return (
    <div className={cn('flex items-start gap-2.5', className)}>
      <input
        ref={ref}
        id={inputId}
        type="checkbox"
        aria-describedby={hintId}
        className="mt-[3px] size-[18px] shrink-0 cursor-pointer rounded-[4px] accent-brand disabled:cursor-not-allowed"
        {...rest}
      />
      <span className="min-w-0">
        <label htmlFor={inputId} className="cursor-pointer text-[0.9375rem] text-ink">{label}</label>
        {hint && <span id={hintId} className="mt-0.5 block text-[0.8125rem] text-ink-soft">{hint}</span>}
      </span>
    </div>
  )
})

export interface RadioOption<T extends string> {
  value: T
  label: string
  hint?: string
}

interface RadioGroupProps<T extends string> {
  name: string
  value: T | null
  onChange: (value: T) => void
  options: RadioOption<T>[]
  legend: ReactNode
  columns?: 1 | 2 | 3
  error?: string
}

/**
 * One choice among a few, as option cards (MS3 prototype "OptionGroup"). Native radios inside a fieldset:
 * arrow keys move between options, the legend names the group. Selected: brand-soft + brand border + bold text.
 */
export function RadioGroup<T extends string>({ name, value, onChange, options, legend, columns = 1, error }: RadioGroupProps<T>) {
  const id = useId()
  const errorId = error ? `${id}-err` : undefined
  return (
    <fieldset className="flex min-w-0 flex-col gap-1.5" aria-describedby={errorId} aria-invalid={error ? true : undefined}>
      <legend className="mb-1.5 text-[0.8125rem] font-semibold text-ink-soft">{legend}</legend>
      <div className={cn('grid gap-2.5', columns === 2 && 'sm:grid-cols-2', columns === 3 && 'sm:grid-cols-3')}>
        {options.map((o) => {
          const selected = value === o.value
          return (
            <label
              key={o.value}
              className={cn(
                'flex cursor-pointer items-start gap-3 rounded-[12px] border px-4 py-3.5 transition-colors duration-150',
                'has-[:focus-visible]:outline has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-signal',
                selected ? 'border-brand bg-brand-soft' : 'border-line bg-paper hover:border-ink-soft',
              )}
            >
              <input type="radio" name={name} value={o.value} checked={selected} onChange={() => onChange(o.value)} className="sr-only" />
              <span aria-hidden className={cn('mt-[3px] grid size-[18px] shrink-0 place-items-center rounded-full border-2', selected ? 'border-brand' : 'border-line')}>
                {selected && <span className="size-2 rounded-full bg-brand forced-colors:bg-[CanvasText]" />}
              </span>
              <span className="min-w-0">
                <span className={cn('block text-[0.9375rem]', selected ? 'font-bold text-brand' : 'text-ink')}>{o.label}</span>
                {o.hint && <span className="mt-0.5 block text-[0.8125rem] text-ink-soft">{o.hint}</span>}
              </span>
            </label>
          )
        })}
      </div>
      {error && <p id={errorId} role="alert" className="text-[0.8125rem] font-semibold text-danger">{error}</p>}
    </fieldset>
  )
}
