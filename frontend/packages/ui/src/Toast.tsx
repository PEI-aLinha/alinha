import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { cn } from './cn'

interface ToastItem {
  id: number
  message: string
  tone: 'success' | 'error'
}

interface ToastApi {
  show: (message: string, tone?: ToastItem['tone']) => void
}

const ToastContext = createContext<ToastApi | null>(null)
/** Success toasts go away on their own; errors stay until closed, so nobody misses them (WCAG 2.2.1). */
const DURATION_MS = 4200
/** At most this many success toasts at once. Errors are never dropped to make room. */
const MAX_SUCCESS = 3

/**
 * Adds a toast. When there are too many success toasts, the oldest success goes; errors always stay. An error that
 * is already shown is not added again, so a request failing in a loop does not stack the same message.
 */
export function addToast(items: ToastItem[], item: ToastItem): ToastItem[] {
  if (item.tone === 'error' && items.some((t) => t.tone === 'error' && t.message === item.message)) return items
  const next = [...items, item]
  const successes = next.filter((t) => t.tone === 'success')
  if (successes.length <= MAX_SUCCESS) return next
  const drop = new Set(successes.slice(0, successes.length - MAX_SUCCESS).map((t) => t.id))
  return next.filter((t) => !drop.has(t.id))
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const seq = useRef(0)
  const timers = useRef(new Map<number, number>())

  const dismiss = useCallback((id: number) => {
    const timer = timers.current.get(id)
    if (timer !== undefined) window.clearTimeout(timer)
    timers.current.delete(id)
    setItems((prev) => prev.filter((t) => t.id !== id))
  }, [])

  const show = useCallback((message: string, tone: ToastItem['tone'] = 'success') => {
    seq.current += 1
    const id = seq.current
    setItems((prev) => addToast(prev, { id, message, tone }))
    if (tone === 'success') timers.current.set(id, window.setTimeout(() => dismiss(id), DURATION_MS))
  }, [dismiss])

  // A success toast dropped to make room no longer needs its timer.
  useEffect(() => {
    const shown = new Set(items.map((t) => t.id))
    timers.current.forEach((timer, id) => {
      if (!shown.has(id)) {
        window.clearTimeout(timer)
        timers.current.delete(id)
      }
    })
  }, [items])

  // No timer may fire after the provider is gone (e.g. between tests, or when the shell unmounts).
  useEffect(() => {
    const pending = timers.current
    return () => {
      pending.forEach((timer) => window.clearTimeout(timer))
      pending.clear()
    }
  }, [])

  const value = useMemo(() => ({ show }), [show])
  const errors = items.filter((t) => t.tone === 'error')
  const successes = items.filter((t) => t.tone === 'success')
  return (
    <ToastContext.Provider value={value}>
      {children}
      {/* Bottom-right corner, clear of the navigation. Both live regions are always mounted, start empty and are
          never hidden (display:none would take them out of the accessibility tree): screen readers often miss a
          region that appears already filled. Errors are alerts (assertive),
          successes are status messages (polite) — WCAG 4.1.3. */}
      <div className="pointer-events-none fixed bottom-6 right-6 z-[90] flex flex-col gap-2 max-md:bottom-auto max-md:left-5 max-md:right-5 max-md:top-[72px]">
        <div role="alert" className="flex flex-col gap-2">
          {errors.map((t) => <Toast key={t.id} item={t} onDismiss={dismiss} />)}
        </div>
        <div role="status" className="flex flex-col gap-2">
          {successes.map((t) => <Toast key={t.id} item={t} onDismiss={dismiss} />)}
        </div>
      </div>
    </ToastContext.Provider>
  )
}

function Toast({ item, onDismiss }: { item: ToastItem; onDismiss: (id: number) => void }) {
  const error = item.tone === 'error'
  return (
    <div className={cn('pointer-events-auto flex items-center gap-2.5 rounded-[12px] px-4 py-3 text-[0.875rem] font-bold text-white shadow-[0_12px_32px_rgb(var(--shadow-rgb)/0.25)] animate-pop-in', error ? 'bg-risk-high-ink pr-2' : 'bg-brand-deep')}>
      <span aria-hidden className="font-extrabold text-signal-soft">{error ? '!' : '✓'}</span>
      <span className="min-w-0 flex-1">{item.message}</span>
      {error && (
        <button type="button" onClick={() => onDismiss(item.id)} aria-label="Fechar aviso" className="-my-1 grid size-8 shrink-0 place-items-center rounded-full text-white/90 hover:bg-white/15 hover:text-white focus-visible:outline-white">
          <X className="size-4" aria-hidden />
        </button>
      )}
    </div>
  )
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx
}
