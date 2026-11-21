import { useEffect, type ReactNode } from 'react'
import { Check, RotateCcw } from 'lucide-react'
import { cn, Stepper } from '@alinha/ui'
import type { TemplateProps } from 'keycloakify/login/TemplateProps'
import { useInitialize } from 'keycloakify/login/Template.useInitialize'
import logoUrl from '../assets/alinha-logo.svg'
import { Alert, StepBadge } from './components'
import type { I18n } from './i18n'
import type { KcContext } from './KcContext'

/**
 * Where a page sits in the self-registration flow (ADR 0004): account → email → security → organization, the last
 * step being the onboarding area of the app. Only pages that every visitor reaches through registration show the
 * steps: a consultant created by an admin also goes through TOTP setup, and "Organização" would be wrong for them.
 * Email verification only reaches self-registered accounts (seeded and admin-created ones are already verified).
 */
const FLOW_STEP: Partial<Record<KcContext['pageId'], number>> = { 'register.ftl': 0, 'login-verify-email.ftl': 1 }
const FLOW_KEYS = ['alinhaStepAccount', 'alinhaStepEmail', 'alinhaStepSecurity', 'alinhaStepOrganization'] as const

// Static network of points and lines on the brand panel (prototype OnboardingLayout, viewBox 500×900).
const NODES: Array<[number, number]> = [
  [195, 32], [322, 48], [80, 112], [150, 138], [292, 120], [368, 88], [420, 132], [342, 150],
  [92, 248], [64, 330], [200, 335], [234, 316], [372, 310], [492, 380], [305, 402], [150, 488],
  [258, 465], [210, 580], [160, 548], [316, 548], [42, 468], [428, 474], [212, 666], [404, 676],
  [26, 645], [242, 740], [428, 720], [372, 856],
]
const EDGES: Array<[number, number]> = [
  [0, 1], [0, 3], [0, 2], [1, 5], [1, 4], [4, 6], [5, 6], [5, 7], [4, 7], [3, 4], [2, 3], [3, 8],
  [8, 9], [9, 10], [10, 11], [11, 12], [12, 13], [10, 14], [11, 14], [14, 16], [15, 16], [15, 17],
  [16, 17], [17, 18], [17, 19], [16, 19], [9, 20], [20, 15], [14, 21], [12, 21], [17, 22], [22, 25],
  [22, 23], [24, 25], [23, 26], [10, 18], [15, 18], [16, 21],
]

function Network() {
  return (
    <svg aria-hidden viewBox="0 0 500 900" preserveAspectRatio="xMidYMid slice" className="absolute inset-0 size-full">
      {EDGES.map(([a, b]) => (
        <line key={`${a}-${b}`} x1={NODES[a][0]} y1={NODES[a][1]} x2={NODES[b][0]} y2={NODES[b][1]} className="stroke-white" strokeOpacity={0.14} strokeWidth={1} />
      ))}
      {NODES.map(([x, y]) => <circle key={`${x}-${y}`} cx={x} cy={y} r={2} className="fill-white" fillOpacity={0.35} />)}
    </svg>
  )
}

/** Steps on the dark brand panel: vertical, white (prototype Stepper, orientation="vertical"). */
function PanelSteps({ labels, current, label, i18n }: { labels: string[]; current: number; label: string; i18n: I18n }) {
  return (
    <ol aria-label={label} className="flex flex-col gap-4">
      {labels.map((s, i) => {
        const done = i < current
        const active = i === current
        const status = i18n.msgStr(done ? 'alinhaStepDone' : active ? 'alinhaStepCurrent' : 'alinhaStepTodo')
        return (
          <li key={s} aria-current={active ? 'step' : undefined} className="flex items-center gap-3">
            <span
              aria-hidden
              className={cn(
                'grid size-7 shrink-0 place-items-center rounded-full text-[0.8125rem] font-bold',
                done ? 'bg-white/90 text-brand' : active ? 'bg-signal text-white' : 'border border-white/50 text-white/75',
              )}
            >
              {done ? <Check className="size-3.5" strokeWidth={3} /> : i + 1}
            </span>
            <span className={cn('text-[0.9375rem]', active ? 'font-bold text-white' : 'text-white/80')}>
              {s}
              <span className="sr-only">{` (${status})`}</span>
            </span>
          </li>
        )
      })}
    </ol>
  )
}

/** Dark mode follows the system: the shell's appearance menu is not reachable before login. */
function useSystemTheme() {
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const apply = () => {
      document.documentElement.dataset.theme = mq.matches ? 'dark' : 'light'
    }
    apply()
    mq.addEventListener('change', apply)
    return () => mq.removeEventListener('change', apply)
  }, [])
}

function LanguageLinks({ i18n }: { i18n: I18n }) {
  const { enabledLanguages, currentLanguage } = i18n
  if (enabledLanguages.length < 2) return null
  return (
    <nav aria-label={i18n.msgStr('alinhaLanguage')} className="flex flex-wrap items-center justify-end gap-1 text-[0.8125rem]">
      {enabledLanguages.map(({ languageTag, label, href }) => {
        const current = languageTag === currentLanguage.languageTag
        return (
          <a
            key={languageTag}
            href={href}
            lang={languageTag}
            aria-current={current ? 'true' : undefined}
            className={cn('rounded-full px-2.5 py-1', current ? 'bg-paper-deep font-bold text-ink' : 'text-ink-soft hover:text-ink')}
          >
            {label}
          </a>
        )
      })}
    </nav>
  )
}

