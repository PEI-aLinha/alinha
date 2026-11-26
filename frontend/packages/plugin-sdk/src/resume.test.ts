import { afterEach, describe, expect, it } from 'vitest'
import { RESUME_MAX_AGE_MS, rememberReturnPath, takeReturnPath } from './resume'

afterEach(() => sessionStorage.clear())

describe('rememberReturnPath / takeReturnPath', () => {
  it('returns the remembered page once', () => {
    rememberReturnPath('/org/acme/integrations/code-repository', 1000)

    expect(takeReturnPath(2000)).toBe('/org/acme/integrations/code-repository')
    expect(takeReturnPath(2000)).toBeNull()
  })

  it('accepts a page with an encoded character that stays inside the organization', () => {
    rememberReturnPath('/org/acme/caf%C3%A9.v1', 0)
    expect(takeReturnPath(1)).toBe('/org/acme/caf%C3%A9.v1')
  })

  it('ignores a stored encoded parent segment', () => {
    sessionStorage.setItem('alinha:resume', JSON.stringify({ path: '/org/acme/%2e%2e/%2e%2e/x', at: 0 }))
    expect(takeReturnPath(1)).toBeNull()
  })

  it('accepts a consultant page', () => {
    rememberReturnPath('/portfolio/acme/integrations/code-repository', 0)
    expect(takeReturnPath(1)).toBe('/portfolio/acme/integrations/code-repository')
  })

  it('forgets an expired page', () => {
    rememberReturnPath('/org/acme/x', 0)
    expect(takeReturnPath(RESUME_MAX_AGE_MS + 1)).toBeNull()
  })

  it.each(['https://evil.example/', '//evil.example', '/login', '/org/acme/../../x', '/org/acme?x=1', '/org/',
    '/org/acme/%2e%2e/%2E%2E/x', '/org/acme/%2e/x', '/org/acme/.%2E/x', '/org/acme%2F..%2F..%2Fx', '/org/acme/%5C..', '/org/acme/%E0%A4%A'])(
    'refuses %s',
    (path) => {
      expect(() => rememberReturnPath(path)).toThrow()
    },
  )

  it.each([
    'not json',
    JSON.stringify({ path: '//evil.example', at: 0 }),
    JSON.stringify({ path: '/org/acme', at: 'x' }),
    JSON.stringify({ at: 0 }),
  ])('ignores a tampered value %s', (value) => {
    sessionStorage.setItem('alinha:resume', value)
    expect(takeReturnPath(1)).toBeNull()
  })
})
