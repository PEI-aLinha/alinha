import { forwardRef, useState, type InputHTMLAttributes, type ReactNode } from 'react'
import { Check, CircleAlert, CircleCheck, Eye, EyeOff, Info, TriangleAlert } from 'lucide-react'
import { cn, TextInput } from '@alinha/ui'
import { kcSanitize } from 'keycloakify/lib/kcSanitize'
import type { I18n } from './i18n'
import type { PasswordRule } from './passwordRules'

export { plainText } from './text'

type MessageType = 'success' | 'warning' | 'error' | 'info'

const ALERT_STYLE: Record<MessageType, { cls: string; Icon: typeof Info }> = {
  error: { cls: 'bg-risk-high text-risk-high-ink', Icon: CircleAlert },
  warning: { cls: 'bg-risk-medium text-risk-medium-ink', Icon: TriangleAlert },
  success: { cls: 'bg-signal-soft text-brand', Icon: CircleCheck },
  info: { cls: 'bg-signal-soft text-brand', Icon: Info },
}

/**
 * Message from Keycloak (`kcContext.message`). The summary may hold HTML from the server's message bundle, so it is
 * sanitised first. Errors are alerts; the rest are polite status messages. Never colour alone: each type has an icon.
 */
export function Alert({ type, summary }: { type: MessageType; summary: string }) {
  const { cls, Icon } = ALERT_STYLE[type]
  return (
    <div role={type === 'error' ? 'alert' : 'status'} className={cn('flex items-start gap-2.5 rounded-[10px] px-3.5 py-2.5 text-[0.875rem] font-semibold', cls)}>
      <Icon className="mt-[2px] size-4 shrink-0" aria-hidden />
      <span className="min-w-0" dangerouslySetInnerHTML={{ __html: kcSanitize(summary) }} />
    </div>
  )
}

type PasswordInputProps = InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean; i18n: I18n }

/** Password field with a show/hide button (the prototype's PasswordInput). The button says what it will do. */
export const PasswordInput = forwardRef<HTMLInputElement, PasswordInputProps>(function PasswordInput({ i18n, className, ...rest }, ref) {
  const [visible, setVisible] = useState(false)
  const label = i18n.msgStr(visible ? 'alinhaHidePassword' : 'alinhaShowPassword')
  return (
    <div className="relative">
      <TextInput ref={ref} type={visible ? 'text' : 'password'} className={cn('pr-11', className)} {...rest} />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        aria-label={label}
        aria-controls={rest.id}
        aria-pressed={visible}
        className="absolute inset-y-0 right-0 grid w-11 place-items-center rounded-r-[10px] text-ink-soft hover:text-ink"
      >
        {visible ? <EyeOff className="size-[18px]" aria-hidden /> : <Eye className="size-[18px]" aria-hidden />}
      </button>
    </div>
  )
})

/** Live checklist under the password (prototype: PasswordChecklist). Each rule says in words whether it is met. */
export function PasswordChecklist({ id, rules, password, i18n }: { id: string; rules: PasswordRule[]; password: string; i18n: I18n }) {
  return (
    <ul id={id} aria-label={i18n.msgStr('alinhaPasswordRulesLabel')} className="flex flex-col gap-1">
      {rules.map((rule) => {
        const met = rule.test(password)
        return (
          <li key={rule.label} className={cn('flex items-center gap-2 text-[0.8125rem]', met ? 'font-semibold text-brand' : 'text-ink-soft')}>
            <span aria-hidden className={cn('grid size-4 place-items-center rounded-full', met ? 'bg-brand text-white' : 'border border-line')}>
              {met && <Check className="size-3" strokeWidth={3} />}
            </span>
            {rule.label}
            <span className="sr-only">{` (${i18n.msgStr(met ? 'alinhaRuleMet' : 'alinhaRuleNotMet')})`}</span>
          </li>
        )
      })}
    </ul>
  )
}

/** "Passo 1 de 4" pill above a title in the registration flow. */
export function StepBadge({ children }: { children: ReactNode }) {
  return (
    <span className="inline-flex w-fit items-center rounded-full bg-signal-soft px-2.5 py-1 text-[0.75rem] font-bold leading-[1.2] text-brand">
      {children}
    </span>
  )
}

/** Primary link that sits under a form ("Já tem conta? Entrar"). */
export function TextLink({ href, children, className }: { href: string; children: ReactNode; className?: string }) {
  return (
    <a href={href} className={cn('font-semibold text-brand underline-offset-2 hover:underline', className)}>
      {children}
    </a>
  )
}
