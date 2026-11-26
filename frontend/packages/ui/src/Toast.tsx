import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { cn } from './cn'

interface ToastItem {
  id: number
  message: string
  /** success: something was done; info: something to know (a skip, a redirect); error: needs attention. */
  tone: 'success' | 'info' | 'error'
}

interface ToastApi {
  show: (message: string, tone?: ToastItem['tone']) => void
}

const ToastContext = createContext<ToastApi | null>(null)
/**
 * Success and info toasts go away on their own, and wait while the pointer or the focus is on them; errors stay
 * until closed, so nobody misses them (WCAG 2.2.1).
 */
const DURATION_MS = 4200
/** At most this many success and info toasts at once. Errors are never dropped to make room. */
const MAX_QUIET = 2

const isQuiet = (t: ToastItem) => t.tone !== 'error'

/**
 * Adds a toast. When there are too many success and info toasts, the oldest goes; errors always stay. An error that
 * is already shown is not added again, so a request failing in a loop does not stack the same message.
 */
export function addToast(items: ToastItem[], item: ToastItem): ToastItem[] {
  if (item.tone === 'error' && items.some((t) => t.tone === 'error' && t.message === item.message)) return items
  const next = [...items, item]
  const quiet = next.filter(isQuiet)
  if (quiet.length <= MAX_QUIET) return next
  const drop = new Set(quiet.slice(0, quiet.length - MAX_QUIET).map((t) => t.id))
  return next.filter((t) => !drop.has(t.id))
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const seq = useRef(0)

  const dismiss = useCallback((id: number) => setItems((prev) => prev.filter((t) => t.id !== id)), [])

  const show = useCallback((message: string, tone: ToastItem['tone'] = 'success') => {
    seq.current += 1
    const id = seq.current
    setItems((prev) => addToast(prev, { id, message, tone }))
  }, [])

  const value = useMemo(() => ({ show }), [show])
  const errors = items.filter((t) => !isQuiet(t))
  const quiet = items.filter(isQuiet)
  return (
    <ToastContext.Provider value={value}>
      {children}
      {/* Top centre: clear of form buttons (bottom), header actions and drawers (right). Both live regions are always
          mounted, start empty and are never hidden (display:none would take them out of the accessibility tree):
          screen readers often miss a region that appears already filled. Errors are alerts (assertive), the rest
          are status messages (polite) — WCAG 4.1.3. */}
      <div className="pointer-events-none fixed left-1/2 top-5 z-[90] flex w-[min(400px,calc(100vw-40px))] -translate-x-1/2 flex-col gap-2 max-md:top-[72px]">
        <div role="alert" className="flex flex-col gap-2">
          {errors.map((t) => <Toast key={t.id} item={t} onDismiss={dismiss} />)}
        </div>
        <div role="status" className="flex flex-col gap-2">
          {quiet.map((t) => <Toast key={t.id} item={t} onDismiss={dismiss} />)}
        </div>
      </div>
    </ToastContext.Provider>
  )
}

const ICON: Record<ToastItem['tone'], string> = { success: '✓', info: 'i', error: '!' }

function Toast({ item, onDismiss }: { item: ToastItem; onDismiss: (id: number) => void }) {
  const error = item.tone === 'error'
  const [paused, setPaused] = useState(false)
  const remaining = useRef(DURATION_MS)
  // The timer stops while the toast is pointed at or focused, and resumes with the time that was left.
  useEffect(() => {
    if (error || paused) return undefined
    const started = Date.now()
    const timer = window.setTimeout(() => onDismiss(item.id), remaining.current)
    return () => {
      window.clearTimeout(timer)
      remaining.current -= Date.now() - started
    }
  }, [error, paused, item.id, onDismiss])
  return (
    <div
      onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)} onBlur={() => setPaused(false)}
      className={cn('pointer-events-auto flex items-center gap-2.5 rounded-[12px] px-4 py-3 text-[0.875rem] font-bold text-white shadow-[0_12px_32px_rgb(var(--shadow-rgb)/0.25)] animate-pop-in', error ? 'bg-risk-high-ink pr-2' : 'bg-brand-deep')}
    >
      <span aria-hidden className={cn('font-extrabold text-signal-soft', item.tone === 'info' && 'grid size-[18px] shrink-0 place-items-center rounded-full border border-signal-soft/70 font-serif text-[0.75rem] italic')}>{ICON[item.tone]}</span>
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
