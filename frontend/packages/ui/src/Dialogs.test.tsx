import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { Drawer, Modal } from './Dialogs'

function Opener({ kind }: { kind: 'modal' | 'drawer' }) {
  const [open, setOpen] = useState(false)
  const Dialog = kind === 'modal' ? Modal : Drawer
  return (
    <main>
      <h1>Ativos</h1>
      <button onClick={() => setOpen(true)}>Novo ativo</button>
      <Dialog open={open} onOpenChange={setOpen} title="Novo ativo" description="Preencha os dados" footer={<button onClick={() => setOpen(false)}>Cancelar</button>}>
        <input aria-label="Nome" />
      </Dialog>
    </main>
  )
}

describe.each(['modal', 'drawer'] as const)('%s', (kind) => {
  it('is a dialog named by its title and described', () => {
    render(<Opener kind={kind} />)
    fireEvent.click(screen.getByRole('button', { name: 'Novo ativo' }))
    const dialog = screen.getByRole('dialog', { name: 'Novo ativo' })
    expect(dialog).toHaveAccessibleDescription('Preencha os dados')
  })

  it('closes with Escape and gives focus back to the button that opened it', async () => {
    render(<Opener kind={kind} />)
    const opener = screen.getByRole('button', { name: 'Novo ativo' })
    opener.focus()
    fireEvent.click(opener)
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await waitFor(() => expect(opener).toHaveFocus())
  })

  it('has a close button in Portuguese', () => {
    render(<Opener kind={kind} />)
    fireEvent.click(screen.getByRole('button', { name: 'Novo ativo' }))
    fireEvent.click(screen.getByRole('button', { name: /^Fechar/ }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

it('a modal without description has no description, so the title is not read twice', () => {
  render(<Modal open onOpenChange={() => {}} title="Apagar ativo?" />)
  const dialog = screen.getByRole('dialog', { name: 'Apagar ativo?' })
  expect(dialog).not.toHaveAttribute('aria-describedby')
  expect(dialog).toHaveAccessibleDescription('')
})

it('a modal can send the focus somewhere else when it closes', async () => {
  function Question() {
    const [open, setOpen] = useState(false)
    return (
      <main>
        <input aria-label="Sim" />
        <button onClick={() => setOpen(true)}>Seguinte</button>
        <Modal open={open} onOpenChange={setOpen} title="Avançar sem guardar?" focusOnClose={() => screen.getByLabelText('Sim')} />
      </main>
    )
  }
  render(<Question />)
  const opener = screen.getByRole('button', { name: 'Seguinte' })
  opener.focus()
  fireEvent.click(opener)
  fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
  await waitFor(() => expect(screen.getByLabelText('Sim')).toHaveFocus())
})
