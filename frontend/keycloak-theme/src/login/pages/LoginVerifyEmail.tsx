import { MailCheck } from 'lucide-react'
import type { PageProps } from 'keycloakify/login/pages/PageProps'
import { TextLink } from '../components'
import type { I18n } from '../i18n'
import type { KcContext } from '../KcContext'

/** login-verify-email.ftl: the account exists, the email link is on its way. */
export default function LoginVerifyEmail({ kcContext, i18n, doUseDefaultCss, Template, classes }: PageProps<Extract<KcContext, { pageId: 'login-verify-email.ftl' }>, I18n>) {
  const { url, user } = kcContext
  const { msg } = i18n

  return (
    <Template kcContext={kcContext} i18n={i18n} doUseDefaultCss={doUseDefaultCss} classes={classes} displayMessage={false} headerNode={msg('alinhaVerifyTitle')}>
      <div className="flex items-start gap-4">
        <span aria-hidden className="grid size-12 shrink-0 place-items-center rounded-full bg-signal-soft text-brand">
          <MailCheck className="size-6" />
        </span>
        <p className="text-[0.9375rem] text-ink">
          {user?.email ? msg('alinhaVerifyLead', user.email) : msg('alinhaVerifyLeadNoEmail')}
        </p>
      </div>
      <p className="text-[0.875rem] text-ink-soft">
        {msg('alinhaVerifyResend')} <TextLink href={url.loginAction}>{msg('alinhaVerifyResendLink')}</TextLink>.
      </p>
    </Template>
  )
}
