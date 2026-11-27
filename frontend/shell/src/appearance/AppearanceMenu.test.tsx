import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceMenu } from './AppearanceMenu'
import { AppearanceProvider } from './AppearanceProvider'

const renderMenu = (props: Parameters<typeof AppearanceMenu>[0]) =>
  render(<AppearanceProvider><AppearanceMenu {...props} /></AppearanceProvider>)

describe('AppearanceMenu', () => {
  beforeEach(() => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addEventListener: () => {}, removeEventListener: () => {} })))
  })
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('opens the theme and colour-vision choices from the palette button', () => {
    renderMenu({})
    fireEvent.click(screen.getByRole('button', { name: 'Aparência' }))
    expect(screen.getByRole('radio', { name: /Protanopia/ })).toBeInTheDocument()
    expect(screen.queryByText('Ana Silva')).not.toBeInTheDocument()
  })

  it('with an account, the avatar opens who is signed in above the same choices', () => {
    renderMenu({ account: { initials: 'AS', name: 'Ana Silva', subtitle: 'Padaria Lda' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ana Silva: conta e aparência' }))
    expect(screen.getByText('Ana Silva')).toBeInTheDocument()
    expect(screen.getByText('Padaria Lda')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Protanopia/ })).toBeInTheDocument()
  })
})
