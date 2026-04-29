# CSS Rebuild — Review Pass 1 (2026-04-29)

Four parallel review agents audited the CSS-rebuild plan + 8 child tickets. This file consolidates findings and lists per-ticket corrections to apply before / during implementation. Each affected bead has a comment pointing here.

Source agents:
1. **Coverage** — frontend/src vs plan inventory.
2. **Dep graph + ACs** — graph hygiene, AC verifiability, test-id continuity.
3. **Tailwind v4 toolchain feasibility** — package.json, vite, Docker, nginx, Vitest.
4. **Mockup fidelity** — design tokens vs actual mockup CSS.

---

## P1 — must-fix before kickoff (silent-regression risk)

### df-hly: ReclassifyModal accent is **amber**, not "blue/violet"

The bead body says "ReclassifyModal: blue/violet accent". The actual mockup (`problem-statement/mockups/02-review-reclassify-pinnacle.html` lines 210, 227–228) uses the same amber palette as FlagModal:

```
.modal-icon.warning { background: #fef3c7; color: #f59e0b; }
button.confirm { background: #f59e0b; }  hover: #d97706;
```

**Action:** when implementing df-hly, render BOTH modals in amber (`#fef3c7` icon tile, `#f59e0b` icon glyph + confirm button, hover `#d97706`). The "blue/violet" claim was speculative and conflicts with the reference.

### df-qcu: define `stage-segment-*` CSS class hooks (currently dead refs)

`StageProgress.tsx` calls `colorClass()` (lines 302–323, 352) returning class names like `stage-segment-red`, `stage-segment-violet`, etc. **None of these classes are defined anywhere today** and df-qcu's AC mentions color states without enumerating these specific hooks. Without explicit definitions in `index.css` (or the component switching to inline tokens), the bar will silently render unstyled.

**Action:** in df-qcu, either (a) add `@layer components` definitions in `index.css` for every `stage-segment-*` class the component emits, OR (b) replace `colorClass()` with Tailwind utility classes inline. Pick one explicitly — don't leave dead refs.

### df-qcu: per-stage current-dot palette is incomplete

Plan only lists violet `#7c3aed` for "current". Actual mockups use distinct colors per stage:

| State | Color | Mockup |
|---|---|---|
| review (current) | `#7c3aed` violet | 02-review-pinnacle.html |
| approval (current) | `#be185d` pink | 03-approval-pinnacle.html:87 |
| filed (terminal) | `#10b981` green | 04-filed-riverside.html:89 |
| rejected (terminal) | `#dc2626` red | 04-rejected-pinnacle.html:93 |
| regressed | `#e5e7eb` fill + `#f59e0b` 2px ring | 02-review-flagged-pinnacle.html:109 |
| skipped | `text-decoration: line-through; color: #d1d5db` | 04-rejected.html:106 |

**Action:** df-qcu must implement all six current-dot states with a 3px halo ring on the current node.

### df-qv7: import `react-pdf` annotation/text layer CSS

`react-pdf` ships `react-pdf/dist/Page/AnnotationLayer.css` and `TextLayer.css`. Without these the text-selection layer misaligns over the rendered PDF body — even after df-qcu styles the toolbar and surrounding panel.

