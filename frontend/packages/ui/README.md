# @alinha/ui

The aLinha design system, ported from the MS3 high-fidelity prototype (`frontend-prototype/src/ui`).
Plugins build their pages from these components so every page looks and behaves the same.

```tsx
import { Button, DataTable, Field, PageHeader, TextInput, useToast } from '@alinha/ui'
```

## Rules for plugin authors

- **Use the components first.** Write your own markup only for layout (grids, spacing). If you need a component
  that is missing, add it here instead of inside your plugin.
- **Colours come from the tokens** (`bg-paper`, `text-ink`, `text-ink-soft`, `border-line-soft`, `bg-brand`,
  `text-danger`, `bg-risk-high`, ...). Do not use Tailwind's default palette (`gray-500`, `red-600`, ...): it does
  not pass the contrast checks in dark mode or the colour-vision modes. It is still enabled only so pages written
  before the kit keep working while they move to the tokens.
- **Never use colour alone to say something.** Pair it with text or an icon (`RiskBadge` does this for you).
- **Text the user sees is European Portuguese, plain and non-technical.** Components that have their own text
  (`Fechar`, `Escolher…`, `A carregar…`, `Tentar de novo`) already use it.
- **One `h1` per page:** `PageHeader`. Use `h2` for sections, and give `EmptyState` the right `headingLevel`.
- The shell imports `@alinha/ui/theme.css` and mounts `ToastProvider`. Plugins import neither.

## Components

| Need | Use |
|---|---|
| Page title, subtitle and actions | `PageHeader` |
| An action | `Button` (`primary`, `secondary`, `ghost`, `link`, `danger`); a link that looks like a button: `buttonClass()` |
| A form field with label, hint and error | `Field` + `TextInput` / `TextArea` / `Select` |
| Yes/no | `Checkbox` |
| One of a few options | `RadioGroup` |
| A box of content, a number with a caption, a notice | `Card`, `StatCard`, `Note` |
| A status or category | `Badge`; risk and criticality: `RiskBadge`, `CriticalityBadge` |
| Content drafted by AI | `AIBadge` (mandatory, AI Act) |
| A list of records | `DataTable` (sorting, row click, selection, cards on small screens) |
| Sections on one page | `Tabs` |
| A multi-step flow | `Stepper` |
| A question or short form on top of the page | `Modal`; detail or editing at the side: `Drawer` |
| "Saved" / "Could not save" | `useToast().show(message, 'success' \| 'error')` |
| Loading, nothing yet, failed | `SkeletonRows`, `EmptyState`, `ErrorState` |

## Field example

`Field` gives the control its id, the ids of the hint and the error, and whether it is required. Pass them on:

```tsx
<Field label="NIF" hint="9 dígitos" error={errors.nif}>
  {(p) => (
    <TextInput id={p.id} aria-describedby={p.describedBy} aria-required={p.required} invalid={p.invalid}
      value={nif} onChange={(e) => setNif(e.target.value)} />
  )}
</Field>
```

## Accessibility built in (WCAG 2.2 AA, from the MS3 accessibility review)

- Visible focus ring on every control (`:focus-visible`, 2 px signal colour).
- Labels, hints and errors are linked to their control; errors are announced (`role="alert"`).
- `Modal` and `Drawer` trap focus, close with Escape and give focus back to what opened them.
- `Tabs` move with the arrow keys; `DataTable` has a caption, `aria-sort` and one real button per row.
- Success toasts are polite (`role="status"`) and go away; error toasts are alerts and stay until closed.
- Contrast of every text and border token is tested in light, dark and the colour-vision modes
  (`src/contrast.test.ts`). Animations are switched off with `prefers-reduced-motion`; Windows high contrast
  (`forced-colors`) keeps selected and current states visible.

## Developing

```bash
pnpm --filter @alinha/ui test        # Vitest + coverage (≥ 70%)
pnpm --filter @alinha/ui typecheck
```
