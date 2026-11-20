import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { ApiError, PluginHostContext, type PluginHost } from './index'
import { useApiMutation, useApiQuery } from './query'

afterEach(cleanup)

function setup(api: PluginHost['api']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const host: PluginHost = { tenantId: 't1', organization: null, user: null, can: () => true, api, reauthenticate: () => {} }
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <PluginHostContext.Provider value={host}>{children}</PluginHostContext.Provider>
    </QueryClientProvider>
  )
  return { queryClient, wrapper }
}

describe('useApiQuery', () => {
  it('reads the path through host.api and passes the abort signal', async () => {
    const api = vi.fn().mockResolvedValue([{ id: 1 }])
    const { wrapper } = setup(api as PluginHost['api'])
    const { result } = renderHook(() => useApiQuery<{ id: number }[]>(['p', 'items'], '/p/items'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual([{ id: 1 }])
    expect(api).toHaveBeenCalledWith('/p/items', { signal: expect.any(AbortSignal) })
  })

  it('adds the path to the key, so records with the same key never share a cache entry', async () => {
    const api = vi.fn(async (path: string) => ({ path }))
    const { queryClient, wrapper } = setup(api as PluginHost['api'])
    const { result, rerender } = renderHook(({ id }) => useApiQuery<{ path: string }>(['p', 'item'], `/p/items/${id}`), {
      wrapper,
      initialProps: { id: 1 },
    })
    await waitFor(() => expect(result.current.data).toEqual({ path: '/p/items/1' }))

    rerender({ id: 2 })

    expect(result.current.data).toBeUndefined()
    await waitFor(() => expect(result.current.data).toEqual({ path: '/p/items/2' }))
    expect(queryClient.getQueryData(['p', 'item', '/p/items/1'])).toEqual({ path: '/p/items/1' })
  })

  it('exposes the ApiError', async () => {
    const { wrapper } = setup(vi.fn().mockRejectedValue(new ApiError(403, '/p/items')) as PluginHost['api'])
    const { result } = renderHook(() => useApiQuery(['p', 'items'], '/p/items'), { wrapper })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error).toBeInstanceOf(ApiError)
  })

  it('passes options through', () => {
    const api = vi.fn()
    const { wrapper } = setup(api as PluginHost['api'])
    renderHook(() => useApiQuery(['p'], '/p', { enabled: false }), { wrapper })
    expect(api).not.toHaveBeenCalled()
  })
})

describe('useApiMutation', () => {
  it('posts the body as JSON by default and refreshes the listed queries', async () => {
    const api = vi.fn().mockResolvedValue({ id: 2 })
    const { queryClient, wrapper } = setup(api as PluginHost['api'])
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const onSuccess = vi.fn()
    const { result } = renderHook(
      () => useApiMutation<{ name: string }, { id: number }>({ path: '/p/items', invalidates: [['p', 'items']], onSuccess }),
      { wrapper },
    )

    result.current.mutate({ name: 'Servidor' })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(api).toHaveBeenCalledWith('/p/items', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{"name":"Servidor"}',
    })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['p', 'items'] })
    expect(onSuccess).toHaveBeenCalledWith({ id: 2 }, { name: 'Servidor' }, undefined, expect.anything())
    expect(invalidate.mock.invocationCallOrder[0]).toBeLessThan(onSuccess.mock.invocationCallOrder[0])
  })

  it('builds the path from the body and sends no body when there is none', async () => {
    const api = vi.fn().mockResolvedValue(undefined)
    const { wrapper } = setup(api as PluginHost['api'])
    const { result } = renderHook(
      () => useApiMutation<{ id: number } | undefined>({ method: 'DELETE', path: (item) => `/p/items/${item?.id ?? 'all'}` }),
      { wrapper },
    )

    result.current.mutate(undefined)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(api).toHaveBeenCalledWith('/p/items/all', { method: 'DELETE' })
  })

  it('does not refresh anything when the write fails', async () => {
    const { queryClient, wrapper } = setup(vi.fn().mockRejectedValue(new ApiError(409, '/p')) as PluginHost['api'])
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const { result } = renderHook(() => useApiMutation<{ a: number }>({ path: '/p', invalidates: [['p']] }), { wrapper })

    result.current.mutate({ a: 1 })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(invalidate).not.toHaveBeenCalled()
  })
})
