import { useState } from 'react'
import { Button, Checkbox, Field, TextInput } from '@alinha/ui'
import type { PageProps } from 'keycloakify/login/pages/PageProps'
import { PasswordChecklist, PasswordInput, plainText, TextLink } from '../components'
import { passwordRules } from '../passwordRules'
import type { I18n } from '../i18n'
import type { KcContext } from '../KcContext'

type RegisterContext = Extract<KcContext, { pageId: 'register.ftl' }>

/** Fields with their own error under them: when one has an error, the page-level message would repeat it. */
const FIELDS = ['firstName', 'lastName', 'email', 'username', 'password', 'password-confirm', 'termsAccepted'] as const

/**
 * register.ftl, step 1 of the prototype ("Crie a sua conta"). The realm uses the email as the username and its user
 * profile has exactly first name, last name and email for users to fill in (`tenant_id`/`tenants` are admin-only), so
 * the fields are written out here instead of generated from the profile. Adding a user-editable attribute to the
 * realm's user profile means adding its field here.
 */
export default function Register({ kcContext, i18n, doUseDefaultCss, Template, classes }: PageProps<RegisterContext, I18n>) {
  const { url, messagesPerField, profile, passwordPolicies, termsAcceptanceRequired } = kcContext
  const { msg, msgStr } = i18n
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const error = (field: string) => (messagesPerField.existsError(field) ? plainText(messagesPerField.get(field)) : undefined)
  const value = (field: string) => profile.attributesByName[field]?.value ?? ''
  const rules = passwordRules(passwordPolicies, msgStr)

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      classes={classes}
      headerNode={msg('alinhaRegisterTitle')}
      displayMessage={!messagesPerField.existsError(...FIELDS)}
      displayInfo
      infoNode={
        <p>
          {msg('alinhaHaveAccount')} <TextLink href={url.loginUrl}>{msg('alinhaDoLogIn')}</TextLink>
        </p>
      }
    >
      <p className="-mt-3 text-[0.875rem] text-ink-soft">{msg('alinhaRegisterLead')}</p>
      <form id="kc-register-form" action={url.registrationAction} method="post" onSubmit={() => setSubmitting(true)} className="flex flex-col gap-5">
        <div className="grid items-start gap-5 md:grid-cols-2">
          <Field label={msg('alinhaFirstName')} error={error('firstName')}>
            {(p) => (
              <TextInput id={p.id} name="firstName" aria-describedby={p.describedBy} aria-required={p.required} invalid={p.invalid} defaultValue={value('firstName')} autoComplete="given-name" autoFocus />
            )}
          </Field>
          <Field label={msg('alinhaLastName')} error={error('lastName')}>
            {(p) => (
              <TextInput id={p.id} name="lastName" aria-describedby={p.describedBy} aria-required={p.required} invalid={p.invalid} defaultValue={value('lastName')} autoComplete="family-name" />
            )}
          </Field>
          <Field label={msg('alinhaWorkEmail')} hint={msg('alinhaWorkEmailHint')} error={error('email') ?? error('username')} className="md:col-span-2">
            {(p) => (
              <TextInput id={p.id} name="email" type="email" aria-describedby={p.describedBy} aria-required={p.required} invalid={p.invalid} defaultValue={value('email')} autoComplete="email" placeholder="nome@empresa.pt" />
            )}
          </Field>
          <Field label={msg('alinhaPassword')} error={error('password')}>
            {(p) => (
              <div className="flex flex-col gap-2">
                <PasswordInput
                  i18n={i18n}
                  id={p.id}
                  name="password"
                  aria-describedby={[p.describedBy, rules.length ? 'password-rules' : undefined].filter(Boolean).join(' ') || undefined}
                  aria-required={p.required}
                  invalid={p.invalid}
                  autoComplete="new-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
                {rules.length > 0 && <PasswordChecklist id="password-rules" rules={rules} password={password} i18n={i18n} />}
              </div>
            )}
          </Field>
          <Field label={msg('alinhaPasswordConfirm')} error={error('password-confirm')}>
            {(p) => (
              <PasswordInput i18n={i18n} id={p.id} name="password-confirm" aria-describedby={p.describedBy} aria-required={p.required} invalid={p.invalid} autoComplete="new-password" />
            )}
          </Field>
        </div>

        {termsAcceptanceRequired && (
          <div className="flex flex-col gap-1.5">
            <Checkbox
              name="termsAccepted"
              label={msg('alinhaTerms')}
              aria-required="true"
              aria-invalid={messagesPerField.existsError('termsAccepted') || undefined}
              aria-describedby={messagesPerField.existsError('termsAccepted') ? 'terms-error' : undefined}
            />
            {messagesPerField.existsError('termsAccepted') && (
              <p id="terms-error" role="alert" className="text-[0.8125rem] font-semibold text-danger">
                {plainText(messagesPerField.get('termsAccepted'))}
              </p>
            )}
          </div>
        )}

        <Button type="submit" className="w-fit max-sm:w-full" loading={submitting}>
          {msgStr('alinhaRegisterSubmit')}
        </Button>
      </form>
    </Template>
  )
}