/**
 * Layout of every Keycloak page (prototype OnboardingLayout): brand panel with the registration steps on the left,
 * the page on the right. Below `lg` the panel goes away and the logo and steps sit above the title.
 * Keycloakify's default pages render inside it too, so pages we did not redesign still look like aLinha.
 */
export default function Template(props: TemplateProps<KcContext, I18n>) {
  const { displayInfo = false, displayMessage = true, headerNode, socialProvidersNode = null, infoNode = null, documentTitle, kcContext, i18n, doUseDefaultCss, children } = props
  const { msg, msgStr } = i18n
  const { realm, auth, url, message, isAppInitiatedAction } = kcContext

  useSystemTheme()
  useEffect(() => {
    document.title = documentTitle ?? msgStr('loginTitle', realm.displayName || realm.name)
    document.documentElement.lang = i18n.currentLanguage.languageTag
  }, [documentTitle, msgStr, realm.displayName, realm.name, i18n.currentLanguage.languageTag])

  const { isReadyToRender } = useInitialize({ kcContext, doUseDefaultCss })
  if (!isReadyToRender) return null

  const step = FLOW_STEP[kcContext.pageId]
  const steps = FLOW_KEYS.map((k) => msgStr(k))
  const flowLabel = msgStr('alinhaFlowLabel')
  // App-initiated actions should not see warnings about the action they are completing (Keycloak's own rule).
  const showMessage = displayMessage && message !== undefined && (message.type !== 'warning' || !isAppInitiatedAction)
  const tagline = msgStr('alinhaTagline').split('\n')

  let heading: ReactNode = <h1 className="text-[1.875rem] font-extrabold title-tight text-ink">{headerNode}</h1>
  if (auth?.showUsername && !auth.showResetCredentials) {
    heading = (
      <div className="flex flex-col gap-2">
        {heading}
        <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[0.9375rem] text-ink-soft">
          <span className="font-semibold text-ink">{auth.attemptedUsername}</span>
          <a href={url.loginRestartFlowUrl} className="inline-flex items-center gap-1 text-[0.8125rem] font-semibold text-brand hover:underline">
            <RotateCcw className="size-3.5" aria-hidden />
            {msg('alinhaRestartLogin')}
          </a>
        </p>
      </div>
    )
  }

  return (
    <div className="flex min-h-dvh bg-paper text-ink">
      <aside className="brand-panel sticky top-0 flex h-dvh w-[500px] shrink-0 flex-col justify-between overflow-hidden bg-brand-deep bg-gradient-to-br from-brand-deep via-brand/90 to-signal/75 px-14 pb-28 pt-14 max-xl:w-[420px] max-xl:px-11 max-lg:hidden">
        <Network />
        <img src={logoUrl} alt="aLinha" width={985} height={404} className="relative h-10 w-fit brightness-0 invert" />
        <p aria-hidden className="relative text-[4rem] font-extrabold leading-[0.98] tracking-[-0.03em] text-white">
          {tagline[0]}
          <br />
          {tagline[1]}
        </p>
        <div className="relative min-h-[156px]">
          {step !== undefined && <PanelSteps labels={steps} current={step} label={flowLabel} i18n={i18n} />}
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col px-10 pb-16 pt-8 max-md:px-5 max-md:pt-6">
        <div className="flex items-center justify-between gap-4 lg:justify-end">
          <img src={logoUrl} alt="aLinha" width={985} height={404} className="logo-adaptive h-9 w-auto lg:hidden" />
          <LanguageLinks i18n={i18n} />
        </div>
        <div className="flex flex-1 items-center">
          <div className="mx-auto flex w-full max-w-[620px] flex-col gap-7 py-10 max-md:py-6">
            {step !== undefined && (
              <div className="lg:hidden">
                <Stepper steps={steps.map((label) => ({ label }))} current={step} label={flowLabel} />
              </div>
            )}
            <main id="conteudo" className="flex flex-col gap-6">
              <header className="flex flex-col gap-3">
                {step !== undefined && <StepBadge>{msgStr('alinhaStepOf', String(step + 1), String(steps.length))}</StepBadge>}
                {heading}
              </header>
              {showMessage && <Alert type={message.type} summary={message.summary} />}
              {children}
              {auth?.showTryAnotherWayLink && (
                <form id="kc-select-try-another-way-form" action={url.loginAction} method="post">
                  <input type="hidden" name="tryAnotherWay" value="on" />
                  <button type="submit" className="text-[0.875rem] font-semibold text-brand hover:underline">
                    {msg('doTryAnotherWay')}
                  </button>
                </form>
              )}
              {socialProvidersNode}
              {displayInfo && <div className="text-[0.9375rem] text-ink-soft">{infoNode}</div>}
            </main>
          </div>
        </div>
      </div>
    </div>
  )
}
