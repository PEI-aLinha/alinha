# UI text, data and formatting for plugin authors

How plugin pages fetch data, report errors and write text, so every screen in aLinha behaves and reads the same.
Everything below comes from `@alinha/plugin-sdk`.

The people reading the UI run small companies. Most are not IT or legal specialists. Write for them.

## Fetching data

Use `useApiQuery` to read and `useApiMutation` to write. Both go through `host.api`, so the session cookie, the
current organization (`X-Alinha-Tenant`) and the 401 handling come from the shell.

```tsx
import { errorMessage, useApiMutation, useApiQuery } from '@alinha/plugin-sdk'
import { Button, EmptyState } from '@alinha/ui'

const KEY = ['risk-assets', 'assets']

export function AssetsPage() {
  const assets = useApiQuery<Asset[]>(KEY, '/risk-assets/assets')
  const create = useApiMutation<NewAsset, Asset>({ path: '/risk-assets/assets', invalidates: [KEY] })

  if (assets.isPending) return <p>A carregar ativos…</p>
  if (assets.isError) return <p role="alert">{errorMessage(assets.error)}</p>
  // ...
}
```

- Query keys start with the plugin id. `useApiQuery` adds the path to the end of the key, so
  `/risk-assets/assets/1` and `/risk-assets/assets/2` never share a cache entry. Invalidate by the prefix
  (`['risk-assets', 'assets']`). The shell gives each organization its own cache, so keys never need the tenant.
- `useApiMutation` sends the body as JSON (POST unless you pass `method`) and then refreshes the queries in
  `invalidates`. `path` can be a function of the body: `(asset) => \`/risk-assets/assets/${asset.id}\``.
- Types for core endpoints come from `@alinha/api-client`. Contract paths start with `/api`, but `host.api` paths
  leave it out because the shell adds it: `useApiQuery<ApiResponse<'/api/me'>>(['core', 'me'], '/me')`.
- Failed reads are retried only for network and 5xx errors. A 4xx fails at once.

## Errors

Show `errorMessage(error)`, never `error.message`. `error.message` is technical and in English.

- If the backend sent an aLinha Problem Details body (it has a `code`), its `detail` is shown. The backend writes
  that text for the user, in Portuguese.
- Otherwise the message comes from the HTTP status:

  | Status | Message |
  |---|---|
  | 400, 422 | Alguns dados não estão corretos. Reveja-os e tente de novo. |
  | 401 | A sua sessão terminou. Entre novamente para continuar. |
  | 403 | Não tem permissão para fazer isto. |
  | 404, 410 | Não encontrámos o que procura. Pode ter sido apagado. |
  | 409, 412 | Estes dados foram alterados entretanto. Atualize a página e tente de novo. |
  | 413 | O ficheiro é demasiado grande. |
  | 429 | Fez muitos pedidos seguidos. Aguarde um pouco e tente de novo. |
  | 5xx | O serviço está com dificuldades. Tente de novo dentro de alguns minutos. |
  | no connection (`NetworkError`) | Não foi possível ligar ao serviço. Verifique a sua ligação à internet. |
  | anything else | Ocorreu um erro inesperado. Tente de novo. |

- For forms, `fieldErrors(error)` returns `{ field: message }` from the Problem `errors` list. Show each message
  next to its input.
- Backend developers: when an error is meant for the user, send a `code` and a `detail` that follows the rules
  below. Without a `code`, the frontend ignores `detail`.

## Dates and numbers

Never use `toLocaleString()` or `toLocaleDateString()`, because they follow the browser's language and time zone.
Use these instead. All of them show `—` when there is no value.

| Function | Example |
|---|---|
| `formatDate(value)` | 19/11/2026 |
| `formatDate(value, 'long')` | 19 de novembro de 2026 |
| `formatDateTime(value)` | 19/11/2026, 14:30 (Lisbon time) |
| `formatRelativeTime(value)` | há 3 dias, ontem, agora |
| `formatNumber(1234.5)` | 1234,5 (12 345 from five digits) |
| `formatPercent(0.42)` | 42% |
| `formatCurrency(1234.5)` | 1234,50 € |

