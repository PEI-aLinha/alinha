import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Segmented } from './Segmented'

const options = [
  { value: 'light', label: 'Claro' },
  { value: 'dark', label: 'Escuro' },
] as const

describe('Segmented', () => {
  it('marks the current value and reports a new choice', () => {
    const onChange = vi.fn()
    render(<Segmented ariaLabel="Tema" value="light" onChange={onChange} options={[...options]} />)
    expect(screen.getByRole('radio', { name: 'Claro' })).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(screen.getByRole('radio', { name: 'Escuro' }))
    expect(onChange).toHaveBeenCalledWith('dark')
  })

  it('keeps the value when the active segment is pressed again', () => {
    const onChange = vi.fn()
    render(<Segmented ariaLabel="Tema" value="light" onChange={onChange} options={[...options]} />)
    fireEvent.click(screen.getByRole('radio', { name: 'Claro' }))
    expect(onChange).not.toHaveBeenCalled()
  })
})
