import { useState } from 'react'
import { Button, Checkbox, Field, TextInput } from '@alinha/ui'
import type { PageProps } from 'keycloakify/login/pages/PageProps'
import { PasswordInput, plainText, TextLink } from '../components'
import type { I18n } from '../i18n'
import type { KcContext } from '../KcContext'

/** login.ftl: email + password. Passkeys and identity providers are not enabled in the realm, so they are not shown. */
export default function Login({ kcContext, i18n, doUseDefaultCss, Template, classes }: PageProps<Extract<KcContext, { pageId: 'login.ftl' }>, I18n>) {
  const { realm, url, usernameHidden, login, auth, registrationDisabled, messagesPerField } = kcContext
  const { msg, msgStr } = i18n
  const [submitting, setSubmitting] = useState(false)

  const credentialsError = messagesPerField.existsError('username', 'password') ? plainText(messagesPerField.getFirstError('username', 'password')) : undefined
  const usernameLabel = !realm.loginWithEmailAllowed ? msg('username') : realm.registrationEmailAsUsername ? msg('email') : msg('usernameOrEmail')

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      classes={classes}
      displayMessage={!credentialsError}
      headerNode={msg('loginAccountTitle')}
      displayInfo={realm.password && realm.registrationAllowed && !registrationDisabled}
      infoNode={
        <p>
          {msg('noAccount')} <TextLink href={url.registrationUrl}>{msg('doRegister')}</TextLink>
        </p>
      }
    >
      <p className="-mt-3 text-[0.9375rem] text-ink-soft">{msg('alinhaLoginLead')}</p>
      {realm.password && (
        <form id="kc-form-login" action={url.loginAction} method="post" onSubmit={() => setSubmitting(true)} className="flex flex-col gap-5">
          {!usernameHidden && (
            <Field label={usernameLabel} error={credentialsError}>
              {(p) => (
                <TextInput
                  id={p.id}
                  name="username"
                  aria-describedby={p.describedBy}
                  aria-required={p.required}
                  invalid={p.invalid}
                  defaultValue={login.username ?? ''}
                  type={realm.registrationEmailAsUsername ? 'email' : 'text'}
                  autoComplete="username"
                  autoFocus
                />
              )}
            </Field>
          )}
          <Field label={msg('password')} error={usernameHidden ? credentialsError : undefined}>
            {(p) => (
              <PasswordInput
                i18n={i18n}
                id={p.id}
                name="password"
                aria-describedby={p.describedBy}
                aria-required={p.required}
                invalid={p.invalid || !!credentialsError}
                autoComplete="current-password"
                autoFocus={usernameHidden}
              />
            )}
          </Field>

          {realm.rememberMe && !usernameHidden && (
            <Checkbox name="rememberMe" label={msg('rememberMe')} defaultChecked={!!login.rememberMe} />
          )}

          {realm.resetPasswordAllowed && (
            <TextLink href={url.loginResetCredentialsUrl} className="w-fit text-[0.875rem]">
              {msg('doForgotPassword')}
            </TextLink>
          )}

          <input type="hidden" name="credentialId" value={auth.selectedCredential ?? ''} />
          <Button type="submit" name="login" className="w-fit min-w-[160px] max-sm:w-full" loading={submitting}>
            {msgStr('doLogIn')}
          </Button>
        </form>
      )}
    </Template>
  )
}
