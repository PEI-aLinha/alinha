import { useId, useState } from 'react'
import { Copy } from 'lucide-react'
import { Button, Card, Checkbox, Field, Note, TextInput } from '@alinha/ui'
import type { PageProps } from 'keycloakify/login/pages/PageProps'
import { plainText } from '../components'
import type { I18n } from '../i18n'
import type { KcContext } from '../KcContext'

type TotpContext = Extract<KcContext, { pageId: 'login-config-totp.ftl' }>

/** login-config-totp.ftl: the prototype's MfaSetup card (app → QR or key → code), on Keycloak's real secret. */
export default function LoginConfigTotp({ kcContext, i18n, doUseDefaultCss, Template, classes }: PageProps<TotpContext, I18n>) {
  const { url, isAppInitiatedAction, totp, mode, messagesPerField } = kcContext
  const { msg, msgStr, advancedMsgStr } = i18n
  const ids = useId()
  const [code, setCode] = useState('')
  const [copied, setCopied] = useState<'ok' | 'failed'>()
  const manual = mode === 'manual'
  const digits = totp.policy.digits

  const copyKey = async () => {
    try {
      await navigator.clipboard.writeText(totp.totpSecretEncoded.replaceAll(' ', ''))
      setCopied('ok')
    } catch {
      setCopied('failed')
    }
  }

  const codeError = messagesPerField.existsError('totp') ? plainText(messagesPerField.get('totp')) : undefined
  const labelError = messagesPerField.existsError('userLabel') ? plainText(messagesPerField.get('userLabel')) : undefined
  const apps = totp.supportedApplications.map((app) => advancedMsgStr(app)).join(', ')
  const period = totp.policy.type === 'totp' ? totp.policy.period : undefined

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      classes={classes}
      headerNode={msg('alinhaTotpTitle')}
      // Keycloak adds a warning ("configure the authenticator to activate the account") that the lead already says.
      displayMessage={kcContext.message?.type !== 'warning' && !messagesPerField.existsError('totp', 'userLabel')}
    >
      <p className="-mt-3 text-[0.9375rem] text-ink-soft">{msg('alinhaTotpLead')}</p>
      <Card className="px-7 py-7 max-md:px-5">
        <ol aria-label={msgStr('alinhaTotpStepsLabel')} className="flex flex-col gap-6">
          <li>
            <section aria-labelledby={`${ids}-app`} className="flex flex-col gap-1.5">
              <h2 id={`${ids}-app`} className="text-[1rem] font-bold">
                <span aria-hidden>1. </span>
                {msg('alinhaTotpStep1')}
              </h2>
              <p className="text-[0.875rem] text-ink-soft">{msg('alinhaTotpStep1Body', apps)}</p>
            </section>
          </li>

          <li>
            <section aria-labelledby={`${ids}-qr`} className="flex flex-col gap-3">
              <h2 id={`${ids}-qr`} className="text-[1rem] font-bold">
                <span aria-hidden>2. </span>
                {msg(manual ? 'alinhaTotpStep2Manual' : 'alinhaTotpStep2')}
              </h2>
              <div className="flex items-center gap-6 max-sm:flex-col max-sm:items-start">
                {!manual && (
                  // The QR code is a black-on-white PNG: keep it on white in dark mode too, or phones cannot read it.
                  <img
                    src={`data:image/png;base64, ${totp.totpSecretQrCode}`}
                    alt={msgStr('alinhaTotpQrAlt')}
                    width={160}
                    height={160}
                    className="size-40 shrink-0 rounded-[10px] border border-line-soft bg-[#ffffff] p-1.5"
                  />
                )}
                <div className="flex min-w-0 flex-col gap-2">
                  {!manual && <p className="text-[0.875rem] text-ink-soft">{msg('alinhaTotpCantScan')}</p>}
                  <code className="w-fit break-all rounded-[10px] bg-paper-soft px-3 py-2 font-mono text-[0.9375rem] tracking-[0.08em] text-ink">
                    {totp.totpSecretEncoded}
                  </code>
                  <div className="flex flex-wrap items-center gap-3">
                    <Button variant="secondary" size="sm" className="w-fit" icon={<Copy className="size-4" aria-hidden />} onClick={copyKey}>
                      {msgStr('alinhaTotpCopyKey')}
                    </Button>
                    <a href={manual ? totp.qrUrl : totp.manualUrl} className="text-[0.8125rem] font-semibold text-brand hover:underline">
                      {msg(manual ? 'alinhaTotpShowQr' : 'alinhaTotpShowKey')}
                    </a>
                  </div>
                  <p role="status" className="text-[0.8125rem] font-semibold text-brand empty:hidden">
                    {copied === 'ok' ? msgStr('alinhaTotpCopied') : copied === 'failed' ? msgStr('alinhaTotpCopyFailed') : ''}
                  </p>
                  {manual && period !== undefined && (
                    <p className="text-[0.8125rem] text-muted">
                      {msg('alinhaTotpManualSettings', 'TOTP', String(digits), String(period), totp.policy.getAlgorithmKey())}
                    </p>
                  )}
                </div>
              </div>
            </section>
          </li>

          <li>
            <form id="kc-totp-settings-form" action={url.loginAction} method="post" aria-labelledby={`${ids}-code`} className="flex flex-col gap-4">
              <h2 id={`${ids}-code`} className="text-[1rem] font-bold">
                <span aria-hidden>3. </span>
                {msg('alinhaTotpStep3')}
              </h2>
              <Field label={msg('alinhaTotpCode')} error={codeError}>
                {(p) => (
                  <TextInput
                    id={p.id}
                    name="totp"
                    aria-describedby={p.describedBy}
                    aria-required={p.required}
                    invalid={p.invalid}
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    maxLength={digits}
                    placeholder={'0'.repeat(digits)}
                    value={code}
                    onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, digits))}
                    className="max-w-[220px] font-mono text-[1.25rem] tracking-[0.4em]"
                  />
                )}
              </Field>
              <Field label={msg('alinhaTotpDeviceName')} hint={msg('alinhaTotpDeviceNameHint')} error={labelError} optional={totp.otpCredentials.length === 0}>
                {(p) => (
                  <TextInput id={p.id} name="userLabel" aria-describedby={p.describedBy} aria-required={p.required} invalid={p.invalid} autoComplete="off" className="max-w-[360px]" />
                )}
              </Field>
              <input type="hidden" name="totpSecret" value={totp.totpSecret} />
              {mode && <input type="hidden" name="mode" value={mode} />}
              <Checkbox name="logout-sessions" value="on" label={msg('alinhaLogoutOtherSessions')} />
              <div className="flex flex-wrap gap-3">
                <Button type="submit" id="saveTOTPBtn" className="w-fit max-sm:w-full">
                  {msgStr('alinhaTotpSubmit')}
                </Button>
                {isAppInitiatedAction && (
                  <Button type="submit" variant="secondary" name="cancel-aia" value="true" className="w-fit max-sm:w-full">
                    {msgStr('doCancel')}
                  </Button>
                )}
              </div>
            </form>
          </li>
        </ol>
      </Card>
      <Note>{msg('alinhaTotpNote')}</Note>
    </Template>
  )
}
