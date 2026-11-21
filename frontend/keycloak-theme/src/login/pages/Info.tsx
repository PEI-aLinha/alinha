import { buttonClass } from '@alinha/ui'
import { kcSanitize } from 'keycloakify/lib/kcSanitize'
import type { PageProps } from 'keycloakify/login/pages/PageProps'
import type { I18n } from '../i18n'
import type { KcContext } from '../KcContext'

/** info.ftl: Keycloak's notices (email verified, password changed, an action to confirm). */
export default function Info({ kcContext, i18n, doUseDefaultCss, Template, classes }: PageProps<Extract<KcContext, { pageId: 'info.ftl' }>, I18n>) {
  const { messageHeader, message, requiredActions, skipLink, pageRedirectUri, actionUri, client } = kcContext
  const { msgStr, advancedMsgStr } = i18n

  let summary = message.summary
  if (requiredActions) summary += `<b>${requiredActions.map((a) => advancedMsgStr(`requiredAction.${a}`)).join(', ')}</b>`
  const next = skipLink ? undefined : (pageRedirectUri ?? actionUri ?? client.baseUrl)
  const nextLabel = pageRedirectUri || !actionUri ? msgStr('alinhaBackToApp') : msgStr('alinhaContinue')

  return (
    <Template
      kcContext={kcContext}
      i18n={i18n}
      doUseDefaultCss={doUseDefaultCss}
      classes={classes}
      displayMessage={false}
      headerNode={<span dangerouslySetInnerHTML={{ __html: kcSanitize(messageHeader ? advancedMsgStr(messageHeader) : message.summary) }} />}
    >
      {(messageHeader || requiredActions) && <p className="text-[0.9375rem] text-ink" dangerouslySetInnerHTML={{ __html: kcSanitize(summary) }} />}
      {next && (
        <a href={next} className={buttonClass({ className: 'w-fit max-sm:w-full' })}>
          {nextLabel}
        </a>
      )}
    </Template>
  )
}
