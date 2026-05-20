# Quota & Usage

Per-organisation resource limits with real-time enforcement and monthly usage history.

**Base path:** `/api/organizations/{orgId}/quota`  
**Auth:** JWT required. `PLATFORM_ADMIN` can manage any org; `ORG_ADMIN` can only read/manage their own.

---

## Endpoints

### GET `/api/organizations/{orgId}/quota/config`
Returns the current quota limits alongside live usage values for the current month.

**Response `200`:**
```json
{
  "organizationId": "org456",
  "subscriptionPlan": "PROFESSIONAL",
  "maxUsers": 25,
  "maxDocsPerMonth": 500,
  "maxStorageMb": 5120,
  "maxApiCallsPerMonth": 10000,
  "currentUsage": {
    "users": 8,
    "docsThisMonth": 143,
    "storageMb": 1240,
    "apiCallsThisMonth": 3200
  }
}
```

A limit of `-1` means **unlimited**.

---

### PUT `/api/organizations/{orgId}/quota/config`
Overrides quota limits independently of the subscription plan defaults. `PLATFORM_ADMIN` only.

**Request body:**
```json
{
  "maxUsers": 50,
  "maxDocsPerMonth": 1000,
  "maxStorageMb": 10240,
  "maxApiCallsPerMonth": 20000
}
```

Send `-1` for any field to make that dimension unlimited.

---

### GET `/api/organizations/{orgId}/quota/usage`
Returns monthly usage history.

**Query param:** `months` (int, default 6)

**Response `200`:**
```json
[
  {
    "year": 2026,
    "month": 5,
    "docsGenerated": 143,
    "esignSent": 47,
    "storageMb": 1240,
    "apiCalls": 3200
  }
]
```

Results are in reverse-chronological order (newest first).

---

## Quota Enforcement

`QuotaService` is called before quota-consuming operations:

| Operation | Quota dimension checked |
|-----------|------------------------|
| `POST /api/generate-pdf` | `maxDocsPerMonth` (docs generated) |
| `POST /api/esign/documents/{id}/send` | `maxDocsPerMonth` (e-sign sent) |
| `POST /api/files/upload` | `maxStorageMb` |
| External API calls | `maxApiCallsPerMonth` |
| `POST /api/users` | `maxUsers` |

When a limit is exceeded, `QuotaExceededException` is thrown → HTTP `429` response:
```json
{
  "status": 429,
  "message": "Monthly document quota exceeded",
  "quotaType": "DOCS_PER_MONTH",
  "limit": 500,
  "current": 500,
  "timestamp": "2026-05-20T10:00:00Z"
}
```

---

## Subscription Plan Defaults

Quota limits are automatically reset when a plan is assigned:

| Plan | Max Users | Docs/Month | Storage (MB) | API Calls/Month |
|------|-----------|------------|--------------|-----------------|
| `FREE` | 3 | 50 | 512 | 1,000 |
| `PROFESSIONAL` | 25 | 500 | 5,120 | 10,000 |
| `ENTERPRISE` | Unlimited | Unlimited | Unlimited | Unlimited |

After assignment, limits can be individually overridden by a Platform Admin.

---

## Background Scheduler

`QuotaCleanupScheduler` runs **daily at 03:00** and purges `OrgUsage` records older than the configured retention window (default 24 months, configurable via `quota.history-retention-months`).

---

## Data Models

### OrgQuotaConfig (`org_quota_configs`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `organizationId` | String | Unique indexed |
| `maxUsers` | int | -1 = unlimited |
| `maxDocsPerMonth` | int | -1 = unlimited |
| `maxStorageMb` | long | -1 = unlimited |
| `maxApiCallsPerMonth` | int | -1 = unlimited |
| `updatedBy` | String | Email of last PA to modify |
| `updatedAt` | LocalDateTime | Auto |

### OrgUsage (`org_usage`)

One document per `(orgId, year, month)` triple. Upserted atomically with MongoDB `$inc`.

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `organizationId` | String | Indexed |
| `year` | int | Calendar year (e.g. 2026) |
| `month` | int | Calendar month (1–12) |
| `docsGenerated` | long | PDF generations this month |
| `esignSent` | long | E-sign invitations sent |
| `storageMb` | long | Cumulative storage (not monthly delta) |
| `apiCalls` | long | External API calls |

---

## Notes

- Quota counters are incremented atomically with `MongoTemplate` `$inc` — safe under concurrent requests.
- `storageMb` is a running total (not reset monthly), reflecting current storage consumption.
- Plan expiry causes `QuotaService` to fall back to `FREE` limits.
