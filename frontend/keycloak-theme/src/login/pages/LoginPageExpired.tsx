import { buttonClass } from '@alinha/ui'
import type { PageProps } from 'keycloakify/login/pages/PageProps'
import type { I18n } from '../i18n'
import type { KcContext } from '../KcContext'

/** login-page-expired.ftl: the login form was left open too long. */
export default function LoginPageExpired({ kcContext, i18n, doUseDefaultCss, Template, classes }: PageProps<Extract<KcContext, { pageId: 'login-page-expired.ftl' }>, I18n>) {
  const { url } = kcContext
  const { msg } = i18n

  return (
    <Template kcContext={kcContext} i18n={i18n} doUseDefaultCss={doUseDefaultCss} classes={classes} displayMessage={false} headerNode={msg('alinhaExpiredTitle')}>
      <p className="text-[0.9375rem] text-ink-soft">{msg('alinhaExpiredLead')}</p>
      <div className="flex flex-wrap gap-3">
        <a href={url.loginAction} className={buttonClass({ className: 'max-sm:w-full' })}>
          {msg('alinhaExpiredContinue')}
        </a>
        <a href={url.loginRestartFlowUrl} className={buttonClass({ variant: 'secondary', className: 'max-sm:w-full' })}>
          {msg('alinhaExpiredRestart')}
        </a>
      </div>
    </Template>
  )
}
