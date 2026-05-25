# Audit Logging

Immutable, tamper-evident activity trail covering all state-changing operations across all features.

**Base path:** `/api/audit-logs`  
**Auth:** JWT required. Results are org-scoped unless caller is `PLATFORM_ADMIN`.

---

## Endpoints

### GET `/api/audit-logs`
Returns paginated audit log entries, newest first.

**Query params:**

| Param | Type | Description |
|-------|------|-------------|
| `page` | int | Page number (0-based, default 0) |
| `size` | int | Page size (default 20, max 100) |
| `action` | String | Filter by action (e.g. `CREATED`, `DELETED`) |
| `resourceType` | String | Filter by resource type (e.g. `TEMPLATE`, `USER`) |
| `performedBy` | String | Filter by email of acting user |
| `from` | String | ISO datetime lower bound |
| `to` | String | ISO datetime upper bound |

**Response `200`:**
```json
{
  "content": [
    {
      "id": "log123",
      "organizationId": "org456",
      "resourceType": "TEMPLATE",
      "templateId": "tmpl789",
      "templateName": "Invoice Template",
      "action": "UPDATED",
      "versionNumber": 3,
      "performedBy": "jane@acme.com",
      "performedByName": "Jane Doe",
      "timestamp": "2026-05-20T09:14:32",
      "severity": "INFO",
      "outcome": "SUCCESS",
      "ipAddress": "192.168.1.10",
      "integrityHash": "sha256hex..."
    }
  ],
  "totalElements": 142,
  "totalPages": 8
}
```

---

## Audit Actions

| Action | Triggered by |
|--------|-------------|
| `CREATED` | New resource created |
| `UPDATED` | Resource updated |
| `DELETED` | Resource soft-deleted |
| `RESTORED` | Soft-delete reversed |
| `READ` | Sensitive read / download |
| `PASSWORD_CHANGED` | User password change |
| `AVATAR_UPDATED` | Profile picture changed |
| `DEACTIVATED` | User deactivated |
| `ACTIVATED` | User re-activated |
| `SESSION_REVOKED` | JWT session revoked |
| `SENT` | E-sign invitation sent |
| `FEATURES_UPDATED` | Org feature list changed |
| `CANCELLED` | E-sign document cancelled |
| `SUBSCRIPTION_CHANGED` | Org plan changed |
| `BRANDING_UPDATED` | Org branding updated |
| `QUOTA_EXCEEDED` | Quota limit hit |
| `TEMPLATE_SHARED` | Template shared with another org |
| `TEMPLATE_UNSHARED` | Share revoked |

## Resource Types

`TEMPLATE` · `EMAIL_TEMPLATE` · `USER` · `ORGANIZATION` · `E_SIGN` · `SHARING` · `API_KEY` · `DOCUMENT`

## Severity

| Level | Actions |
|-------|---------|
| `INFO` | Normal operations (CREATED, UPDATED, READ) |
| `WARNING` | Sensitive changes (PASSWORD_CHANGED, DEACTIVATED, SENT) |
| `CRITICAL` | High-risk changes (DELETED, SUBSCRIPTION_CHANGED, QUOTA_EXCEEDED) |

---

## Data Model

### AuditLog (`audit_logs`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `organizationId` | String | Owning org (indexed) |
| `resourceType` | Enum | Resource classification |
| `templateId` | String | Affected resource ID (legacy field name) |
| `templateName` | String | Resource display name snapshot |
| `action` | Enum | See action table above |
| `versionNumber` | int | Resulting version (0 if N/A) |
| `performedBy` | String | Acting user's email |
| `performedByUserId` | String | Stable user ID snapshot |
| `performedByName` | String | Display name snapshot |
| `changes` | `Map<String, Object>` | Changed fields: `field → {from, to}` |
| `timestamp` | LocalDateTime | Auto (indexed) |
| `ipAddress` | String | Client IP |
| `userAgent` | String | Browser/client agent |
| `sessionId` | String | JWT JTI for session correlation |
| `reason` | String | Optional human justification |
| `severity` | Enum | `INFO` \| `WARNING` \| `CRITICAL` |
| `outcome` | Enum | `SUCCESS` \| `FAILURE` |
| `failureReason` | String | Error detail when outcome = FAILURE |
| `integrityHash` | String | SHA-256 of key fields for tamper detection |

---

## Integrity Verification

Each entry contains a SHA-256 hash computed from:
```
resourceId|action|resourceType|performedBy|organizationId|timestamp
```
Re-compute the hash and compare with `integrityHash` to detect tampering.

---

## Notes

- Audit logs are **append-only** — they are never updated or deleted.
- `changes` is populated only on `UPDATED` actions.
- `PLATFORM_ADMIN` sees all logs; org-scoped users see only their own org.
- The `templateId` field is the resource ID for all resource types (legacy naming).
