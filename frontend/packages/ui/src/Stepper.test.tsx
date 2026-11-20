import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Stepper } from './Stepper'

const steps = [{ label: 'Empresa' }, { label: 'Ativos' }, { label: 'Revisão' }]

describe('Stepper', () => {
  it('is a named list that marks the current step and says each status in words', () => {
    render(<Stepper steps={steps} current={1} label="Passos do registo" />)
    expect(screen.getByRole('navigation', { name: 'Passos do registo' })).toBeInTheDocument()
    const items = screen.getAllByRole('listitem')
    expect(items[1]).toHaveAttribute('aria-current', 'step')
    expect(items[0]).toHaveTextContent('Empresa (concluído)')
    expect(items[1]).toHaveTextContent('Ativos (passo atual)')
    expect(items[2]).toHaveTextContent('Revisão (por fazer)')
  })

  it('lets you go back to a done step, never forward', () => {
    const onStepClick = vi.fn()
    render(<Stepper steps={steps} current={1} label="Passos" onStepClick={onStepClick} />)
    fireEvent.click(screen.getByRole('button', { name: /Empresa/ }))
    expect(onStepClick).toHaveBeenCalledWith(0)
    expect(screen.getAllByRole('button')).toHaveLength(1)
  })
})
