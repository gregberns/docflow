# API walkthrough

A realistic end-to-end flow against a running stack, using `curl`. Use this if you want to exercise the system without the SPA. Assumes the default ports from `.env.example` (`BACKEND_HOST_PORT=8551`). The seeded dashboard works without an Anthropic API key; only step 3 (live upload) and `make eval` actually call the LLM.

```
export BASE=http://localhost:8551
```

## 1. Health check

```
curl -s "$BASE/api/health"
```

Response: `200 OK`, body `{"status":"UP"}`.

## 2. List organizations

Lists the seeded orgs and per-org in-progress / filed counts. Pick an `id` from the response for the rest of the walkthrough — `riverside-bistro` is a good default since its workflows are short.

```
curl -s "$BASE/api/organizations"
```

Response: `200 OK`, JSON array of `{ id, displayName, iconId, documentTypeIds, inProgressCount, filedCount }`.

## 3. Upload a PDF (live LLM)

Multipart upload of a sample PDF. The backend stores bytes, inserts `StoredDocument` + `ProcessingDocument`, and signals C3 to start text-extract → classify → extract. Requires `ANTHROPIC_API_KEY` configured on the backend.

```
curl -s -X POST \
  -F "file=@problem-statement/samples/riverside-bistro/invoices/senor_tacos_wholesale_march_2024.pdf" \
  "$BASE/api/organizations/riverside-bistro/documents"
```

Response: `201 Created`, body `{ "storedDocumentId": "<uuid>", "processingDocumentId": "<uuid>" }`.

## 4. Poll the dashboard

Same endpoint as the SPA dashboard. Returns three arrays: `processing` (still in pipeline), `documents` (post-pipeline, by stage/status), and `stats`. Poll until your new doc moves from `processing` to `documents` — typically 5–15s for a small PDF. Optional query params: `status`, `stage`, `docType`.

```
curl -s "$BASE/api/organizations/riverside-bistro/documents"
```

Response: `200 OK`, body `{ processing: [...], documents: [{ documentId, currentStageId, currentStatus, detectedDocumentType, extractedFields, ... }], stats: { processingCount, inProgressCount, filedCount, rejectedCount } }`. Capture a `documentId` from `documents[]` for the next step.

## 5. Fetch document detail

```
DOC=<documentId-from-step-4>
curl -s "$BASE/api/documents/$DOC"
```

Response: `200 OK`, body matches one entry of the dashboard `documents[]` shape — `documentId`, `organizationId`, `sourceFilename`, `mimeType`, `uploadedAt`, `processedAt`, `currentStageId`, `currentStageDisplayName`, `currentStatus`, `detectedDocumentType`, `extractedFields`, `reextractionStatus`. The PDF bytes are at `GET /api/documents/{id}/file`.

## 6. Edit an extracted field

`extractedFields` is a full-replace map; the entire schema-shaped object goes in. Validated against the doc-type's `FieldSchema` (required fields, enum values, types).

```
curl -s -X PATCH -H "Content-Type: application/json" \
  -d '{"extractedFields":{"vendor":"Senor Tacos Wholesale","invoiceNumber":"ST-2024-03","amount":"482.50","date":"2024-03-15"}}' \
  "$BASE/api/documents/$DOC/review/fields"
```

Response: `200 OK`, full `DocumentView` with the patched fields. `400` with `fieldErrors[]` if validation fails.

## 7. Approve / Reject / Flag

A polymorphic body keyed on `action` (`Approve` | `Reject` | `Flag` | `Resolve`). Routes through the configured workflow's transitions and stage guards.

```
# Approve — advance to next stage per the workflow
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"action":"Approve"}' \
  "$BASE/api/documents/$DOC/actions"

# Flag with a comment — moves to a flagged sub-state
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"action":"Flag","comment":"vendor name looks wrong"}' \
  "$BASE/api/documents/$DOC/actions"
```

Response: `200 OK`, full `DocumentView` reflecting the new `currentStageId` / `currentStatus`. `409` (`InvalidAction`) if the action is not allowed in the current stage; `400` (`ValidationFailed`) if a guard rejects.

## 8. Retype (reclassify + re-extract)

Switches the document to a different doc type and re-runs extract. Asynchronous — returns `202` immediately and `reextractionStatus` flips through `IN_PROGRESS` → `READY` (poll step 5 to watch).

```
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"newDocumentType":"receipt"}' \
  "$BASE/api/documents/$DOC/review/retype"
```

Response: `202 Accepted`, body `{ "reextractionStatus": "IN_PROGRESS" }`. `400` if `newDocumentType` is not configured for the org.

## 9. Subscribe to per-org SSE

`text/event-stream` carrying `ProcessingStepChanged` and `DocumentStateChanged` events for a single org. The SPA dashboard refetches on any event; you can do the same in scripts. Connection is open-ended — interrupt with Ctrl-C.

```
curl -N "$BASE/api/organizations/riverside-bistro/stream"
```

Response: `200 OK`, `Content-Type: text/event-stream`. Frames look like `event: ProcessingStepChanged\ndata: {...}\n\n`.
