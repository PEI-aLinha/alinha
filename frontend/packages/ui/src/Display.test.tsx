import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AIBadge, Badge, CriticalityBadge, RiskBadge, levelClass } from './Badge'
import { Card, Note, StatCard } from './Card'
import { PageHeader } from './PageHeader'
import { EmptyState, ErrorState, SkeletonRows } from './States'

describe('badges', () => {
  it('say the level in words, not only in colour', () => {
    render(<><RiskBadge level="high" prefix="Risco " /><CriticalityBadge level="medium" /><CriticalityBadge level="high" /><CriticalityBadge level="low" /><Badge tone="outline">Rascunho</Badge></>)
    expect(screen.getByText(/Risco\s+Alto/)).toBeInTheDocument()
    expect(screen.getByText('Média')).toBeInTheDocument()
    expect(screen.getByText('Alta')).toHaveClass('bg-brand-deep')
    expect(screen.getByText('Baixa')).toHaveClass('bg-paper-deep')
    expect(screen.getByText('Rascunho')).toBeInTheDocument()
    expect(levelClass('low')).toBe('bg-risk-low text-risk-low-ink')
  })

  it('label AI content as a proposal or as information', () => {
    render(<><AIBadge /><AIBadge kind="info" onDark /></>)
    expect(screen.getByText('Proposta pela IA · por validar')).toBeInTheDocument()
    expect(screen.getByText('Gerado por IA · apenas informativo')).toBeInTheDocument()
  })
})

describe('cards', () => {
  it('a stat card with onClick has one button named by its label and value', () => {
    const onClick = vi.fn()
    render(<StatCard label="Ativos" value={12} caption="3 críticos" onClick={onClick}>pré-visualização</StatCard>)
    fireEvent.click(screen.getByRole('button', { name: 'Ativos 12' }))
    expect(onClick).toHaveBeenCalledOnce()
  })

  it('a clickable stat card keeps its heading and puts no block content inside the button', () => {
    render(<StatCard label="Riscos" value={4} caption="2 altos" labelAs="h3" onClick={() => {}} />)
    expect(screen.getByRole('heading', { level: 3, name: 'Riscos' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Riscos 4' })).toBeEmptyDOMElement()
  })

  it('a stat card without onClick is not interactive', () => {
    render(<><StatCard label="Riscos" value={4} labelAs="h3" /><Card soft>x</Card><Note>Guardado automaticamente</Note></>)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 3, name: 'Riscos' })).toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent('Guardado automaticamente')
  })
})

describe('PageHeader', () => {
  it('is the page h1, with optional eyebrow, subtitle and actions', () => {
    render(<PageHeader eyebrow="Organização" title="Ativos" subtitle="O que a empresa usa" actions={<button>Novo ativo</button>} help={<button>Ajuda</button>} />)
    expect(screen.getByRole('heading', { level: 1, name: 'Ativos' })).toBeInTheDocument()
    expect(screen.getByText('O que a empresa usa')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Novo ativo' })).toBeInTheDocument()
  })

  it('works with just a title', () => {
    render(<PageHeader title="Painel" />)
    expect(screen.getByRole('heading', { level: 1, name: 'Painel' })).toBeInTheDocument()
  })
})

describe('states', () => {
  it('the empty state takes the heading level it is given', () => {
    render(<EmptyState title="Ainda não há ativos" headingLevel={2} action={<button>Adicionar</button>}>Comece por adicionar um.</EmptyState>)
    expect(screen.getByRole('heading', { level: 2, name: 'Ainda não há ativos' })).toBeInTheDocument()
  })

  it('the error state explains in plain words and offers a retry', () => {
    const onRetry = vi.fn()
    render(<ErrorState error={new Error('HTTP 503')} onRetry={onRetry} />)
    expect(screen.getByRole('alert')).toHaveTextContent('Pode ser uma falha de ligação')
    expect(screen.getByText('HTTP 503')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Tentar de novo' }))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('loading rows are announced once, as a status', () => {
    render(<SkeletonRows rows={2} />)
    expect(screen.getByRole('status', { name: 'A carregar…' })).toBeInTheDocument()
  })
})
