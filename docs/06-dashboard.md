# Dashboard

Aggregated KPIs, analytics, and trend data for the authenticated user's organisation.

**Base path:** `/api/dashboard`  
**Auth:** JWT required.

---

## Endpoints

### GET `/api/dashboard`
Returns a single `DashboardStats` object containing all metrics for the caller's scope.

- `PLATFORM_ADMIN` — cross-org aggregated data + per-org breakdown
- All other roles — data scoped to their own organisation

**Response `200`:** `DashboardStats` (see model below)

---

## DashboardStats Fields

### KPI Summary Cards

| Field | Type | Description |
|-------|------|-------------|
| `totalOrganizations` | long | Active orgs (PA: all; others: always 1) |
| `totalUsers` | long | Active users visible to caller |
| `totalPdfTemplates` | long | Non-deleted PDF templates |
| `totalEmailTemplates` | long | Non-deleted email templates |
| `pendingInvites` | long | Users with `mustChangePassword = true` |

### E-Sign Analytics

| Field | Type | Description |
|-------|------|-------------|
| `esignTotal` | long | All documents (all statuses) |
| `esignDraft` | long | Documents in DRAFT |
| `esignPending` | long | Documents in PENDING or IN_REVIEW |
| `esignCompleted` | long | Documents in COMPLETED |
| `esignViewed` | long | LINK_OPENED audit events (proxy for recipient views) |
| `esignOverdue` | long | PENDING/IN_REVIEW documents past token expiry |
| `esignCancelled` | long | CANCELLED documents |
| `esignExpired` | long | EXPIRED documents |
| `esignAvgSigningHours` | Double | Avg hours from `sentAt` → `completedAt` (null if no data) |
| `esignDeclineRate` | Double | `cancelled / sent` as a percentage (null if no sent docs) |
| `esignGrowth` | `List<MonthStat>` | Monthly count of documents sent, last 6 months |

### Monthly Trends (last 6 calendar months)

| Field | Type | Description |
|-------|------|-------------|
| `pdfGrowth` | `List<MonthStat>` | PDF template creations per month |
| `emailGrowth` | `List<MonthStat>` | Email template creations per month |
| `userGrowth` | `List<MonthStat>` | User sign-ups per month |

### Team Activity

| Field | Type | Description |
|-------|------|-------------|
| `recentActivity` | `List<AuditLog>` | Last 10 audit events |
| `topUsers` | `List<TopUser>` | Top 5 most active users, last 30 days |

### Platform Admin Only

| Field | Type | Description |
|-------|------|-------------|
| `orgBreakdown` | `List<OrgSummary>` | Per-org summary table |
| `activeOrganizations` | long | Active (non-deleted) org count |
| `inactiveOrganizations` | long | Inactive org count |
| `pendingOnboarding` | long | Pending onboarding requests |
| `featureDistribution` | `Map<String,Long>` | Feature adoption counts across active orgs |
| `tenantGrowth` | `List<MonthStat>` | New orgs per month, last 6 months |

---

## Inner Types

### MonthStat
```json
{ "label": "Jan '26", "count": 42 }
```

### OrgSummary
```json
{
  "organizationId": "org456",
  "organizationName": "Acme Corp",
  "features": ["PDF_TEMPLATES", "E_SIGN"],
  "users": 12,
  "pdfTemplates": 8,
  "emailTemplates": 3,
  "esignDocuments": 47
}
```

### TopUser
```json
{
  "email": "jane@acme.com",
  "name": "Jane Doe",
  "activityCount": 83
}
```

---

## Example Response (trimmed)
```json
{
  "totalOrganizations": 1,
  "totalUsers": 8,
  "totalPdfTemplates": 12,
  "totalEmailTemplates": 4,
  "pendingInvites": 1,
  "esignTotal": 34,
  "esignDraft": 2,
  "esignPending": 5,
  "esignCompleted": 24,
  "esignViewed": 31,
  "esignOverdue": 1,
  "esignCancelled": 2,
  "esignExpired": 1,
  "esignAvgSigningHours": 18.4,
  "esignDeclineRate": 5.9,
  "esignGrowth": [
    { "label": "Dec '25", "count": 3 },
    { "label": "Jan '26", "count": 7 }
  ],
  "recentActivity": [...],
  "topUsers": [...]
}
```

---

## Notes

- All data is computed on-the-fly from multiple collections — no cached aggregate store.
- `esignViewed` is derived from `LINK_OPENED` events in `esign_audit_events`.
- Monthly trend windows are aligned to calendar months, not rolling 30-day windows.
