import { buttonClass } from '@alinha/ui'
import type { PageProps } from 'keycloakify/login/pages/PageProps'
import { Alert } from '../components'
import type { I18n } from '../i18n'
import type { KcContext } from '../KcContext'

/** error.ftl: something Keycloak cannot recover from (expired link, unknown client, ...). */
export default function ErrorPage({ kcContext, i18n, doUseDefaultCss, Template, classes }: PageProps<Extract<KcContext, { pageId: 'error.ftl' }>, I18n>) {
  const { message, client, skipLink } = kcContext
  const { msg } = i18n

  return (
    <Template kcContext={kcContext} i18n={i18n} doUseDefaultCss={doUseDefaultCss} classes={classes} displayMessage={false} headerNode={msg('alinhaErrorTitle')}>
      <Alert type="error" summary={message.summary} />
      {!skipLink && client?.baseUrl && (
        <a href={client.baseUrl} className={buttonClass({ variant: 'secondary', className: 'w-fit max-sm:w-full' })}>
          {msg('alinhaBackToApp')}
        </a>
      )}
    </Template>
  )
}
