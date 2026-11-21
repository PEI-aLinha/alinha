import { Button, Field, TextInput } from '@alinha/ui'
import type { PageProps } from 'keycloakify/login/pages/PageProps'
import { plainText, TextLink } from '../components'
import type { I18n } from '../i18n'
import type { KcContext } from '../KcContext'

/** login-reset-password.ftl: "Esqueceu-se da palavra-passe?" asks for the email and sends a link. */
export default function LoginResetPassword({ kcContext, i18n, doUseDefaultCss, Template, classes }: PageProps<Extract<KcContext, { pageId: 'login-reset-password.ftl' }>, I18n>) {
  const { url, realm, auth, messagesPerField } = kcContext
  const { msg, msgStr } = i18n
  const error = messagesPerField.existsError('username') ? plainText(messagesPerField.get('username')) : undefined
  const label = !realm.loginWithEmailAllowed ? msg('username') : msg('email')

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      classes={classes}
      displayMessage={!error}
      headerNode={msg('alinhaResetTitle')}
      displayInfo
      infoNode={<TextLink href={url.loginUrl}>{msg('backToLogin')}</TextLink>}
    >
      <p className="-mt-3 text-[0.9375rem] text-ink-soft">{msg('alinhaResetLead')}</p>
      <form id="kc-reset-password-form" action={url.loginAction} method="post" className="flex flex-col gap-5">
        <Field label={label} error={error}>
          {(p) => (
            <TextInput
              id={p.id}
              name="username"
              type={realm.loginWithEmailAllowed ? 'email' : 'text'}
              aria-describedby={p.describedBy}
              aria-required={p.required}
              invalid={p.invalid}
              defaultValue={auth?.attemptedUsername ?? ''}
              autoComplete="username"
              autoFocus
            />
          )}
        </Field>
        <Button type="submit" className="w-fit max-sm:w-full">
          {msgStr('alinhaResetSubmit')}
        </Button>
      </form>
    </Template>
  )
}
