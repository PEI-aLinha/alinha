import { useState, type ReactNode } from 'react'
import {
  flexRender, getCoreRowModel, getSortedRowModel, useReactTable, type ColumnDef, type SortingState,
} from '@tanstack/react-table'
import { ArrowDown, ArrowUp, ChevronsUpDown } from 'lucide-react'
import { cn } from './cn'

declare module '@tanstack/react-table' {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  interface ColumnMeta<TData, TValue> {
    width?: number
    align?: 'right'
  }
}

interface DataTableProps<T> {
  data: T[]
  // `any`, not `unknown`: columns from createColumnHelper have different value types and do not fit
  // ColumnDef<T, unknown> (TanStack's documented workaround).
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  columns: ColumnDef<T, any>[]
  getRowId: (row: T) => string
  onRowClick?: (row: T) => void
  rowLabel?: (row: T) => string
  /** Card version for mobile. */
  renderCard?: (row: T) => ReactNode
  /** Width below which the cards replace the table: phones only (default), or up to small desktops for wide tables. */
  cardsBelow?: 'md' | 'xl'
  footer?: ReactNode
  empty?: ReactNode
  initialSorting?: SortingState
  caption: string
  highlightId?: string | null
  /**
   * Row selection (bulk actions). "Selecionar todos" only adds or removes the rows shown now. When `data` changes
   * (filter, page), remove from `selected` the ids that are no longer shown, or a bulk action would reach rows the
   * person cannot see.
   */
  selected?: string[]
  onSelectedChange?: (ids: string[]) => void
  /** Name of a row in its checkbox label ("Selecionar <name>"). Defaults to the row id. */
  selectLabel?: (row: T) => string
}

/** Table: radius 16 card, paper-soft header with 11 eyebrows, 14/20 rows with line-soft separator. */
const boxCls = 'size-4 cursor-pointer rounded accent-[var(--color-brand)]'

