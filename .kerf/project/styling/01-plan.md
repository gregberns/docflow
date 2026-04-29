# DocFlow Frontend Styling Plan

## Goal

Bring the DocFlow frontend from raw default-styled HTML to mockup-fidelity
styling so the take-home demo is presentable. The mockups under
`problem-statement/mockups/` define the target visual language: a dark
navy topbar, light-gray app surface, white cards with subtle borders, a blue
accent for primary actions, and a small set of pastel badge variants for
workflow stages. The work is purely additive — markup and behavior already
exist. Goal is to get every route (`OrgPickerPage`, `DashboardPage`,
`DocumentDetailPage`) and every component (`OrgPickerCard`, the dashboard
stats/filter/processing/documents sections, the detail PDF + form panel,
modals, banners, action bar) within reasonable visual tolerance of the
mockups, fix the broken `<img>` icon placeholders, and keep the existing
Vitest + Playwright suites green.

## Toolchain

Use **Tailwind CSS** (v4 with the official Vite plugin: `tailwindcss` +
`@tailwindcss/vite`). Reasons: (1) the mockups are pure utility-class style
already — atomic spacing, color, and typography tokens map 1:1; (2) no
per-component CSS file proliferation across the 20 existing components;
(3) zero CSS imports in the codebase today, so there is nothing to migrate;
(4) Tailwind v4 needs only one CSS file with `@import "tailwindcss";` and
the Vite plugin — no `tailwind.config.js`, no PostCSS config, no
`autoprefixer` install. Custom design tokens (the navy `#1a1a2e`, the
accent blue `#6c9bff`, the stage-badge palette) live in `@theme` blocks
inside the single `index.css`. We import that file once from `main.tsx`.

## Design tokens (extracted from mockups)

Colors:

- Surface: `#f5f6f8` (app bg), `#ffffff` (cards), `#f9fafb` (table head /
  zebra), `#1a1a2e` (topbar / primary text).
- Accent (primary action / focus ring): `#6c9bff` with `#5a8af2` hover.
- Stage badges: classify `#eff6ff/#3b82f6`, extract `#fef3c7/#b45309`,
  review `#ede9fe/#7c3aed`, approval `#fce7f3/#be185d`, filed
  `#d1fae5/#059669`, rejected `#fee2e2/#dc2626`, flagged `#fff1f2/#e11d48`,
  processing `#f3f4f6/#9ca3af`, type-neutral `#f3f4f6/#374151`.
- Status semantics: success green `#10b981` (and hover `#059669`), warn
  amber `#f59e0b`, danger red `#dc2626/#ef4444`, neutral grays
  `#374151 / #6b7280 / #9ca3af / #d1d5db / #e5e7eb / #f3f4f6`.

Typography:

- Family: `-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
  sans-serif` (apply on `body`).
- Scale (px): 9, 10, 11, 12, 13 (body), 14, 16, 18, 20, 22, 24, 32 (logo).
- Weights: 400 (body), 600 (labels / nav), 700 (titles, badges).
- Tabular numerics on table monetary cells (`font-variant-numeric:
  tabular-nums`).

Spacing: 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 24, 28, 32, 40 px (Tailwind's
default scale covers all of these). Border-radius: 4, 6, 8, 12.

Layout primitives:

- App: 52px topbar + content area; `.page` max-width 1400px, padding
  `24px 32px`, centered.
- Detail layout: split `pdf-panel` (flex:1, dark `#2a2a2e`) + `form-panel`
  (420px white, left border `#e5e7eb`); height = `100vh - 52px`.
- Card: white bg, `#e5e7eb` 1px border, 8px radius, subtle hover shadow.

## Page / component inventory

Routes:

- `OrgPickerPage` — centered logo + subtitle, 3-column grid of org cards.
- `DashboardPage` — topbar + page header + filters bar + 4-stat row +
  table card (with processing rows, separator, and pagination footer).
- `DocumentDetailPage` — topbar + split-pane layout (PDF left, form right),
  StageProgress band above the form body.
