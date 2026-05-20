# API Keys

Per-organisation API keys for programmatic (machine-to-machine) access to PDF generation, email dispatch, and file upload endpoints.

**Base path:** `/api/organizations/{orgId}/api-keys`  
**Auth:** JWT required (`PLATFORM_ADMIN`, `ORG_ADMIN`, or `ADMIN`).

---

## How API Keys Work

1. An admin creates a key via `POST /api-keys`. The **plain key** is returned **once** — it cannot be retrieved again.
2. The key is stored as a SHA-256 hash (`keyHash`) in MongoDB.
3. External callers pass the key in the `X-Api-Key` header on every request.
4. `ApiKeyAuthFilter` hashes the incoming value and looks it up in `org_api_keys`.
5. If the key is active, non-expired, and allows the requested feature — the call proceeds as an `ApiKeyPrincipal`.

**Key format:** `brfy_<40 random hex chars>`  
**Key prefix (display only):** first 12 chars, e.g. `brfy_a1b2c3d`

---

## Endpoints

### GET `/api/organizations/{orgId}/api-keys`
Lists all API keys for the organisation. `keyHash` is never included in responses.

**Response `200`:**
```json
[
  {
    "id": "key123",
    "orgId": "org456",
    "name": "Production Key",
    "keyPrefix": "brfy_a1b2c3",
    "allowedFeatures": ["PDF_TEMPLATES"],
    "active": true,
    "createdAt": "2026-01-10T09:00:00",
    "createdBy": "admin@acme.com",
    "lastUsedAt": "2026-05-19T14:22:00",
    "expiresAt": null,
    "totalCalls": 1427
  }
]
```

---

### POST `/api/organizations/{orgId}/api-keys`
Generates a new API key.

**Request body:**
```json
{
  "name": "CI Pipeline Key",
  "allowedFeatures": ["PDF_TEMPLATES", "EMAIL_TEMPLATES"],
  "expiresAt": "2027-01-01"
}
```

- `name` — required, human-readable label.
- `allowedFeatures` — subset of the org's enabled features (`PDF_TEMPLATES`, `EMAIL_TEMPLATES`, `E_SIGN`).
- `expiresAt` — optional. Accepts `yyyy-MM-dd` or `yyyy-MM-ddTHH:mm:ss`. Null = never expires.

**Response `200`:**
```json
{
  "key": "brfy_a1b2c3d4e5f6...",
  "keyPrefix": "brfy_a1b2c3",
  "id": "key123",
  "name": "CI Pipeline Key",
  "message": "Save this key — it will not be shown again."
}
```

---

### DELETE `/api/organizations/{orgId}/api-keys/{keyId}`
Revokes (deactivates) an API key immediately.

**Response `204`**

---

### GET `/api/organizations/{orgId}/api-keys/{keyId}/usage`
Returns the usage log for a specific key.

---

## External API (API Key Authenticated)

These endpoints are accessed with `X-Api-Key: brfy_...` instead of a JWT:

### POST `/api/external/generate-pdf`
Generates a PDF from a template. Requires `PDF_TEMPLATES` feature on the key.

**Request body:**
```json
{
  "templateId": "tmpl123",
  "data": { "invoiceNumber": "INV-001", "customerName": "ACME Ltd" },
  "filename": "invoice.pdf"
}
```
**Response `200`:** Binary PDF (`application/pdf`).

---

### POST `/api/external/send-email`
Sends an email using a template. Requires `EMAIL_TEMPLATES` feature on the key.

**Request body:**
```json
{
  "templateId": "tmpl456",
  "to": "recipient@example.com",
  "data": { "userName": "Jane", "amount": "150.00" }
}
```

---

## Data Model

### OrgApiKey (`org_api_keys`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `orgId` | String | Owning organisation |
| `name` | String | Human-readable label |
| `keyPrefix` | String | First 12 chars for display |
| `keyHash` | String | SHA-256 of full key — **never returned in API** |
| `allowedFeatures` | `Set<String>` | Subset of org features |
| `active` | boolean | False = revoked |
| `createdAt` | LocalDateTime | Auto |
| `createdBy` | String | Email of creator |
| `lastUsedAt` | LocalDateTime | Updated on each use |
| `expiresAt` | LocalDateTime | Null = no expiry |
| `totalCalls` | long | Lifetime call counter |

---

## Security Notes

- `keyHash` is excluded from all API responses via `@JsonIgnore`.
- A compromised key should be revoked immediately via DELETE and replaced.
- Keys are org-scoped — a key for org A cannot access org B data.
- `totalCalls` is incremented atomically on every authenticated request.