**Action:** in df-qv7, add to `main.tsx` ahead of `./index.css` (or after — order doesn't matter for these files):
```ts
import "react-pdf/dist/Page/AnnotationLayer.css";
import "react-pdf/dist/Page/TextLayer.css";
```

### df-qv7: pin `@tailwindcss/vite >= 4.2.2`

The repo runs Vite `^8.0.0`. `@tailwindcss/vite` only added Vite 8 support in 4.2.2 (Mar 2026). Without the pin, the resolver may pick an older 4.x that omits Vite 8.

**Action:** install command should be `npm install -D tailwindcss@^4 @tailwindcss/vite@^4.2.2`.

---

## P2 — should-fix (test contract, missing surfaces, mockup tokens)

### Test-id enumeration in ACs

The audit found ACs that say "existing tests pass" / "no test-id changes" without listing which testids matter. A Tailwind rewrite that drops or renames any of these silently breaks tests AND screen-reader contracts. Each ticket should explicitly enumerate:

- **df-vw1** — `dashboard-stats`, `stat-{key}-value` (keys: `inProgress`, `awaitingReview`, `flagged`, `filedThisMonth`), `filter-status`, `filter-doctype`, `upload-button`, `upload-file-input`, `upload-error`, `dashboard-error`, `documents-section`, `documents-empty`, `document-row[data-status]`, `processing-row`, `processing-failure`.
- **df-qcu** — `pdf-viewer[data-pdf-state]`, `pdf-load-error`, `pdf-loading`, `document-filename`, `document-doc-type`, `document-stage`, `document-status`, `document-uploaded-at`, `detail-pane-left`, `detail-pane-right`, `stage-progress` (plus the existing `data-state`/`data-segment` attribute preservation).
- **df-4p1** — `form-panel[data-branch]`, `review-fields`, `reextraction-in-progress-banner`, `reextraction-failed-banner`, `reextraction-failed-message` (text matching `/boom|Unable to load/`), `approve-button`, `reject-button`, `resolve-button`, `review-action-bar` (must keep buttons inside it; `FormPanel.test.tsx:132` queries via `querySelectorAll("button")` on this node), `flag-banner-origin` (text equals stage id; e2e/flag-and-resolve.spec.ts:37).
- **df-hly** — `flag-modal`, `flag-modal-form`, `flag-modal-comment`, `flag-modal-submit`, `flag-modal-cancel`, `reclassify-modal`, `reclassify-modal-heading`, `reclassify-modal-body`, `reclassify-cancel`, `reclassify-confirm`, `doctype-select`.
- **df-5ua** — also include `org-picker-page`, `org-picker-loading`, `org-picker-error` (App.test.tsx:34).
- **df-ib5** — AC says "the org-card test that asserts on the alt-text or data-testid keeps working". **No test asserts on `<img alt>`.** OrgPickerPage.test.tsx:55 uses `getByRole('button', { name: /Ironworks Construction/ })` — relies on the `<h2>{name}</h2>` falling through. **Correction:** preserve `<h2>{name}</h2>` so the accessible name still resolves; the alt-text claim is wrong.

### df-ib5: missing dep edges (will fix in beads)

df-ib5 lists `FlagModal.tsx`, `ReclassifyModal.tsx` (df-hly's files) and `FormPanel.tsx` (df-4p1's). No dep edge today. **Adding edges:** df-ib5 → df-hly and df-ib5 → df-4p1. (Done; see "Dep graph changes" below.)

### df-4p1: cite `02-review-flagged-pinnacle.html`

This mockup is referenced by no ticket but owns the unique `flag-banner` palette (`#fff7ed` bg, `#fed7aa` border, `#c2410c` title, `#9a3412` sub, comment box bg `#fff` border `#fed7aa`, resolve-btn hover `#fb923c`). df-4p1's banner styles should follow it, not the generic "red/amber" plan token.

### df-4p1: font-weight 500 for read-only summaries

Plan lists weights 400/600/700. Mockups use `font-weight: 500` for `field-value` (03-approval-pinnacle:111, 04-filed-riverside:111). ApprovalSummary / TerminalSummary / ReadOnlyArrayTable should map to a 500 utility (Tailwind `font-medium`).

### df-vw1 + df-qcu: PDF dark scale + flagged badge

- df-qcu: PDF chrome uses three shades — `#2a2a2e` panel, `#3a3a3e` toolbar bg, `#4a4a4e` toolbar border. Plan only lists `#2a2a2e`. Add the other two to `index.css` `@theme` so toolbar styling matches.
- df-vw1: `badge-flagged` (with `#fecdd3` border) is a separate badge from `badge-stage.flagged`. Plan conflates them. Render the flagged-from-stage badge as the bordered variant in the dashboard table.

### Stage progress edge tokens

- `stage-line.rejected` uses `#fecaca` (04-rejected.html:98) — add to df-qcu palette.

### df-ge4: pagination scope is fuzzy

AC says "pagination footer rendering 'Showing 1-N of M' + page buttons matching the mockup, no behavior wiring required, buttons inert if not yet wired" — but no current pagination code or test exists, so what counts as "matching mockup" is subjective. **Action:** either (a) carve pagination out into its own micro-bead with explicit AC ("renders the table footer with two buttons, no onClick wiring"), or (b) drop pagination from df-ge4 and leave the table footer alone.

---

## P3 — nice-to-have polish

- Spacing values 36/38/48 (modal icon 36×36, action bar button 38px, pdf-page padding 48 40) — add to plan token scale or use Tailwind arbitrary values.
- Modal width: 440px, OrgPicker grid max-width 780px — call out in df-hly / df-5ua respectively.
- Topbar muted text `#c4c9d9`, white-alpha overlays `rgba(255,255,255,0.08/0.12)`, avatar accent `#6c9bff` — add to df-qv7 Topbar styling.
- Btn-danger border `#fecaca`, btn-flag border `#fde68a`, modal-btn-cancel border `#d1d5db` — explicit outline tints (df-4p1 / df-hly).
- Reclassify highlight `category-confirm.changed`: `border-color:#f59e0b; background:#fffbeb` (df-hly).
- Materials-table variant in `03-approval-ironworks.html` — df-4p1 should explicitly cover this read-only line-items shape too.

---

## Missed surfaces — file as new beads

The coverage audit found surfaces no ticket addresses. A new bead `df-???` should cover at minimum:

1. **App.tsx Topbar mounting** — df-qv7 creates `<Topbar>` but no ticket wires it as a layout for the dashboard + detail routes. Risk: two implementors mount it independently (df-qv7 says creates, df-vw1 says "Topbar mounted with org name").
2. **NotFoundPage** styling pass — minimal centered message; plan mentions it but no ticket owns it.
3. **Loading / error placeholders** — every route has `*-loading` and `*-error` testids (`OrgPickerPage:21,23`, `DashboardPage:96,98`, `DocumentDetailPage:79,81`, `PdfViewer.tsx:30`). They will look raw against styled surroundings.
4. **Empty states** — `documents-empty`, `readonly-array-empty`.
5. **Inline `style={}` retirements** — `ProcessingSection.tsx:30` (opacity), `DetailLayout.tsx:12-17` (grid). The inline grid in DetailLayout will fight df-qcu's flex split if not removed.

**Filed as `df-k0u`** (P2): "App.tsx Topbar mounting + NotFoundPage + loading/error placeholders + retire inline style={} blocks". Depends on df-qv7. Blocks df-7cr + df-ge4. df-vw1 and df-qcu have follow-up comments cross-linking df-k0u so neither re-mounts Topbar.

---

## Dep graph changes applied

- **Add:** `df-ib5 → df-hly` (df-ib5 edits FlagModal.tsx, ReclassifyModal.tsx).
- **Add:** `df-ib5 → df-4p1` (df-ib5 edits FormPanel.tsx).
- Redundant edges from df-7cr to children **kept** for clarity (umbrella semantically blocked by each child).

---

## Estimate sanity

Sum of ticket estimates: ~19.5h. Realistic with df-qv7 drift to 3-4h, df-ge4 to 3h, df-ib5 to 3h: ~22-25h. Handoff cited "17h" — slightly optimistic.

---

## Verdict

CSS rebuild plan is **substantially correct** but has **2 outright errors** (df-hly amber not blue/violet; df-ib5 alt-text claim) and **3 silent-regression risks** (stage-segment-* dead classes, missing react-pdf CSS, vague test-id ACs). Apply this review-pass-1 before implementation kicks off; total cost ~30 min of bead edits.
