import { useState } from 'react'
import { Button, Field, RadioGroup, TextInput } from '@alinha/ui'
import type { PageProps } from 'keycloakify/login/pages/PageProps'
import { plainText } from '../components'
import type { I18n } from '../i18n'
import type { KcContext } from '../KcContext'

/** login-otp.ftl: the 6-digit code on every login. With several devices, the user picks one first. */
export default function LoginOtp({ kcContext, i18n, doUseDefaultCss, Template, classes }: PageProps<Extract<KcContext, { pageId: 'login-otp.ftl' }>, I18n>) {
  const { otpLogin, url, messagesPerField } = kcContext
  const { msg, msgStr } = i18n
  const [submitting, setSubmitting] = useState(false)
  const [code, setCode] = useState('')
  const [device, setDevice] = useState(otpLogin.selectedCredentialId ?? otpLogin.userOtpCredentials[0]?.id ?? '')
  const error = messagesPerField.existsError('totp') ? plainText(messagesPerField.get('totp')) : undefined

  return (
    <Template kcContext={kcContext} i18n={i18n} doUseDefaultCss={doUseDefaultCss} classes={classes} displayMessage={!error} headerNode={msg('alinhaOtpTitle')}>
      <p className="-mt-3 text-[0.9375rem] text-ink-soft">{msg('alinhaOtpLead')}</p>
      <form id="kc-otp-login-form" action={url.loginAction} method="post" onSubmit={() => setSubmitting(true)} className="flex flex-col gap-5">
        {otpLogin.userOtpCredentials.length > 1 && (
          <RadioGroup
            name="selectedCredentialId"
            legend={msg('alinhaOtpDevice')}
            value={device}
            onChange={setDevice}
            options={otpLogin.userOtpCredentials.map((c) => ({ value: c.id, label: c.userLabel }))}
          />
        )}
        <Field label={msg('alinhaTotpCode')} error={error}>
          {(p) => (
            <TextInput
              id={p.id}
              name="otp"
              aria-describedby={p.describedBy}
              aria-required={p.required}
              invalid={p.invalid}
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              placeholder="000000"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              autoFocus
              className="max-w-[220px] font-mono text-[1.25rem] tracking-[0.4em]"
            />
          )}
        </Field>
        <Button type="submit" name="login" className="w-fit min-w-[160px] max-sm:w-full" loading={submitting}>
          {msgStr('alinhaOtpSubmit')}
        </Button>
      </form>
    </Template>
  )
}
