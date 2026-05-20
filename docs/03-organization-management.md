# Organization Management

CRUD, feature management, and subscription assignment for tenant organisations.

**Base path:** `/api/organizations`  
**Auth:** JWT required. `PLATFORM_ADMIN` only unless noted.

---

## Endpoints

### GET `/api/organizations`
Returns all organisations (active and inactive, non-deleted).

### GET `/api/organizations/search?q=`
Full-text search on name and code.

### GET `/api/organizations/{id}`
Returns a single organisation by ID.

### POST `/api/organizations`
Creates a new tenant organisation.

**Request body:**
```json
{
  "name": "Acme Corp",
  "code": "acme-corp",
  "description": "Healthcare division"
}
```
- `code` must be unique and is immutable after creation.

**Response `200`:** `Organization`

### PUT `/api/organizations/{id}`
Updates name, description, and active status.

**Request body:**
```json
{
  "name": "Acme Corp (Updated)",
  "description": "Updated description",
  "active": true
}
```

### DELETE `/api/organizations/{id}`
Soft-deletes an organisation. Users and data are retained.

---

## Feature Management

### GET `/api/organizations/{id}/features`
Returns the list of currently enabled feature keys.

**Response `200`:**
```json
{ "features": ["PDF_TEMPLATES", "E_SIGN", "EMAIL_TEMPLATES"] }
```

### PUT `/api/organizations/{id}/features`
Replaces the entire feature list. Send empty array to disable all features.

**Request body:**
```json
{ "features": ["PDF_TEMPLATES", "E_SIGN", "EMAIL_TEMPLATES", "FILE_STORAGE"] }
```

**Available feature keys:**

| Key | Description |
|-----|-------------|
| `PDF_TEMPLATES` | PDF builder and generation |
| `EMAIL_TEMPLATES` | Email template builder and dispatch |
| `E_SIGN` | Electronic document signing |
| `FILE_STORAGE` | Cloud file upload and management |

---

## Subscription Management

### GET `/api/organizations/{id}/subscription`
Returns the current subscription plan and its default quota values.

**Response `200`:**
```json
{
  "subscriptionPlan": "PROFESSIONAL",
  "planAssignedAt": "2026-01-15T10:00:00",
  "planAssignedBy": "admin@braify.com",
  "planExpiresAt": null,
  "defaults": {
    "maxUsers": 25,
    "maxDocsPerMonth": 500,
    "maxStorageMb": 5120,
    "maxApiCallsPerMonth": 10000
  }
}
```

### PUT `/api/organizations/{id}/subscription`
Assigns a new subscription plan. Resets quota limits to plan defaults.

**Request body:**
```json
{
  "subscriptionPlan": "PROFESSIONAL",
  "planExpiresAt": "2027-01-01T00:00:00"
}
```
- `planExpiresAt` null = no expiry.
- After expiry, `QuotaService` falls back to `FREE` limits.

**Subscription tiers:**

| Plan | Max Users | Docs/Month | Storage | API Calls/Month |
|------|-----------|------------|---------|-----------------|
| `FREE` | 3 | 50 | 512 MB | 1,000 |
| `PROFESSIONAL` | 25 | 500 | 5,120 MB | 10,000 |
| `ENTERPRISE` | Unlimited | Unlimited | Unlimited | Unlimited |

---

## Admin Detail Page

### GET `/api/admin/organizations/{orgId}`
Returns extended admin view of an organisation including users, usage, and quota.

---

## Data Model

### Organization (`organizations`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `name` | String | Display name |
| `code` | String | Unique short slug (immutable) |
| `description` | String | Optional |
| `features` | `List<String>` | Enabled feature keys |
| `subscriptionPlan` | Enum | `FREE` \| `PROFESSIONAL` \| `ENTERPRISE` |
| `planAssignedAt` | LocalDateTime | |
| `planAssignedBy` | String | Email |
| `planExpiresAt` | LocalDateTime | Null = no expiry |
| `branding` | Embedded | See [Branding](./14-branding.md) |
| `cloudConfig` | Embedded | See [Cloud Config](./15-cloud-config.md) |
| `active` | boolean | |
| `deleted` | boolean | Soft delete flag |
| `deletedAt` | LocalDateTime | |
| `createdAt` | LocalDateTime | Auto |
| `updatedAt` | LocalDateTime | Auto |

---

## Related
- [Quota & Usage](./12-quota-usage.md) — per-org limits
- [Branding](./14-branding.md) — logo and colours
- [Cloud Config](./15-cloud-config.md) — storage credentials
- [Onboarding](./11-onboarding.md) — self-service org creation flow
