import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Avatar, Logo, initialsOf } from './Brand'

describe('Logo', () => {
  it('is an image named aLinha, filled with the brand token so it follows the theme', () => {
    render(<Logo />)
    const logo = screen.getByRole('img', { name: 'aLinha' })
    expect(logo.className).toMatch(/bg-brand/)
  })
})

describe('Avatar', () => {
  it('is decorative without a label (the name is written next to it)', () => {
    const { container } = render(<Avatar initials="RS" />)
    expect(container.firstElementChild).toHaveAttribute('aria-hidden', 'true')
    expect(screen.queryByRole('img')).toBeNull()
  })

  it('is an image named by its label when given one', () => {
    render(<Avatar initials="RS" label="Rui Santos" size={28} />)
    expect(screen.getByRole('img', { name: 'Rui Santos' })).toHaveTextContent('RS')
  })
})

describe('initialsOf', () => {
  it.each([
    ['Rui Santos', 'RS'],
    ['Marta Isabel Ferreira', 'MF'],
    ['  marta  ', 'M'],
    ['álvaro órfão', 'ÁÓ'],
    ['', '?'],
  ])('%j → %s', (name, initials) => {
    expect(initialsOf(name)).toBe(initials)
  })
})
