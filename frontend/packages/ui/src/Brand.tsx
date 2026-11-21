// Plugins compile these sources with their own tsconfig, which does not include assets.d.ts: point at it here.
// eslint-disable-next-line @typescript-eslint/triple-slash-reference
/// <reference path="./assets.d.ts" />
import logoUrl from './assets/alinha-logo.svg'
import { cn } from './cn'

/** Width / height of the official logo artwork (assets/alinha-logo.svg). */
const LOGO_RATIO = 985 / 404

/**
 * Official aLinha logo. Drawn as a mask filled with the brand token, so it follows the theme (the artwork itself is
 * a single dark-teal shape that would vanish on dark surfaces). Height comes from `className` (default 36 px).
 */
export function Logo({ className }: { className?: string }) {
  return (
    <span
      role="img"
      aria-label="aLinha"
      style={{
        aspectRatio: LOGO_RATIO,
        maskImage: `url("${logoUrl}")`,
        WebkitMaskImage: `url("${logoUrl}")`,
        maskSize: 'contain',
        WebkitMaskSize: 'contain',
        maskRepeat: 'no-repeat',
        WebkitMaskRepeat: 'no-repeat',
      }}
      className={cn('inline-block h-9 w-auto bg-brand', className)}
    />
  )
}

export type AvatarSize = 24 | 28 | 36 | 40 | 44

interface AvatarProps {
  initials: string
  size?: AvatarSize
  tone?: 'soft' | 'dark'
  /** Accessible name. Without it the avatar is decorative (the name is usually written next to it). */
  label?: string
}

/** Round initials badge for a person. */
export function Avatar({ initials, size = 36, tone = 'soft', label }: AvatarProps) {
  return (
    <span
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      style={{ width: size, height: size }}
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-full font-extrabold leading-none',
        size >= 40 ? 'text-[0.9375rem]' : size <= 28 ? 'text-[0.6875rem]' : 'text-[0.75rem]',
        tone === 'dark' ? 'bg-brand-deep text-white' : 'bg-brand-soft text-brand',
      )}
    >
      {initials}
    </span>
  )
}

/** Up to two initials from a full name: first and last word ("Rui Santos" → "RS", "Marta" → "M"). */
export function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return '?'
  const first = words[0][0]
  const last = words.length > 1 ? words[words.length - 1][0] : ''
  return (first + last).toLocaleUpperCase('pt')
}
