# E-Sign (Electronic Signatures)

Full document signing lifecycle: create a PDF document, place signature fields, send to a client via email, receive signatures, and deliver a tamper-proof completed PDF.

**Base paths:**
- Creator (authenticated): `/api/esign/documents`
- Client (signing link): `/api/esign/sign`
- Verification (public): `/api/esign/verify`

**Auth:** Creator endpoints require JWT. Client endpoints use a short-lived `ESIGN` signing token. Verify is public.  
**Feature flag:** `E_SIGN` must be enabled for the org.

---

## Document Lifecycle

```
DRAFT → (send) → PENDING → (client opens link) → IN_REVIEW
      → (all fields signed) → SIGNED
      → (PDF stamped + emails sent) → COMPLETED
      → or → EXPIRED (token expired before submission)
      → or → CANCELLED (creator cancelled)
```

---

## Creator Endpoints

### POST `/api/esign/documents`
Creates a new DRAFT document. Renders the base PDF immediately.

**Request body:**
```json
{
  "title": "Service Agreement - ACME",
  "sourceType": "TEMPLATE",
  "templateId": "tmpl123",
  "clientEmail": "client@acme.com",
  "clientName": "John Smith",
  "tokenValidDays": 7
}
```

- `sourceType`: `TEMPLATE` (use a PDF template) or `UPLOAD` (supply raw base64 PDF via `pdfBase64`).
- When `sourceType = TEMPLATE`, the PDF is rendered with an empty data map.
- `tokenValidDays`: signing link expiry (default 7 days).

**Response `200`:** `DocumentResponse`

---

### GET `/api/esign/documents`
Lists all documents created by the authenticated user.

---

### GET `/api/esign/documents/{id}`
Returns full document detail: base PDF (base64), field placements, status.

---

### PUT `/api/esign/documents/{id}/fields`
Saves (replaces) all field placement definitions for a DRAFT document.

**Request body:**
```json
[
  {
    "page": 1,
    "x": 10.5,
    "y": 85.0,
    "width": 25.0,
    "height": 8.0,
    "fieldType": "SIGNATURE",
    "label": "Client Signature",
    "required": true
  },
  {
    "page": 1,
    "x": 60.0,
    "y": 85.0,
    "width": 20.0,
    "height": 8.0,
    "fieldType": "DATE",
    "label": "Signed Date",
    "required": true
  }
]
```

- `x`, `y`, `width`, `height` — expressed as **percentage of page dimensions**.
- `page` — 1-based page number; `0` = stamp on every page.
- `fieldType`: `SIGNATURE` \| `INITIALS` \| `DATE` \| `TEXT`

**Only DRAFT documents can have fields edited.**

---

### POST `/api/esign/documents/{id}/send`
Sends the signing invitation email to the client and transitions document to `PENDING`.

**Query param:** `tokenValidDays` (default 7)

**What happens:**
1. A short-lived ESIGN JWT is generated and stored.
2. An invitation email is sent to `clientEmail` containing the signing link.
3. Document status → `PENDING`.

---

### POST `/api/esign/documents/{id}/resend`
Resends the invitation email. Only works on `PENDING` or `IN_REVIEW` documents.

---

### POST `/api/esign/documents/{id}/cancel`
Cancels the document. Status → `CANCELLED`. Sends a cancellation notification.

---

### GET `/api/esign/documents/{id}/audit`
Returns the complete e-sign audit trail for this document, oldest first.

---

### GET `/api/esign/documents/{id}/download`
Downloads the completed signed PDF. Only available for `COMPLETED` documents.

---

## Client Signing Endpoints

These endpoints are accessed by the signing recipient using the token from the invitation email:

```
Authorization: Bearer <ESIGN-signing-token>
```

### GET `/api/esign/sign/{token}`
Opens the document for signing. Transitions to `IN_REVIEW` on first open. Returns document + base PDF for rendering.

### PUT `/api/esign/sign/{token}/fields/{fieldId}`
Submits the client's value for a single field.

