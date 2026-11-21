import { describe, expect, it } from 'vitest'
import { passwordRules } from './passwordRules'

const msgStr = (key: string, ...args: string[]) => [key, ...args].join(':')

describe('passwordRules', () => {
  it('has no rules without a policy', () => {
    expect(passwordRules(undefined, msgStr)).toEqual([])
  })

  it('checks the minimum length from the policy', () => {
    const [rule] = passwordRules({ length: 12 }, msgStr)
    expect(rule.label).toBe('alinhaRuleLength:12')
    expect(rule.test('a'.repeat(11))).toBe(false)
    expect(rule.test('a'.repeat(12))).toBe(true)
  })

  it('asks for letters and numbers when the policy wants digits', () => {
    const [rule] = passwordRules({ digits: 1 }, msgStr)
    expect(rule.label).toBe('alinhaRuleLettersDigits')
    expect(rule.test('123456789012')).toBe(false)
    expect(rule.test('abcdefghijkl')).toBe(false)
    expect(rule.test('açúcar2026')).toBe(true)
  })
})
