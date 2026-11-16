import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { PageHeader } from './PageHeader'

describe('PageHeader', () => {
  it('renders the title as the page heading', () => {
    render(<PageHeader title="Ativos" />)

    expect(screen.getByRole('heading', { level: 1, name: 'Ativos' })).toBeInTheDocument()
  })

  it('renders actions next to the title', () => {
    render(
      <PageHeader title="Ativos">
        <button type="button">Adicionar ativo</button>
      </PageHeader>,
    )

    expect(screen.getByRole('button', { name: 'Adicionar ativo' })).toBeInTheDocument()
  })
})
