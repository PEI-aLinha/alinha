import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryKey,
  type UseMutationOptions,
  type UseQueryOptions,
} from '@tanstack/react-query'
// useHost lives in index.ts (the contract with the shell); it is only used when a hook runs, so the circular
// import is safe.
import { useHost } from './index'

/**
 * Reads from /api with the session and the current organization (`host.api`), cached by TanStack Query.
 *
 * The path is added to the end of `queryKey`, so `/risk-assets/assets/1` and `/risk-assets/assets/2` never share a
 * cache entry even with the same key. Start keys with the plugin id (`['risk-assets', 'assets']`) so plugins never
 * share one, and invalidate by that prefix. The shell gives each organization its own query cache, so keys do not
 * need the tenant. Errors are ApiError or NetworkError; show them with `errorMessage(query.error)`. Types for core
 * endpoints come from `@alinha/api-client`; `host.api` paths leave out `/api`, which the shell adds:
 * `useApiQuery<ApiResponse<'/api/me'>>(['core', 'me'], '/me')`.
 *
 * ```ts
 * const assets = useApiQuery<Asset[]>(['risk-assets', 'assets'], '/risk-assets/assets')
 * ```
 */
export function useApiQuery<T>(
  queryKey: QueryKey,
  path: string,
  options?: Omit<UseQueryOptions<T, Error, T, QueryKey>, 'queryKey' | 'queryFn'>,
) {
  const { api } = useHost()
  return useQuery<T, Error, T, QueryKey>({
    ...options,
    queryKey: [...queryKey, path],
    queryFn: ({ signal }) => api<T>(path, { signal }),
  })
}

export type ApiMethod = 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export type UseApiMutationOptions<TBody, TResult> = Omit<UseMutationOptions<TResult, Error, TBody>, 'mutationFn'> & {
  /** Defaults to POST. */
  method?: ApiMethod
  /** Fixed path, or built from the body (e.g. `(asset) => \`/risk-assets/assets/${asset.id}\``). */
  path: string | ((body: TBody) => string)
  /** Queries to refresh after success, so lists show the change (e.g. `[['risk-assets', 'assets']]`). */
  invalidates?: QueryKey[]
}

/**
 * Writes to /api: sends the body as JSON, then refreshes the queries in `invalidates`. Pass no body (`undefined`)
 * for a request without one, such as most DELETEs.
 *
 * ```ts
 * const create = useApiMutation<NewAsset, Asset>({ path: '/risk-assets/assets', invalidates: [['risk-assets', 'assets']] })
 * create.mutate({ name: 'Servidor de email' })
 * ```
 */
export function useApiMutation<TBody = void, TResult = unknown>({
  method = 'POST',
  path,
  invalidates = [],
  onSuccess,
  ...options
}: UseApiMutationOptions<TBody, TResult>) {
  const { api } = useHost()
  const queryClient = useQueryClient()
  return useMutation<TResult, Error, TBody>({
    ...options,
    mutationFn: (body) =>
      api<TResult>(typeof path === 'function' ? path(body) : path, {
        method,
        ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
      }),
    onSuccess: async (...args) => {
      await Promise.all(invalidates.map((queryKey) => queryClient.invalidateQueries({ queryKey })))
      return onSuccess?.(...args)
    },
  })
}
