import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { addToast, ToastProvider, useToast } from './Toast'

function Trigger({ message, tone }: { message: string; tone?: 'success' | 'error' }) {
  const toast = useToast()
  return <button onClick={() => toast.show(message, tone)}>show</button>
}

function renderToast(message: string, tone?: 'success' | 'error') {
  render(<ToastProvider><Trigger message={message} tone={tone} /></ToastProvider>)
  fireEvent.click(screen.getByText('show'))
}

describe('Toast', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { cleanup(); vi.useRealTimers() })

  it('announces success politely and dismisses it after a few seconds', () => {
    renderToast('Ativo guardado')
    expect(screen.getByRole('status')).toHaveTextContent('Ativo guardado')
    act(() => { vi.advanceTimersByTime(5000) })
    expect(screen.queryByText('Ativo guardado')).not.toBeInTheDocument()
  })

  it('keeps errors as an alert until they are closed', () => {
    renderToast('Não foi possível guardar', 'error')
    expect(screen.getByRole('alert')).toHaveTextContent('Não foi possível guardar')
    act(() => { vi.advanceTimersByTime(60_000) })
    expect(screen.getByRole('alert')).toHaveTextContent('Não foi possível guardar')
    fireEvent.click(screen.getByRole('button', { name: 'Fechar aviso' }))
    expect(screen.getByRole('alert')).toBeEmptyDOMElement()
  })

  it('keeps both live regions mounted and empty before any toast, so the first one is announced', () => {
    render(<ToastProvider><Trigger message="x" /></ToastProvider>)
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
    expect(screen.getByRole('alert')).toBeEmptyDOMElement()
  })

  it('never hides the live regions, even while empty', () => {
    render(<ToastProvider><Trigger message="x" /></ToastProvider>)
    expect(screen.getByRole('status').className).not.toMatch(/hidden/)
    expect(screen.getByRole('alert').className).not.toMatch(/hidden/)
  })

  it('clears the timer of a success toast dropped to make room', () => {
    render(<ToastProvider><Trigger message="Ativo guardado" /></ToastProvider>)
    for (let i = 0; i < 4; i++) fireEvent.click(screen.getByText('show'))
    expect(vi.getTimerCount()).toBe(3)
  })

  it('clears its timers on unmount', () => {
    const { unmount } = render(<ToastProvider><Trigger message="Ativo guardado" /></ToastProvider>)
    fireEvent.click(screen.getByText('show'))
    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('clears the timer of a toast that is already gone', () => {
    render(<ToastProvider><Trigger message="Ativo guardado" /></ToastProvider>)
    fireEvent.click(screen.getByText('show'))
    act(() => { vi.advanceTimersByTime(5000) })
    expect(vi.getTimerCount()).toBe(0)
  })
})

describe('addToast', () => {
  const success = (id: number) => ({ id, message: `s${id}`, tone: 'success' as const })
  const error = (id: number) => ({ id, message: `e${id}`, tone: 'error' as const })

  it('drops the oldest success when there are too many', () => {
    const items = [success(1), success(2), success(3)]
    expect(addToast(items, success(4)).map((t) => t.id)).toEqual([2, 3, 4])
  })

  it('never drops an error to make room', () => {
    const items = [error(1), success(2), success(3), success(4)]
    expect(addToast(items, success(5)).map((t) => t.id)).toEqual([1, 3, 4, 5])
    expect(addToast([error(1), error(2), error(3)], error(4)).map((t) => t.id)).toEqual([1, 2, 3, 4])
  })

  it('does not stack the same error twice', () => {
    const items = [error(1)]
    expect(addToast(items, { id: 2, message: 'e1', tone: 'error' })).toBe(items)
  })
})
