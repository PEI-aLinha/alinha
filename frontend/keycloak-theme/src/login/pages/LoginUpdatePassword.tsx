import { useState } from 'react'
import { Button, Checkbox, Field } from '@alinha/ui'
import type { PageProps } from 'keycloakify/login/pages/PageProps'
import { PasswordInput, plainText } from '../components'
import type { I18n } from '../i18n'
import type { KcContext } from '../KcContext'

/** login-update-password.ftl: new password after a reset link, or when an admin asks for one. */
export default function LoginUpdatePassword({ kcContext, i18n, doUseDefaultCss, Template, classes }: PageProps<Extract<KcContext, { pageId: 'login-update-password.ftl' }>, I18n>) {
  const { url, messagesPerField, isAppInitiatedAction } = kcContext
  const { msg, msgStr } = i18n
  const [submitting, setSubmitting] = useState(false)
  const error = (field: string) => (messagesPerField.existsError(field) ? plainText(messagesPerField.get(field)) : undefined)

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      classes={classes}
      displayMessage={!messagesPerField.existsError('password', 'password-confirm')}
      headerNode={msg('alinhaUpdatePasswordTitle')}
    >
      <form id="kc-passwd-update-form" action={url.loginAction} method="post" onSubmit={() => setSubmitting(true)} className="flex flex-col gap-5">
        <div className="grid items-start gap-5 md:grid-cols-2">
          <Field label={msg('alinhaPassword')} error={error('password')}>
            {(p) => (
              <PasswordInput i18n={i18n} id={p.id} name="password-new" aria-describedby={p.describedBy} aria-required={p.required} invalid={p.invalid} autoComplete="new-password" autoFocus />
            )}
          </Field>
          <Field label={msg('alinhaPasswordConfirm')} error={error('password-confirm')}>
            {(p) => (
              <PasswordInput i18n={i18n} id={p.id} name="password-confirm" aria-describedby={p.describedBy} aria-required={p.required} invalid={p.invalid} autoComplete="new-password" />
            )}
          </Field>
        </div>
        <Checkbox name="logout-sessions" value="on" defaultChecked label={msg('alinhaLogoutOtherSessions')} />
        <div className="flex flex-wrap gap-3">
          <Button type="submit" className="w-fit max-sm:w-full" loading={submitting}>
            {msgStr('alinhaUpdatePasswordSubmit')}
          </Button>
          {isAppInitiatedAction && (
            <Button type="submit" variant="secondary" name="cancel-aia" value="true" className="w-fit max-sm:w-full">
              {msgStr('doCancel')}
            </Button>
          )}
        </div>
      </form>
    </Template>
  )
}