A date-only string such as `2026-11-19` is a calendar day: it is never moved to the day before or after, and
`formatDateTime` shows it without a time.

## Writing UI text

All text the user sees is in **European Portuguese** (pt-PT). Code, comments, URLs and logs stay in English.

1. **Address the person formally, without "você".** Use the third person with no pronoun: "Indique o NIF da
   empresa", "A sua conta ainda não está pronta". This is what the shell already does.
2. **Say what happened and what to do next.** "Não foi possível guardar. Tente de novo." is better than "Erro 500".
3. **Short sentences, everyday words.** One idea per sentence. Avoid jargon, English terms and acronyms the person
   may not know. If an acronym is needed (NIS2, RGPD), explain it the first time on the page.
4. **Buttons are verbs in the infinitive:** Guardar, Cancelar, Enviar, Continuar, Apagar. Do not write "OK" or
   "Submeter".
5. **Titles and labels use sentence case:** "Ativos da organização", not "Ativos Da Organização".
6. **Loading text uses "a" + infinitive:** "A carregar…", "A guardar…". Not "Carregando…" (pt-BR).
7. **Do not blame the person.** "Este NIF não é válido" instead of "Introduziu um NIF errado".
8. **AI-generated content is labelled** "Sugerido por IA". It always needs the consultant's approval before the
   client sees it.

### pt-PT, not pt-BR

| Write (pt-PT) | Not (pt-BR) |
|---|---|
| ecrã | tela |
| ficheiro | arquivo |
| utilizador | usuário |
| guardar | salvar |
| registo | registro |
| equipa | time |
| definições | configurações |
| palavra-passe | senha |
| telemóvel | celular |
| transferir | baixar |
| a carregar | carregando |

## Glossary

Use the same plain word for the same thing on every screen. When a technical term must appear, add the plain
explanation next to it the first time.

| Concept (code / docs) | Write in the UI | Notes |
|---|---|---|
| tenant, organization | organização | Never "tenant" or "inquilino". |
| client organization (consultant side) | cliente | The consultant's list of clients is the "Carteira de clientes". |
| consultant | consultor | The service provider. |
| designated responsible person | responsável | The SME person who uses the client area. |
| analyst | analista | |
| assessment | avaliação | |
| control | medida | "Controlo" only on pages for specialists. |
| compliant | cumpre | Control status, from the Compliance Engine. |
| gap | não cumpre | Avoid "gap" and "lacuna". |
| partially compliant | cumpre em parte | |
| not applicable | não se aplica | |
| evidence | prova | "Evidência" only on pages for specialists (consultants know it from ISO 27001 material). |
| asset | ativo | Explain it the first time: "equipamentos, sistemas e dados importantes para a empresa". |
| risk | risco | |
| threat | ameaça | |
| vulnerability | fragilidade | "Vulnerabilidade" only on pages for specialists. |
| roadmap | plano de ação | |
| recommendation | recomendação | |
| maturity level (SOC-CMM) | nível de maturidade | |
| incident | incidente | |
| dashboard | painel | |
| report | relatório | |
| notification | notificação | |
| provider, integration (code repository, external surface) | ligação | "Ligar o GitHub", not "Integrar provider". |
| sign in / sign out | entrar / sair | Not "login" or "logout". |
| upload | carregar ficheiro | |
| download | transferir | |
| AI-generated | sugerido por IA | Shown on every AI draft (AI Act). |
| approve / validate | aprovar | |
| framework, regulation (NIS2, RGPD, AI Act) | regulamento, lei | Keep the short name: "NIS2", "RGPD". |

Add new terms here in the same PR that first uses them in the UI.
