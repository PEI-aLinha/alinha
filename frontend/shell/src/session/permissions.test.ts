import { describe, expect, it } from 'vitest'
import { isClientUser, isConsultant, makeCan, roleLabel } from './permissions'

describe('makeCan', () => {
  const can = makeCan(['asset:read', 'asset:edit', 'risk:read'])

  it('checks "<resource>:<action>", with read by default', () => {
    expect(can('asset')).toBe(true)
    expect(can('asset', 'edit')).toBe(true)
    expect(can('asset', 'delete')).toBe(false)
    expect(can('roadmap')).toBe(false)
  })

  it('does not match partial names', () => {
    expect(makeCan(['asset:read'])('asse')).toBe(false)
    expect(makeCan(['asset:readonly'])('asset')).toBe(false)
  })

  it('denies everything without permissions', () => {
    expect(makeCan([])('asset')).toBe(false)
  })
})

describe('roles', () => {
  it('tells consultants from client users', () => {
    expect(isConsultant(['consultant'])).toBe(true)
    expect(isConsultant(['responsible'])).toBe(false)
    expect(isClientUser(['responsible'])).toBe(true)
    expect(isClientUser(['analyst'])).toBe(true)
    expect(isClientUser(['consultant'])).toBe(false)
    expect(isClientUser([])).toBe(false)
  })

  it('labels each role in Portuguese', () => {
    expect(roleLabel(['consultant'])).toBe('Consultoria aLinha')
    expect(roleLabel(['responsible'])).toBe('Responsável')
    expect(roleLabel(['analyst'])).toBe('Analista')
    expect(roleLabel([])).toBe('')
  })
})