export function DataTable<T>({ data, columns, getRowId, onRowClick, rowLabel, renderCard, cardsBelow = 'md', footer, empty, initialSorting = [], caption, highlightId, selected, onSelectedChange, selectLabel }: DataTableProps<T>) {
  const [sorting, setSorting] = useState<SortingState>(initialSorting)
  const table = useReactTable({ data, columns, state: { sorting }, onSortingChange: setSorting, getRowId, getCoreRowModel: getCoreRowModel(), getSortedRowModel: getSortedRowModel() })
  const rows = table.getRowModel().rows
  const selectable = !!onSelectedChange
  const sel = selected ?? []
  const shownIds = rows.map((r) => r.id)
  const shownOn = shownIds.filter((id) => sel.includes(id)).length
  const allOn = shownIds.length > 0 && shownOn === shownIds.length
  const someOn = shownOn > 0 && !allOn
  const toggle = (id: string) => onSelectedChange?.(sel.includes(id) ? sel.filter((x) => x !== id) : [...sel, id])
  // Only the rows shown now: selections from another filter or page are left as they are.
  const toggleAll = () => onSelectedChange?.(allOn ? sel.filter((id) => !shownIds.includes(id)) : [...sel, ...shownIds.filter((id) => !sel.includes(id))])
  const rowCheckbox = (row: { id: string; original: T }) => (
    <input type="checkbox" className={boxCls} aria-label={`Selecionar ${selectLabel?.(row.original) ?? row.id}`} checked={sel.includes(row.id)} onChange={() => toggle(row.id)} />
  )

  return (
    <div className="overflow-hidden rounded-[16px] border border-line-soft bg-paper">
      <table className={cn('w-full table-fixed border-collapse', renderCard && (cardsBelow === 'xl' ? 'max-xl:hidden' : 'max-md:hidden'))}>
        <caption className="sr-only">{caption}</caption>
        <thead className="bg-paper-soft">
          {table.getHeaderGroups().map((hg) => (
            <tr key={hg.id} className="border-b border-line-soft">
              {selectable && (
                <th scope="col" className="w-11 pl-5">
                  <input
                    type="checkbox"
                    className={boxCls}
                    aria-label="Selecionar todos"
                    checked={allOn}
                    ref={(el) => { if (el) el.indeterminate = someOn }}
                    onChange={toggleAll}
                  />
                </th>
              )}
              {hg.headers.map((h, i) => {
                const sorted = h.column.getIsSorted()
                const canSort = h.column.getCanSort()
                return (
                  <th
                    key={h.id}
                    scope="col"
                    style={{ width: h.column.columnDef.meta?.width }}
                    aria-sort={sorted === 'asc' ? 'ascending' : sorted === 'desc' ? 'descending' : undefined}
                    className={cn('py-3 text-left font-normal', i === 0 && !selectable ? 'pl-5' : 'pl-0', i === hg.headers.length - 1 ? 'pr-5' : 'pr-3', h.column.columnDef.meta?.align === 'right' && 'text-right')}
                  >
                    {canSort ? (
                      <button type="button" onClick={h.column.getToggleSortingHandler()} className="eyebrow group inline-flex items-center gap-1 text-[0.6875rem] text-muted hover:text-ink">
                        {flexRender(h.column.columnDef.header, h.getContext())}
                        {sorted === 'asc' ? <ArrowUp className="size-3" aria-hidden /> : sorted === 'desc' ? <ArrowDown className="size-3" aria-hidden /> : <ChevronsUpDown className="size-3 opacity-0 group-hover:opacity-60" aria-hidden />}
                      </button>
                    ) : (
                      <span className="eyebrow text-[0.6875rem] text-muted">{flexRender(h.column.columnDef.header, h.getContext())}</span>
                    )}
                  </th>
                )
              })}
            </tr>
          ))}
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={row.id}
              className={cn('relative border-b border-line-soft last:border-b-0 transition-colors', onRowClick && 'cursor-pointer hover:bg-paper-soft focus-within:bg-paper-soft', (highlightId === row.id || sel.includes(row.id)) && 'bg-signal-soft/50')}
            >
              {selectable && (
                <td className="relative z-10 w-11 pl-5">
                  {rowCheckbox(row)}
                </td>
              )}
              {row.getVisibleCells().map((cell, i, all) => (
                <td key={cell.id} className={cn('row-cell py-3.5 align-middle text-[0.875rem]', i === 0 && !selectable ? 'pl-5' : 'pl-0', i === all.length - 1 ? 'pr-5' : 'pr-3', cell.column.columnDef.meta?.align === 'right' && 'text-right')}>
                  {/* One real button per row, stretched over it; row-cell keeps links, buttons and fields in the cells above it. */}
                  {i === 0 && onRowClick && (
                    <button type="button" data-row-open onClick={() => onRowClick(row.original)} className="absolute inset-0 rounded-none focus-visible:outline-offset-[-2px]">
                      <span className="sr-only">{rowLabel?.(row.original) ?? 'Abrir'}</span>
                    </button>
                  )}
                  {flexRender(cell.column.columnDef.cell, cell.getContext())}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {renderCard && (
        <ul className={cn('divide-y divide-line-soft', cardsBelow === 'xl' ? 'xl:hidden' : 'md:hidden')}>
          {rows.map((row) => (
            <li key={row.id} className={cn('flex items-start', sel.includes(row.id) && 'bg-signal-soft/50')}>
              {selectable && <div className="shrink-0 py-4 pl-4">{rowCheckbox(row)}</div>}
              {onRowClick ? (
                <button type="button" onClick={() => onRowClick(row.original)} className="block min-w-0 flex-1 px-4 py-3.5 text-left active:bg-paper-soft">
                  {renderCard(row.original)}
                </button>
              ) : (
                <div className="min-w-0 flex-1 px-4 py-3.5">{renderCard(row.original)}</div>
              )}
            </li>
          ))}
        </ul>
      )}
      {rows.length === 0 && empty}
      {footer && <div className="border-t border-line-soft first:border-t-0">{footer}</div>}
    </div>
  )
}

export type { ColumnDef } from '@tanstack/react-table'
