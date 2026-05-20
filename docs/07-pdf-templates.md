# PDF Templates

Build, version, and generate PDF documents from HTML/CSS templates with dynamic placeholder substitution.

**Base paths:** `/api/templates`, `/api/generate-pdf`, `/api/preview-pdf`  
**Auth:** JWT required. Feature flag `PDF_TEMPLATES` must be enabled for the org.

---

## Endpoints

### GET `/api/templates`
Lists all non-deleted templates for the caller's org.

**Response `200`:** `List<Template>`

---

### GET `/api/templates/{id}`
Returns a single template by ID. Org-scoped.

---

### POST `/api/templates`
Creates a new template.

**Request body:**
```json
{
  "name": "Invoice Template",
  "description": "Monthly client invoice",
  "htmlContent": "<html><body><h1>Invoice {{invoiceNumber}}</h1>...</body></html>",
  "cssContent": "body { font-family: Arial; }",
  "gjsData": "{ \"components\": [...] }",
  "pageSize": "A4",
  "orientation": "portrait",
  "marginTop": 20,
  "marginBottom": 20,
  "marginLeft": 15,
  "marginRight": 15,
  "placeholders": ["invoiceNumber", "customerName", "amount"]
}
```

- `gjsData` — GrapesJS full project JSON, used to re-open the template in the builder.
- `placeholders` — extracted variable names matching `{{varName}}` tokens in HTML.
- Page size options: `A4`, `LETTER`.
- Orientation: `portrait`, `landscape`.

**Response `200`:** `Template`

---

### PUT `/api/templates/{id}`
Updates a template. Automatically creates a new version snapshot before saving.

**Request body:** same shape as POST.

**Response `200`:** `Template`

---

### DELETE `/api/templates/{id}`
Soft-deletes the template. Version history is retained.

**Response `204`**

---

### GET `/api/templates/{id}/versions`
Returns all version snapshots, newest first.

**Response `200`:** `List<TemplateVersion>`

### GET `/api/templates/{id}/versions/{versionId}`
Returns a specific version snapshot.

### POST `/api/templates/{id}/versions/{versionId}/restore`
Restores a previous version as the current template content.

---

## PDF Generation

### POST `/api/generate-pdf`
Renders the template with supplied data and returns a downloadable PDF.

**Quota enforced** — increments `docsGenerated` counter.

**Request body:**
```json
{
  "templateId": "tmpl123",
  "data": {
    "invoiceNumber": "INV-2026-001",
    "customerName": "ACME Ltd",
    "amount": "1,500.00"
  },
  "filename": "invoice-001.pdf"
}
```

**Response `200`:**
- Content-Type: `application/pdf`
- Content-Disposition: `attachment; filename="invoice-001.pdf"`
- Body: raw PDF bytes

**Error `429`:** Monthly document quota exceeded.

---

### POST `/api/preview-pdf`
Same rendering as generate — but returns the PDF `inline` (browser preview) and does **not** increment the quota counter.

**Request body:** same as generate-pdf (`filename` ignored).

**Response `200`:** `Content-Disposition: inline; filename="preview.pdf"`

---

## Placeholder Substitution

Templates use `{{variableName}}` tokens in `htmlContent`:
```html
<p>Dear {{customerName}},</p>
<p>Invoice #{{invoiceNumber}} for {{amount}}</p>
```

The `data` map in the generate request provides the values:
```json
{ "customerName": "Jane", "invoiceNumber": "INV-001", "amount": "$500" }
```

Unmatched tokens are left as-is (not removed).

---

## Org Branding in PDFs

When a PDF is generated, the service:
1. Resolves the org's `OrgBranding` settings.
2. Injects `--brand-color` CSS custom property.
3. Appends the org's `footerText` to every page.
4. Optionally embeds the org logo.

---

## Data Model

### Template (`templates`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `name` | String | |
| `organizationId` | String | Owning org |
| `description` | String | |
| `htmlContent` | String | GrapesJS HTML |
| `cssContent` | String | GrapesJS CSS |
| `gjsData` | String | Full GrapesJS project JSON |
| `pageSize` | String | `A4` \| `LETTER` |
| `orientation` | String | `portrait` \| `landscape` |
| `marginTop/Bottom/Left/Right` | int | mm |
| `placeholders` | `List<String>` | Extracted variable names |
| `currentVersion` | int | Incremented on each save |
| `deleted` | boolean | Soft delete |
| `deletedAt` | LocalDateTime | |
| `forked` | boolean | True if created by template sharing |
| `sourceTemplateId` | String | Origin template (if forked) |
| `sourceOrgId` | String | Origin org (if forked) |
| `createdAt` | LocalDateTime | Auto |
| `updatedAt` | LocalDateTime | Auto |

### TemplateVersion (`template_versions`)

A full snapshot of the template HTML/CSS/gjsData at each save. Fields mirror `Template` plus `versionNumber`.

---

## PDF Engine

Uses **OpenHTMLtoPDF** (LGPL) with Apache PDFBox for rendering.
- SVG support enabled via `openhtmltopdf-svg-support` (QR codes, images).
- SLF4J logging bridge for OpenHTMLtoPDF noise suppression.

---

## Related
- [Email Templates](./08-email-templates.md) — HTML email templates
- [E-Sign](./09-esign.md) — Use PDF templates as e-sign document bases
- [Template Sharing](./13-template-sharing.md) — Cross-org sharing
- [Quota & Usage](./12-quota-usage.md) — Generation quota enforcement
