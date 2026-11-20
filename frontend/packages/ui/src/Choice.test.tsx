import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Checkbox, RadioGroup } from './Choice'

describe('Checkbox', () => {
  it('is a native checkbox named by its label and described by its hint', () => {
    const onChange = vi.fn()
    render(<Checkbox label="Tem teletrabalho" hint="Pelo menos um dia por semana" onChange={onChange} />)
    const box = screen.getByRole('checkbox', { name: 'Tem teletrabalho' })
    expect(box).toHaveAccessibleDescription('Pelo menos um dia por semana')
    fireEvent.click(screen.getByText('Tem teletrabalho'))
    expect(onChange).toHaveBeenCalledOnce()
  })
})

describe('RadioGroup', () => {
  const options = [
    { value: 'micro', label: 'Micro', hint: 'Menos de 10 pessoas' },
    { value: 'small', label: 'Pequena' },
  ]

  it('is a group named by its legend, with one radio per option', () => {
    render(<RadioGroup name="size" legend="Dimensão" value="micro" onChange={() => {}} options={options} />)
    expect(screen.getByRole('group', { name: 'Dimensão' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Micro/ })).toBeChecked()
    expect(screen.getByRole('radio', { name: 'Pequena' })).not.toBeChecked()
  })

  it('reports the chosen value', () => {
    const onChange = vi.fn()
    render(<RadioGroup name="size" legend="Dimensão" value={null} onChange={onChange} options={options} columns={2} />)
    fireEvent.click(screen.getByRole('radio', { name: 'Pequena' }))
    expect(onChange).toHaveBeenCalledWith('small')
  })

  it('announces the error and links it to the group', () => {
    render(<RadioGroup name="size" legend="Dimensão" value={null} onChange={() => {}} options={options} error="Escolha uma opção" />)
    expect(screen.getByRole('group', { name: 'Dimensão' })).toHaveAccessibleDescription('Escolha uma opção')
    expect(screen.getByRole('alert')).toHaveTextContent('Escolha uma opção')
  })
})
