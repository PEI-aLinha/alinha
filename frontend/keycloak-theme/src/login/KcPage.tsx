import { lazy, Suspense } from 'react'
import { buttonClass, controlClass } from '@alinha/ui'
import type { ClassKey } from 'keycloakify/login'
import DefaultPage from 'keycloakify/login/DefaultPage'
import { useI18n } from './i18n'
import type { KcContext } from './KcContext'
import Template from './Template'

const UserProfileFormFields = lazy(() => import('keycloakify/login/UserProfileFormFields'))
const Login = lazy(() => import('./pages/Login'))
const Register = lazy(() => import('./pages/Register'))
const LoginConfigTotp = lazy(() => import('./pages/LoginConfigTotp'))
const LoginOtp = lazy(() => import('./pages/LoginOtp'))
const LoginResetPassword = lazy(() => import('./pages/LoginResetPassword'))
const LoginUpdatePassword = lazy(() => import('./pages/LoginUpdatePassword'))
const LoginVerifyEmail = lazy(() => import('./pages/LoginVerifyEmail'))
const LoginPageExpired = lazy(() => import('./pages/LoginPageExpired'))
const Info = lazy(() => import('./pages/Info'))
const ErrorPage = lazy(() => import('./pages/ErrorPage'))

/**
 * Keycloak's own CSS (PatternFly) is off. The pages below are ours; every other page is Keycloakify's default markup
 * inside our Template, styled through `classes` with the @alinha/ui control and button styles.
 */
const classes = {
  kcFormClass: 'flex flex-col gap-5',
  kcFormGroupClass: 'flex flex-col gap-1.5',
  kcLabelClass: 'text-[0.8125rem] font-semibold text-ink-soft',
  kcInputClass: controlClass(false),
  kcTextareaClass: controlClass(false),
  kcInputErrorMessageClass: 'text-[0.8125rem] font-semibold text-danger',
  kcInputHelperTextAfterClass: 'text-[0.8125rem] text-muted',
  kcInputGroup: 'flex gap-2',
  kcButtonPrimaryClass: buttonClass({}),
  kcButtonDefaultClass: buttonClass({ variant: 'secondary' }),
  kcButtonBlockClass: 'w-full',
  kcFormButtonsClass: 'flex flex-wrap gap-3',
  kcFormOptionsWrapperClass: 'text-[0.875rem]',
  kcCheckboxInputClass: 'size-[18px] accent-brand',
  kcSrOnlyClass: 'sr-only',
} satisfies { [key in ClassKey]?: string }

export default function KcPage({ kcContext }: { kcContext: KcContext }) {
  const { i18n } = useI18n({ kcContext })
  const common = { i18n, classes, Template, doUseDefaultCss: false } as const

  return (
    <Suspense>
      {(() => {
        switch (kcContext.pageId) {
          case 'login.ftl':
            return <Login kcContext={kcContext} {...common} />
          case 'register.ftl':
            return <Register kcContext={kcContext} {...common} />
          case 'login-config-totp.ftl':
            return <LoginConfigTotp kcContext={kcContext} {...common} />
          case 'login-otp.ftl':
            return <LoginOtp kcContext={kcContext} {...common} />
          case 'login-reset-password.ftl':
            return <LoginResetPassword kcContext={kcContext} {...common} />
          case 'login-update-password.ftl':
            return <LoginUpdatePassword kcContext={kcContext} {...common} />
          case 'login-verify-email.ftl':
            return <LoginVerifyEmail kcContext={kcContext} {...common} />
          case 'login-page-expired.ftl':
            return <LoginPageExpired kcContext={kcContext} {...common} />
          case 'info.ftl':
            return <Info kcContext={kcContext} {...common} />
          case 'error.ftl':
            return <ErrorPage kcContext={kcContext} {...common} />
          default:
            return <DefaultPage kcContext={kcContext} {...common} UserProfileFormFields={UserProfileFormFields} doMakeUserConfirmPassword />
        }
      })()}
    </Suspense>
  )
}
