import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { addToast, ToastProvider, useToast } from './Toast'

function Trigger({ message, tone }: { message: string; tone?: 'success' | 'info' | 'error' }) {
  const toast = useToast()
  return <button onClick={() => toast.show(message, tone)}>show</button>
}

function renderToast(message: string, tone?: 'success' | 'info' | 'error') {
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
    expect(vi.getTimerCount()).toBe(2)
  })

  it('announces info politely and dismisses it like a success', () => {
    renderToast('Pergunta deixada por responder', 'info')
    expect(screen.getByRole('status')).toHaveTextContent('Pergunta deixada por responder')
    act(() => { vi.advanceTimersByTime(5000) })
    expect(screen.queryByText('Pergunta deixada por responder')).not.toBeInTheDocument()
  })

  it('waits while the pointer is on the toast and resumes with the time left', () => {
    renderToast('Ativo guardado')
    act(() => { vi.advanceTimersByTime(3000) })
    fireEvent.mouseEnter(screen.getByText('Ativo guardado'))
    act(() => { vi.advanceTimersByTime(60_000) })
    expect(screen.getByText('Ativo guardado')).toBeInTheDocument()
    fireEvent.mouseLeave(screen.getByText('Ativo guardado'))
    act(() => { vi.advanceTimersByTime(1000) })
    expect(screen.getByText('Ativo guardado')).toBeInTheDocument()
    act(() => { vi.advanceTimersByTime(300) })
    expect(screen.queryByText('Ativo guardado')).not.toBeInTheDocument()
  })

  it('gives every toast a close button the keyboard can reach', () => {
    renderToast('Ativo guardado')
    const close = screen.getByRole('button', { name: 'Fechar aviso' })
    expect(close.tabIndex).toBe(0)
    fireEvent.click(close)
    expect(screen.queryByText('Ativo guardado')).not.toBeInTheDocument()
  })

  it('waits while the keyboard focus is in the toasts and resumes with the time left when it leaves', () => {
    renderToast('Ativo guardado')
    act(() => { vi.advanceTimersByTime(3000) })
    act(() => { screen.getByRole('button', { name: 'Fechar aviso' }).focus() })
    act(() => { vi.advanceTimersByTime(60_000) })
    expect(screen.getByText('Ativo guardado')).toBeInTheDocument()

    act(() => { screen.getByText('show').focus() })
    act(() => { vi.advanceTimersByTime(1000) })
    expect(screen.getByText('Ativo guardado')).toBeInTheDocument()
    act(() => { vi.advanceTimersByTime(300) })
    expect(screen.queryByText('Ativo guardado')).not.toBeInTheDocument()
  })

  it('holds every toast while the focus is in one of them, and moving between them restarts nothing', () => {
    render(<ToastProvider><Trigger message="Ativo guardado" /><Trigger message="Pergunta deixada por responder" tone="info" /></ToastProvider>)
    for (const show of screen.getAllByText('show')) fireEvent.click(show)
    const [first, second] = screen.getAllByRole('button', { name: 'Fechar aviso' })
    act(() => { first.focus() })
    act(() => { vi.advanceTimersByTime(30_000) })
    act(() => { second.focus() })
    act(() => { vi.advanceTimersByTime(30_000) })

    expect(screen.getByText('Ativo guardado')).toBeInTheDocument()
    expect(screen.getByText('Pergunta deixada por responder')).toBeInTheDocument()
  })

  it('gives the focus back when a toast is closed with the keyboard, and the others go on', () => {
    render(<ToastProvider><Trigger message="Ativo guardado" /></ToastProvider>)
    const trigger = screen.getByText('show')
    fireEvent.click(trigger)
    fireEvent.click(trigger)
    trigger.focus()
    const [first] = screen.getAllByRole('button', { name: 'Fechar aviso' })
    act(() => { first.focus() })

    fireEvent.click(first)

    expect(screen.getAllByText('Ativo guardado')).toHaveLength(1)
    expect(trigger).toHaveFocus()
    act(() => { vi.advanceTimersByTime(5000) })
    expect(screen.queryByText('Ativo guardado')).not.toBeInTheDocument()
  })

  it('is not held for ever when the focused toast goes away without a blur', () => {
    render(<ToastProvider><Trigger message="Ativo guardado" /></ToastProvider>)
    const trigger = screen.getByText('show')
    fireEvent.click(trigger)
    fireEvent.click(trigger)
    const [first] = screen.getAllByRole('button', { name: 'Fechar aviso' })
    act(() => { first.focus() })
    // No previous focus to give back to: the focus falls to the page with the toast.
    act(() => { fireEvent.click(first) })

    act(() => { vi.advanceTimersByTime(5000) })
    expect(screen.queryByText('Ativo guardado')).not.toBeInTheDocument()
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

  it('keeps at most two success or info toasts, dropping the oldest', () => {
    const items = [success(1), success(2)]
    expect(addToast(items, success(3)).map((t) => t.id)).toEqual([2, 3])
    expect(addToast(items, { id: 3, message: 'i3', tone: 'info' }).map((t) => t.id)).toEqual([2, 3])
  })

  it('never drops an error to make room', () => {
    const items = [error(1), success(2), success(3)]
    expect(addToast(items, success(4)).map((t) => t.id)).toEqual([1, 3, 4])
    expect(addToast([error(1), error(2), error(3)], error(4)).map((t) => t.id)).toEqual([1, 2, 3, 4])
  })

  it('does not stack the same error twice', () => {
    const items = [error(1)]
    expect(addToast(items, { id: 2, message: 'e1', tone: 'error' })).toBe(items)
  })
})
