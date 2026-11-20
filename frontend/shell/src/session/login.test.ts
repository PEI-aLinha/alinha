import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { isSafeReturnPath, login, LOGIN_PATH } from './login'

describe('login', () => {
  const assign = vi.fn()

  beforeEach(() => {
    sessionStorage.clear()
    vi.stubGlobal('location', { ...window.location, assign, pathname: '/org/acme/assets', search: '?f=1', hash: '#top' })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    assign.mockReset()
  })

  it('goes to the BFF login and remembers the current page', () => {
    login.go()
    expect(assign).toHaveBeenCalledWith(LOGIN_PATH)
    expect(login.returnPath()).toBe('/org/acme/assets?f=1#top')
    expect(login.recentlyAttempted()).toBe(true)
  })

  it('does not remember the home page or unsafe addresses', () => {
    login.go('/')
    expect(login.returnPath()).toBeNull()
    login.go('//evil.example/x')
    expect(login.returnPath()).toBeNull()
  })

  it('forgets an attempt after one minute', () => {
    login.go()
    expect(login.recentlyAttempted(Date.now() + 59_000)).toBe(true)
    expect(login.recentlyAttempted(Date.now() + 61_000)).toBe(false)
  })

  it('clears the attempt and the return address once the session is active', () => {
    login.go()
    login.succeeded()
    expect(login.recentlyAttempted()).toBe(false)
    expect(login.returnPath()).toBeNull()
  })

  it('reset() only forgets the attempt', () => {
    login.go()
    login.reset()
    expect(login.recentlyAttempted()).toBe(false)
    expect(login.returnPath()).toBe('/org/acme/assets?f=1#top')
  })

  it('works without sessionStorage (e.g. blocked by the browser)', () => {
    vi.spyOn(window, 'sessionStorage', 'get').mockImplementation(() => { throw new Error('blocked') })
    expect(() => login.go()).not.toThrow()
    expect(login.recentlyAttempted()).toBe(false)
    expect(login.returnPath()).toBeNull()
    expect(() => login.succeeded()).not.toThrow()
    vi.restoreAllMocks()
  })
})

describe('isSafeReturnPath', () => {
  it('accepts same-origin paths only', () => {
    expect(isSafeReturnPath('/portfolio')).toBe(true)
    expect(isSafeReturnPath('//evil.example')).toBe(false)
    expect(isSafeReturnPath('/\\evil.example')).toBe(false)
    expect(isSafeReturnPath('https://evil.example')).toBe(false)
    expect(isSafeReturnPath('')).toBe(false)
    expect(isSafeReturnPath(null)).toBe(false)
  })
})
