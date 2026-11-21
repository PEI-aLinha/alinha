# ADR 0005 — URL paths in English, user-visible text in European Portuguese

- **Status:** accepted
- **Date:** 2026-11-20
- **Issues:** #65 (convention), #64 (shell), #69
- **Refs:** `CLAUDE.md` → Conventions, ADR 0002

## Context
The users are Portuguese SMEs and their consultants, so everything they read in the UI is in plain European
Portuguese. The first routes followed the UI language too: the consultant area was `/carteira`, and plugins
used paths such as `ativos` or `painel`. Code, comments, docs, commits and branch names, though, are all in English.
That meant route paths were the only identifiers in the code that were not in English. Plugin authors had to guess which
language each path should use, and the paths did not match the code that defines them (`RiskAssetsPage` at `ativos`).

## Decision
- **URL paths are in English.** This covers the shell areas (`/org/:org/...` for the client,
  `/portfolio` and `/portfolio/:org/...` for the consultant, `/onboarding/...` before registration) and every
  plugin route path (`/org/:org/assets`, `/portfolio/:org/dashboard`). The same applies to query parameters and
  hash fragments the platform defines.
- **Everything the user sees is in European Portuguese**, in plain, non-technical language: menu labels, page
  titles, headings, buttons and messages. A route at `assets` is still shown as «Ativos e riscos».
- Tests follow the same rule: route fixtures use English paths, and titles and labels stay in Portuguese (#71).

## Consequences
- (+) One language for every identifier in the code. A path matches the component, plugin id and API resource behind it.
- (+) Plugin authors have one rule to follow, and reviews do not have to check the language of paths case by case.
- (−) The URL bar shows English words to Portuguese users. This is accepted: users get to pages through the menu,
  not by typing URLs.
- (−) Renaming the paths touched the shell and all 7 plugins (`feat/english-url-paths`) and broke old
  bookmarks. That is acceptable before the MVP, since no one depends on them yet.

## Alternatives considered
- **Portuguese paths (`/carteira`, `ativos`):** readable in the URL bar, but paths would be the only Portuguese
  identifiers in the code, and accents and plurals (`organização`, `riscos`) make them awkward to type and encode.
- **Translated paths (one set per language):** no second language is planned, and every route and link would
  need a mapping.
- **Each plugin decides:** inconsistent URLs across the platform, and the same question in every review.
