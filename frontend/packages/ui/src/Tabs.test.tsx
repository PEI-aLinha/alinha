import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Tabs } from './Tabs'

const tabs = [
  { value: 'assets', label: 'Ativos', count: 3, content: <p>Lista de ativos</p> },
  { value: 'risks', label: 'Riscos', content: <p>Lista de riscos</p> },
]

describe('Tabs', () => {
  it('shows the first tab and marks it selected', () => {
    render(<Tabs tabs={tabs} />)
    expect(screen.getByRole('tab', { name: /Ativos/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel')).toHaveTextContent('Lista de ativos')
  })

  it('arrow keys move between tabs (WAI-ARIA tabs pattern)', async () => {
    render(<Tabs tabs={tabs} />)
    const first = screen.getByRole('tab', { name: /Ativos/ })
    first.focus()
    fireEvent.keyDown(first, { key: 'ArrowRight' })
    await waitFor(() => expect(screen.getByRole('tab', { name: 'Riscos' })).toHaveFocus())
  })

  it('opens on the default tab', () => {
    render(<Tabs tabs={tabs} defaultValue="risks" />)
    expect(screen.getByRole('tabpanel')).toHaveTextContent('Lista de riscos')
  })
})
