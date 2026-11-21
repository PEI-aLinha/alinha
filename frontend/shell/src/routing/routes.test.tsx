import { describe, expect, it } from 'vitest'
import { definePlugin, type FrontendPlugin } from '@alinha/plugin-sdk'
import { firstOnboardingPath } from './routes'

const Page = () => null
const withOnboarding = (onboarding: FrontendPlugin['routes']['onboarding']) =>
  [definePlugin({ id: 'p', name: 'P', routes: { onboarding } })]

describe('firstOnboardingPath', () => {
  it('finds a top-level page', () => {
    expect(firstOnboardingPath(withOnboarding([{ path: 'organization', Component: Page }]))).toBe('organization')
  })

  it('looks inside a pathless layout route', () => {
    const routes = [{ Component: Page, children: [{ path: 'organization', Component: Page }] }]
    expect(firstOnboardingPath(withOnboarding(routes))).toBe('organization')
  })

  it('stops at a route whose index child shows a page', () => {
    const routes = [{ path: 'organization', children: [{ index: true, Component: Page }, { path: 'start', Component: Page }] }]
    expect(firstOnboardingPath(withOnboarding(routes))).toBe('organization')
  })

  it('joins nested paths when the parent has no index page', () => {
    const routes = [{ path: 'organization', Component: Page, children: [{ path: ':step', Component: Page }, { path: 'start', Component: Page }] }]
    expect(firstOnboardingPath(withOnboarding(routes))).toBe('organization/start')
  })

  it('joins nested paths across a pathless layout route', () => {
    const routes = [{ path: 'organization', children: [{ Component: Page, children: [{ path: 'start', Component: Page }] }] }]
    expect(firstOnboardingPath(withOnboarding(routes))).toBe('organization/start')
  })

  it('skips a parent whose children have no static page', () => {
    const routes = [
      { path: 'organization', children: [{ path: ':step', Component: Page }] },
      { path: 'profile', Component: Page },
    ]
    expect(firstOnboardingPath(withOnboarding(routes))).toBe('profile')
  })

  it('skips dynamic segments and splats, which have no value to put in the address', () => {
    const routes = [
      { path: 'organization/:step', Component: Page },
      { path: 'files/*', Component: Page },
      { path: 'welcome', Component: Page },
    ]
    expect(firstOnboardingPath(withOnboarding(routes))).toBe('welcome')
  })

  it('is undefined when there is no static page', () => {
    expect(firstOnboardingPath(withOnboarding([{ path: ':id', Component: Page }]))).toBeUndefined()
    expect(firstOnboardingPath([])).toBeUndefined()
  })
})
