import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Field, Select, TextArea, TextInput } from './Field'

describe('Field', () => {
  it('labels the control and marks it required unless optional', () => {
    render(<Field label="Nome">{(p) => <TextInput id={p.id} aria-required={p.required} />}</Field>)
    expect(screen.getByRole('textbox', { name: 'Nome' })).toHaveAttribute('aria-required', 'true')
  })

  it('says "(opcional)" in the label of optional fields', () => {
    render(<Field label="Notas" optional>{(p) => <TextArea id={p.id} aria-required={p.required} />}</Field>)
    const box = screen.getByRole('textbox', { name: /^Notas\s*\(opcional\)$/ })
    expect(box).toHaveAttribute('aria-required', 'false')
    expect(box).toHaveAttribute('rows', '3')
  })

  it('links the hint to the control', () => {
    render(<Field label="NIF" hint="9 dígitos">{(p) => <TextInput id={p.id} aria-describedby={p.describedBy} />}</Field>)
    expect(screen.getByRole('textbox', { name: 'NIF' })).toHaveAccessibleDescription('9 dígitos')
  })

  it('announces the error, links it and hides the hint', () => {
    render(
      <Field label="NIF" hint="9 dígitos" error="O NIF tem de ter 9 dígitos">
        {(p) => <TextInput id={p.id} aria-describedby={p.describedBy} invalid={p.invalid} />}
      </Field>,
    )
    const box = screen.getByRole('textbox', { name: 'NIF' })
    expect(box).toHaveAttribute('aria-invalid', 'true')
    expect(box).toHaveAccessibleDescription('O NIF tem de ter 9 dígitos')
    expect(screen.getByRole('alert')).toHaveTextContent('O NIF tem de ter 9 dígitos')
    expect(screen.queryByText('9 dígitos')).not.toBeInTheDocument()
  })
})

describe('Select', () => {
  it('is a labelled combobox showing the placeholder until a value is chosen', () => {
    render(<Select ariaLabel="Setor" value="" onChange={() => {}} options={[{ value: 'ind', label: 'Indústria' }]} />)
    expect(screen.getByRole('combobox', { name: 'Setor' })).toHaveTextContent('Escolher…')
  })

  it('stays controlled when cleared back to no choice', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    const options = [{ value: 'ind', label: 'Indústria' }]
    const { rerender } = render(<Select ariaLabel="Setor" value="ind" onChange={() => {}} options={options} />)
    rerender(<Select ariaLabel="Setor" value="" onChange={() => {}} options={options} />)
    expect(screen.getByRole('combobox', { name: 'Setor' })).toHaveTextContent('Escolher…')
    expect([...warn.mock.calls, ...error.mock.calls].join(' ')).not.toMatch(/uncontrolled|controlled/i)
    warn.mockRestore()
    error.mockRestore()
  })

  it('sends its value with a native form when it has a name', () => {
    const { container } = render(<form><Select ariaLabel="Setor" name="sector" value="ind" onChange={() => {}} options={[{ value: 'ind', label: 'Indústria' }]} /></form>)
    expect(new FormData(container.querySelector('form')!).get('sector')).toBe('ind')
  })

  it('shows the chosen option and passes on invalid and required', () => {
    render(<Select ariaLabel="Setor" value="ind" invalid required onChange={() => {}} options={[{ value: 'ind', label: 'Indústria' }]} />)
    const box = screen.getByRole('combobox', { name: 'Setor' })
    expect(box).toHaveTextContent('Indústria')
    expect(box).toHaveAttribute('aria-invalid', 'true')
    expect(box).toHaveAttribute('aria-required', 'true')
  })
})