**Request body (SIGNATURE/INITIALS):**
```json
{ "signatureData": "data:image/png;base64,..." }
```

**Request body (TEXT):**
```json
{ "textValue": "John Smith" }
```

**Request body (DATE):**
```json
{ "dateValue": "2026-05-20" }
```

### POST `/api/esign/sign/{token}/submit`
Called after all required fields are signed. Triggers:
1. Async PDF stamping (signatures burned into the PDF).
2. Document status → `COMPLETED`.
3. Completion emails to both creator and client.

---

## Verification Endpoint (Public)

### GET `/api/esign/verify/{id}`
Publicly verifiable document integrity check. Returns document metadata and SHA-256 hash of the signed PDF for independent verification.

---

## E-Sign Audit Events

Every action on a document is recorded immutably in `esign_audit_events`:

| Event | Description |
|-------|-------------|
| `DOCUMENT_CREATED` | Draft created |
| `FIELDS_SAVED` | Field placements updated |
| `DOCUMENT_SENT` | Invitation email sent |
| `LINK_OPENED` | Client clicked email link |
| `DOCUMENT_VIEWED` | Client scrolled/confirmed review |
| `SIGNING_STARTED` | First field submitted |
| `FIELD_SIGNED` | Individual field completed |
| `DOCUMENT_SUBMITTED` | Client submitted all signatures |
| `PDF_GENERATED` | Signed PDF stamped and stored |
| `COMPLETION_EMAIL_SENT` | Delivery emails sent |
| `DOCUMENT_DOWNLOADED` | Creator downloaded final PDF |
| `LINK_EXPIRED` | Token expired (set by scheduler) |
| `DOCUMENT_CANCELLED` | Manually cancelled |

---

## Background Scheduler

`ESignExpiryScheduler` runs **every hour** and:
1. Revokes stale signing JWTs.
2. Transitions `PENDING`/`IN_REVIEW` documents past their `tokenExpiresAt` to `EXPIRED`.

---

## Data Models

### ESignDocument (`esign_documents`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `createdBy` | String | Creator user ID |
| `orgId` | String | Owning org |
| `title` | String | |
| `sourceType` | Enum | `TEMPLATE` \| `UPLOAD` |
| `templateId` | String | Source template ID (if TEMPLATE) |
| `sourcePdfData` | byte[] | Raw PDF bytes |
| `sourcePdfHash` | String | SHA-256 of source PDF |
| `status` | Enum | See lifecycle above |
| `clientEmail` | String | |
| `clientName` | String | |
| `signedPdfData` | byte[] | Completed PDF with stamped signatures |
| `signedPdfHash` | String | SHA-256 tamper verification |
| `signingTokenJti` | String | JWT ID of signing token |
| `tokenExpiresAt` | LocalDateTime | |
| `sentAt` | LocalDateTime | |
| `viewedAt` | LocalDateTime | |
| `submittedAt` | LocalDateTime | |
| `completedAt` | LocalDateTime | |
| `createdAt` | LocalDateTime | Auto |
| `updatedAt` | LocalDateTime | Auto |

### ESignSignatureField (`esign_signature_fields`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | |
| `documentId` | String | Parent document |
| `page` | int | 1-based; 0 = every page |
| `x`, `y`, `width`, `height` | double | % of page dimensions |
| `fieldType` | Enum | `SIGNATURE` \| `INITIALS` \| `DATE` \| `TEXT` |
| `label` | String | |
| `required` | boolean | |
| `value` | String | Base64 PNG or plain text after signing |
| `signingMethod` | Enum | `DRAW` \| `TYPE` \| `UPLOAD` |
| `signedAt` | LocalDateTime | |

---

## Related
- [PDF Templates](./07-pdf-templates.md) — Base PDF source
- [Audit Logging](./05-audit-logging.md) — System audit trail
- [Dashboard](./06-dashboard.md) — E-sign KPIs
