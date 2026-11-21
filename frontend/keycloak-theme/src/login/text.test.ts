import { describe, expect, it } from 'vitest'
import { plainText } from './text'

describe('plainText', () => {
  it('decodes entities', () => {
    expect(plainText('Palavra-passe inv&aacute;lida &#39;x&#39;')).toBe("Palavra-passe inválida 'x'")
  })

  it('drops markup instead of rendering it', () => {
    expect(plainText('<img src=x onerror=alert(1)>Erro')).toBe('Erro')
  })

  it('returns an empty string for no message', () => {
    expect(plainText('')).toBe('')
  })
})