- `NotFoundPage` — minimal centered message; light styling only.

Components:

- `OrgPickerCard` — white card, pastel icon tile, name + doc-types list,
  bordered stats footer; replace broken `<img src={icon}>` with a
  reliable visual (see Icons ticket).
- `DashboardStatsBar` — 4 stat cards in a row with colored value text.
- `DashboardFilterBar` — label + select pairs, search input, separator,
  blue primary upload button.
- `ProcessingSection` — opacity-dimmed table-style rows with spinner;
  ends with a dashed separator.
- `DocumentsSection` — main documents table with badge cells, hover row,
  chevron indicator, pagination footer (or "Showing N of M" stub).
- `DetailLayout` — flex split-pane container (left/right slots).
- `PdfViewer` — dark toolbar (zoom/filename/page-info) above the PDF body.
- `DocumentHeader` — back link + doc id + filename + stage/type badges.
- `StageProgress` — segmented dots-and-lines indicator with labels under;
  done/current/upcoming styling. Already has data-state; just style.
- `FormPanel` — sectioned form body with section titles, two-col rows,
  bordered action bar at the bottom.
- `ReviewForm` — same form-input/form-select patterns; field rows.
- `FieldArrayTable` — compact line-items table with inline-edit inputs
  that show borders only on hover/focus.
- `ReadOnlyArrayTable` — same shape minus inputs; static cells.
- `ReviewForm` / `ApprovalSummary` / `TerminalSummary` — read-only
  field-row layouts (label fixed-width + value).
- `FlagModal` — centered modal, amber-accent header icon, textarea, two
  footer buttons (cancel + flag).
- `ReclassifyModal` — same modal shell, blue/violet accent, doc-type
  select.
- `FlagBanner`, `ReextractionInProgressBanner`,
  `ReextractionFailedBanner` — colored notice strips above the form body.

## Sequencing

1. **Toolchain + base styles + theme tokens** (one ticket). Install
   Tailwind v4, add Vite plugin, create `src/index.css` with the
   `@import "tailwindcss";` directive and a `@theme` block for the
   custom colors / fonts. Import from `main.tsx`. Apply global body
   font + bg color so even un-styled subviews look reasonable. Add a
   `Topbar` shell component (or wrapper in `App.tsx`) — it appears on
   both DashboardPage and DocumentDetailPage.
2. **OrgPickerPage + OrgPickerCard.** Self-contained, no topbar.
3. **DashboardPage + its sub-components** (`StatsBar`, `FilterBar`,
   `ProcessingSection`, `DocumentsSection`, badges).
4. **DocumentDetailPage + DetailLayout + PdfViewer + DocumentHeader +
   StageProgress.**
5. **FormPanel + FieldArrayTable + ReadOnlyArrayTable + ReviewForm +
   ApprovalSummary + TerminalSummary + banners.**
6. **Modals** (`FlagModal`, `ReclassifyModal`).
7. **Icons fix** — replace `<img src={icon}>` and SVG-needs (chevrons,
   upload, check, flag, search). Strategy: inline SVGs (matches the
   mockups, no external dep, no font asset, no broken image fallback).
   Per-org icon: emoji entity in a colored tile (matches mockup
   `00-org-picker.html`).
8. **Polish + cross-browser sanity** — fix any layout overflow at
   1280×800, run lint/typecheck/test/test:e2e clean.

## Out of scope

- Animation / micro-interaction polish beyond the trivial Tailwind
  `transition` and the existing `spin` keyframe.
- Dark mode.
- Accessibility audit beyond the basic semantic structure that already
  exists (keep `data-testid` and `aria-*` intact, do not remove
  `role="alert"`, label form inputs).
- Responsive design below 1024px width (mockups are designed for
  1280–1400; we keep them readable but do not redesign for mobile).
- Reskinning the rendered PDF content (`react-pdf` output) beyond the
  surrounding viewer chrome.
- Reorganizing component files / extracting new components other than
  the optional `Topbar` wrapper called out in ticket 1.
