import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { DataTable, type ColumnDef } from './DataTable'

interface Asset { id: string; name: string; owner: string }
const data: Asset[] = [
  { id: 'a', name: 'Servidor', owner: 'Rui' },
  { id: 'b', name: 'Portátil', owner: 'Ana' },
]
const columns: ColumnDef<Asset, unknown>[] = [
  { accessorKey: 'name', header: 'Nome' },
  { accessorKey: 'owner', header: 'Responsável', enableSorting: false, meta: { align: 'right', width: 120 } },
]

describe('DataTable', () => {
  it('is a table with a caption for screen readers', () => {
    render(<DataTable data={data} columns={columns} getRowId={(r) => r.id} caption="Ativos" />)
    expect(screen.getByRole('table', { name: 'Ativos' })).toBeInTheDocument()
    expect(screen.getAllByRole('row')).toHaveLength(3)
  })

  it('sorts by a column and says so with aria-sort', () => {
    render(<DataTable data={data} columns={columns} getRowId={(r) => r.id} caption="Ativos" />)
    fireEvent.click(screen.getByRole('button', { name: 'Nome' }))
    expect(screen.getByRole('columnheader', { name: 'Nome' })).toHaveAttribute('aria-sort', 'ascending')
    expect(screen.getAllByRole('row')[1]).toHaveTextContent('Portátil')
    fireEvent.click(screen.getByRole('button', { name: 'Nome' }))
    expect(screen.getByRole('columnheader', { name: 'Nome' })).toHaveAttribute('aria-sort', 'descending')
  })

  it('opens a row through one real button per row', () => {
    const onRowClick = vi.fn()
    render(<DataTable data={data} columns={columns} getRowId={(r) => r.id} caption="Ativos" onRowClick={onRowClick} rowLabel={(r) => `Abrir ${r.name}`} />)
    fireEvent.click(screen.getByRole('button', { name: 'Abrir Servidor' }))
    expect(onRowClick).toHaveBeenCalledWith(data[0])
  })

  it('selects rows one by one or all at once', () => {
    const onSelectedChange = vi.fn()
    const { rerender } = render(<DataTable data={data} columns={columns} getRowId={(r) => r.id} caption="Ativos" selected={[]} onSelectedChange={onSelectedChange} rowLabel={(r) => `Abrir ${r.name}`} />)
    fireEvent.click(screen.getByRole('checkbox', { name: 'Selecionar Servidor' }))
    expect(onSelectedChange).toHaveBeenLastCalledWith(['a'])
    fireEvent.click(screen.getByRole('checkbox', { name: 'Selecionar todos' }))
    expect(onSelectedChange).toHaveBeenLastCalledWith(['a', 'b'])
    rerender(<DataTable data={data} columns={columns} getRowId={(r) => r.id} caption="Ativos" selected={['a', 'b']} onSelectedChange={onSelectedChange} />)
    fireEvent.click(screen.getByRole('checkbox', { name: 'Selecionar a' }))
    expect(onSelectedChange).toHaveBeenLastCalledWith(['b'])
    fireEvent.click(screen.getByRole('checkbox', { name: 'Selecionar todos' }))
    expect(onSelectedChange).toHaveBeenLastCalledWith([])
  })

  it('shows cards for small screens, the empty state and the footer', () => {
    const onRowClick = vi.fn()
    const { rerender } = render(<DataTable data={data} columns={columns} getRowId={(r) => r.id} caption="Ativos" renderCard={(r) => r.name} cardsBelow="xl" onRowClick={onRowClick} footer={<p>2 ativos</p>} />)
    fireEvent.click(screen.getByRole('button', { name: 'Portátil' }))
    expect(onRowClick).toHaveBeenCalledWith(data[1])
    expect(screen.getByText('2 ativos')).toBeInTheDocument()
    rerender(<DataTable data={[]} columns={columns} getRowId={(r) => r.id} caption="Ativos" renderCard={(r) => r.name} empty={<p>Sem ativos</p>} />)
    expect(screen.getByText('Sem ativos')).toBeInTheDocument()
  })
})
