# Branding

Organisation-level visual and email identity settings: logo, primary colour, email sender name, reply-to address, and PDF footer text.

**Base path:** `/api/organizations/{orgId}/branding`  
**Auth:** JWT required. `PLATFORM_ADMIN` can manage any org; `ORG_ADMIN` can only manage their own.

---

## Endpoints

### GET `/api/organizations/{orgId}/branding`
Returns the current branding settings for the organisation.

**Response `200`:**
```json
{
  "configured": true,
  "logoBase64": "data:image/png;base64,...",
  "primaryColor": "#6c47ff",
  "emailSenderName": "Braify Support",
  "emailReplyTo": "support@acme.com",
  "footerText": "Acme Corp · 123 Main St · San Francisco, CA",
  "updatedAt": "2026-03-15T09:30:00"
}
```

`configured: false` is returned when no branding has been saved yet.

---

### PUT `/api/organizations/{orgId}/branding`
Replaces all branding fields. All fields are optional — send `null` to clear a field.

**Request body:**
```json
{
  "logoBase64": "data:image/png;base64,...",
  "primaryColor": "#6c47ff",
  "emailSenderName": "Acme Docs",
  "emailReplyTo": "no-reply@acme.com",
  "footerText": "Acme Corp · All rights reserved."
}
```

**Field rules:**
- `logoBase64` — data-URL string, e.g. `data:image/png;base64,...` (max ~2 MB recommended).
- `primaryColor` — hex colour `#rrggbb`. Injected as `--brand-color` CSS variable in generated PDFs.
- `emailSenderName` — display name in the `From` field of outgoing emails.
- `emailReplyTo` — must be valid email format.
- `footerText` — plain text appended to every generated PDF's footer (max 500 chars).

**Response `200`:** Updated branding settings.

---

## How Branding Is Applied

### In PDF Generation
When `PdfGenerationService` renders a PDF:
1. The org's `primaryColor` is injected as a CSS custom property: `--brand-color: #6c47ff`.
2. The `footerText` is appended as a footer element on every page.
3. The `logoBase64` image is optionally embedded if the template references `{{orgLogo}}`.

### In Email Dispatch
When `EmailTemplateService` sends an email:
1. `emailSenderName` is used as the `From` display name (falls back to a system default if not set).
2. `emailReplyTo` is set as the `Reply-To` header.

### In E-Sign Emails
E-sign invitation and completion emails use the same branding fallbacks.

---

## Data Model

### OrgBranding (embedded in `Organization`)

Branding is stored as an **embedded BSON object** inside the `Organization` document — not a separate collection.

| Field | Type | Notes |
|-------|------|-------|
| `logoBase64` | String | Data-URL encoded image |
| `primaryColor` | String | Hex `#rrggbb` |
| `emailSenderName` | String | From display name |
| `emailReplyTo` | String | Reply-To email |
| `footerText` | String | PDF footer (max 500 chars) |
| `updatedAt` | LocalDateTime | |

---

## Related
- [PDF Templates](./07-pdf-templates.md) — `--brand-color` and footer injection
- [Email Templates](./08-email-templates.md) — sender name and reply-to fallback
- [E-Sign](./09-esign.md) — email notification branding
- [Organization Management](./03-organization-management.md) — branding is embedded in the Organization document
