// ApiError stays in index.ts (the contract with the shell); it is only used when a function runs, so the
// circular import is safe.
import { ApiError, type Problem } from './index'

/**
 * Turns any error from `host.api` (or a query or mutation) into a short message in plain European Portuguese,
 * ready to show to the user. Pages show this, never `error.message`, which is technical and in English.
 *
 * When the backend sends an aLinha Problem Details body (ADR 0003: it has a `code`, and `detail` is written for
 * the user in Portuguese), its `detail` is used. Otherwise the message depends only on the HTTP status.
 */
export function errorMessage(error: unknown): string {
  const problem = problemOf(error)
  if (problem?.code && problem.detail?.trim()) return problem.detail
  if (error instanceof ApiError) return messageForStatus(error.status)
  if (error instanceof NetworkError) return ERROR_MESSAGES.network
  return ERROR_MESSAGES.unexpected
}

/**
 * Validation errors from the backend, by field (`{ nif: 'O NIF tem 9 dígitos.' }`), to show next to each input.
 * Empty when the error has none. If a field has several messages, the first one wins.
 */
export function fieldErrors(error: unknown): Record<string, string> {
  // A Map, not a plain object: field names come from the server, and `constructor` or `__proto__` must stay data.
  const byField = new Map<string, string>()
  for (const { field, message } of problemOf(error)?.errors ?? []) {
    if (!byField.has(field)) byField.set(field, message)
  }
  return Object.fromEntries(byField)
}

/**
 * The request never got an answer (no connection, server unreachable, blocked by the browser). The shell throws it
 * when `fetch` itself fails, so [errorMessage] only blames the connection for real network failures, never for a
 * bug that happens to be a TypeError.
 */
export class NetworkError extends Error {
  readonly path: string

  constructor(path: string, options?: { cause?: unknown }) {
    super(`No response from /api${path}`, options)
    this.name = 'NetworkError'
    this.path = path
  }
}

/** Default messages. Plain language: say what happened and what the person can do, without technical terms. */
export const ERROR_MESSAGES = {
  badRequest: 'Alguns dados não estão corretos. Reveja-os e tente de novo.',
  unauthenticated: 'A sua sessão terminou. Entre novamente para continuar.',
  forbidden: 'Não tem permissão para fazer isto.',
  notFound: 'Não encontrámos o que procura. Pode ter sido apagado.',
  conflict: 'Estes dados foram alterados entretanto. Atualize a página e tente de novo.',
  tooLarge: 'O ficheiro é demasiado grande.',
  tooManyRequests: 'Fez muitos pedidos seguidos. Aguarde um pouco e tente de novo.',
  unavailable: 'O serviço está com dificuldades. Tente de novo dentro de alguns minutos.',
  network: 'Não foi possível ligar ao serviço. Verifique a sua ligação à internet.',
  unexpected: 'Ocorreu um erro inesperado. Tente de novo.',
} as const

function messageForStatus(status: number): string {
  if (status === 400 || status === 422) return ERROR_MESSAGES.badRequest
  if (status === 401) return ERROR_MESSAGES.unauthenticated
  if (status === 403) return ERROR_MESSAGES.forbidden
  if (status === 404 || status === 410) return ERROR_MESSAGES.notFound
  if (status === 409 || status === 412) return ERROR_MESSAGES.conflict
  if (status === 413) return ERROR_MESSAGES.tooLarge
  if (status === 429) return ERROR_MESSAGES.tooManyRequests
  if (status >= 500) return ERROR_MESSAGES.unavailable
  return ERROR_MESSAGES.unexpected
}

/** The Problem Details body attached to an ApiError, if any. The shell has already checked its fields' types. */
function problemOf(error: unknown): Problem | undefined {
  return error instanceof ApiError ? error.problem : undefined
}
