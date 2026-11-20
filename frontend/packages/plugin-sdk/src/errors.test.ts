import { describe, expect, it } from 'vitest'
import { ERROR_MESSAGES, errorMessage, fieldErrors, NetworkError } from './errors'
import { ApiError } from './index'

/** An ApiError carrying a Problem Details body, as the shell will attach it (#101). */
function withProblem(status: number, problem: unknown): ApiError {
  return Object.assign(new ApiError(status, '/x'), { problem })
}

describe('errorMessage', () => {
  it.each([
    [400, ERROR_MESSAGES.badRequest],
    [422, ERROR_MESSAGES.badRequest],
    [401, ERROR_MESSAGES.unauthenticated],
    [403, ERROR_MESSAGES.forbidden],
    [404, ERROR_MESSAGES.notFound],
    [410, ERROR_MESSAGES.notFound],
    [409, ERROR_MESSAGES.conflict],
    [412, ERROR_MESSAGES.conflict],
    [413, ERROR_MESSAGES.tooLarge],
    [429, ERROR_MESSAGES.tooManyRequests],
    [500, ERROR_MESSAGES.unavailable],
    [503, ERROR_MESSAGES.unavailable],
    [418, ERROR_MESSAGES.unexpected],
  ])('maps HTTP %i to a plain message', (status, expected) => {
    expect(errorMessage(new ApiError(status, '/x'))).toBe(expected)
  })

  it('uses the detail of an aLinha problem (one with a code)', () => {
    const error = withProblem(409, { code: 'NIF_ALREADY_REGISTERED', detail: 'Já existe uma organização com este NIF.' })
    expect(errorMessage(error)).toBe('Já existe uma organização com este NIF.')
  })

  it('ignores a detail without a code, which may be a technical English default', () => {
    const error = withProblem(405, { detail: "Request method 'PUT' is not supported." })
    expect(errorMessage(error)).toBe(ERROR_MESSAGES.unexpected)
  })

  it('ignores an empty detail', () => {
    expect(errorMessage(withProblem(403, { code: 'X', detail: '  ' }))).toBe(ERROR_MESSAGES.forbidden)
  })

  it('ignores a problem that is not an object', () => {
    expect(errorMessage(withProblem(404, 'oops'))).toBe(ERROR_MESSAGES.notFound)
  })

  it('reports a NetworkError as a connection problem', () => {
    const error = new NetworkError('/x', { cause: new TypeError('Failed to fetch') })
    expect(errorMessage(error)).toBe(ERROR_MESSAGES.network)
    expect(error.message).toBe('No response from /api/x')
  })

  it('does not blame the connection for a TypeError from a bug', () => {
    expect(errorMessage(new TypeError("Cannot read properties of undefined (reading 'x')"))).toBe(ERROR_MESSAGES.unexpected)
  })

  it.each([new Error('boom'), 'boom', null])('falls back to a generic message for %j', (error) => {
    expect(errorMessage(error)).toBe(ERROR_MESSAGES.unexpected)
  })
})

describe('fieldErrors', () => {
  it('returns the first message for each field and skips malformed entries', () => {
    const error = withProblem(400, {
      errors: [
        { field: 'nif', message: 'O NIF tem 9 dígitos.' },
        { field: 'nif', message: 'Segunda mensagem.' },
        { field: 'name', message: 'Indique o nome.' },
        { field: 'broken' },
        null,
      ],
    })
    expect(fieldErrors(error)).toEqual({ nif: 'O NIF tem 9 dígitos.', name: 'Indique o nome.' })
  })

  it('keeps field names that are also Object properties', () => {
    const error = withProblem(400, {
      errors: [
        { field: 'constructor', message: 'A' },
        { field: 'toString', message: 'B' },
        { field: '__proto__', message: 'C' },
      ],
    })
    const result = fieldErrors(error)
    expect(Object.keys(result)).toEqual(['constructor', 'toString', '__proto__'])
    expect(result.constructor).toBe('A')
    expect(Object.getPrototypeOf(result)).toBe(Object.prototype)
  })

  it('is empty without validation errors', () => {
    expect(fieldErrors(withProblem(400, { code: 'X' }))).toEqual({})
    expect(fieldErrors(new ApiError(400, '/x'))).toEqual({})
    expect(fieldErrors(new Error('x'))).toEqual({})
  })
})
