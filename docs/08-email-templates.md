# Email Templates

Build, version, and dispatch HTML email templates with `{{placeholder}}` variable substitution via the Resend API.

**Base path:** `/api/email-templates`  
**Auth:** JWT required. Feature flag `EMAIL_TEMPLATES` must be enabled for the org.

---

## Endpoints

### GET `/api/email-templates`
Lists all non-deleted email templates for the caller's org.

**Response `200`:** `List<EmailTemplate>`

---

### GET `/api/email-templates/{id}`
Returns a single template by ID. Org-scoped.

---

### POST `/api/email-templates`
Creates a new email template.

**Request body:**
```json
{
  "name": "Welcome Email",
  "subject": "Welcome to {{orgName}}!",
  "previewText": "Thanks for joining us.",
  "fromName": "Braify Support",
  "htmlContent": "<h1>Hello {{firstName}},</h1><p>Welcome to {{orgName}}!</p>",
  "cssContent": "h1 { color: #6c47ff; }",
  "gjsData": "{ \"components\": [...] }",
  "placeholders": ["firstName", "orgName"]
}
```

**Response `200`:** `EmailTemplate`

---

### PUT `/api/email-templates/{id}`
Updates the template and saves a version snapshot.

**Request body:** same shape as POST.

---

### DELETE `/api/email-templates/{id}`
Soft-deletes the template. Version history retained.

**Response `204`**

---

## Send Email

### POST `/api/email-templates/{id}/send`
Renders the template by substituting `{{placeholder}}` values, then dispatches via Resend.

**Request body:**
```json
{
  "to": "customer@example.com",
  "subject": "Your invoice is ready",
  "placeholders": {
    "firstName": "Jane",
    "orgName": "Acme Corp",
    "invoiceUrl": "https://..."
  }
}
```

- `to` — required, must be a valid email.
- `subject` — optional override; falls back to the template's own subject.
- `placeholders` — key/value map matching `{{tokens}}` in the HTML.
- `fromName` and `replyTo` fall back to org's branding settings if not set on the template.

**Response `200`:**
```json
{
  "messageId": "resend-msg-id-abc123",
  "to": "customer@example.com",
  "status": "sent"
}
```

**Error `400`:** Missing required fields or Resend API error.

---

## Version History

### GET `/api/email-templates/{id}/versions`
Returns all version snapshots, newest first.

### GET `/api/email-templates/{id}/versions/{versionId}`
Returns a specific version.

### POST `/api/email-templates/{id}/versions/{versionId}/restore`
Restores a previous version as the current template.

---

## Placeholder Substitution

Tokens in the template HTML use double-brace syntax:
```html
<p>Hi {{firstName}},</p>
<p>Your plan at {{orgName}} is now active.</p>
```

The `placeholders` map in the send request replaces each `{{token}}`:
```json
{ "firstName": "Jane", "orgName": "ACME" }
```

Unmatched tokens are left in place.

---

## Data Model

### EmailTemplate (`email_templates`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `name` | String | Template display name |
| `organizationId` | String | Owning org |
| `subject` | String | Email subject (supports tokens) |
| `previewText` | String | Email preview snippet |
| `fromName` | String | Sender display name |
| `htmlContent` | String | GrapesJS-rendered HTML |
| `cssContent` | String | |
| `gjsData` | String | Full GrapesJS project JSON |
| `placeholders` | `List<String>` | Declared variable names |
| `currentVersion` | int | Auto-incremented on updates |
| `deleted` | boolean | Soft delete |
| `createdAt` | LocalDateTime | Auto |
| `updatedAt` | LocalDateTime | Auto |

### EmailTemplateVersion (`email_template_versions`)
Full snapshot of the template at each save. Fields mirror `EmailTemplate` plus `versionNumber`.

---

## Email Dispatch Infrastructure

Email is sent via the **Resend Java SDK** (`com.resend:resend-java:LATEST`).

The `EmailDispatcher` (in `config/infra/email/`) handles:
- Resend API client initialization
- Error propagation as `RuntimeException` on send failure
- Org branding fallback for `fromName` and `replyTo`

---

## Related
- [PDF Templates](./07-pdf-templates.md) — PDF equivalent
- [E-Sign](./09-esign.md) — E-sign notifications use this infrastructure
- [Branding](./14-branding.md) — `fromName` and `replyTo` defaults
- [Template Sharing](./13-template-sharing.md) — Share email templates cross-org
