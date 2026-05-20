# Template Sharing

Share PDF and Email templates across organisations with granular permissions. Supports read-only, use, or full EDIT (fork) access.

**Base path:** `/api/sharing`  
**Auth:** JWT required. `PLATFORM_ADMIN` or `ORG_ADMIN`.

---

## Endpoints

### POST `/api/sharing`
Shares a template with a target organisation.

**Required role:** `ORG_ADMIN` (own org's templates only) or `PLATFORM_ADMIN` (any org)

**Request body:**
```json
{
  "templateId": "tmpl123",
  "templateType": "TEMPLATE",
  "targetOrgId": "org789",
  "permission": "USE",
  "note": "Shared for Q2 campaign use"
}
```

**Permission levels:**

| Permission | What the target org can do |
|------------|---------------------------|
| `VIEW` | Preview the template read-only in their library |
| `USE` | Generate PDFs / send emails using the template |
| `EDIT` | A forked copy is created in their org; they can modify it independently |

- Only one active share per `(templateId, targetOrgId)` pair is allowed. Revoke the existing share first to re-share.
- `templateType`: `TEMPLATE` (PDF) or `EMAIL_TEMPLATE`.

**Response `200`:** `SharingResponse`

---

### GET `/api/sharing`
Lists all shares visible to the caller — both shares the org has sent and received.

---

### GET `/api/sharing/received`
Lists shares received by the caller's org (incoming shared templates).

---

### DELETE `/api/sharing/{id}`
Revokes an active share.

- For `EDIT` shares: the forked copy in the target org is **soft-deleted**.
- For `VIEW` / `USE` shares: the target org immediately loses access.

**Required role:** Source org's `ORG_ADMIN` or `PLATFORM_ADMIN`

**Response `204`**

---

## Shared Templates Access

When a template is shared with an org:
- `VIEW`: The template appears in the target org's template list as read-only.
- `USE`: The template can be used for generation but not edited.
- `EDIT`: A full copy is created in the target org (`forked = true`, `sourceTemplateId` set). The copy is fully independent — changes don't propagate back.

---

## Data Model

### SharedTemplate (`shared_templates`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `sourceOrgId` | String | Org that owns the source template |
| `targetOrgId` | String | Org receiving access |
| `templateId` | String | Source template ID |
| `templateType` | Enum | `TEMPLATE` \| `EMAIL_TEMPLATE` |
| `permission` | Enum | `VIEW` \| `USE` \| `EDIT` |
| `sharedBy` | String | Email of user who created the share |
| `sharedByUserId` | String | Stable user ID snapshot |
| `note` | String | Optional message (max 300 chars) |
| `status` | Enum | `ACTIVE` \| `REVOKED` |
| `sharedAt` | LocalDateTime | Auto |
| `revokedAt` | LocalDateTime | Set on revocation |
| `revokedBy` | String | Email of revoker |
| `forkedTemplateId` | String | ID of fork in target org (EDIT only) |

---

## Forking Behaviour

For `EDIT` permission:
1. The source template is deep-copied into the target org.
2. The copy has `forked = true`, `sourceTemplateId`, and `sourceOrgId` set.
3. The `forkedTemplateId` on the `SharedTemplate` record points to this copy.
4. The fork is completely independent — editing either side does not affect the other.
5. On revocation, `forkedTemplateId` is soft-deleted in the target org.

---

## Related
- [PDF Templates](./07-pdf-templates.md) — Templates that can be shared
- [Email Templates](./08-email-templates.md) — Email templates that can be shared
- [Audit Logging](./05-audit-logging.md) — `TEMPLATE_SHARED` and `TEMPLATE_UNSHARED` actions
