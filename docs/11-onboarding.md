# Onboarding

Self-service organisation registration queue. New customers submit a request via the public "Get Started" form. A Platform Admin reviews and approves it — automatically creating the org and sending an invite to the first admin user.

**Base path:** `/api/onboarding`  
**Auth:** Submit endpoint is public. All others require `PLATFORM_ADMIN` JWT.

---

## Endpoints

### POST `/api/onboarding`
Submits a new onboarding request from the public sign-up form. **No auth required.**

**Request body:**
```json
{
  "applicantName": "Jane Doe",
  "applicantEmail": "jane@acme.com",
  "organizationName": "Acme Corp",
  "description": "Healthcare document automation",
  "address": "123 Main St",
  "state": "California",
  "region": "West",
  "country": "USA",
  "requestedFeatures": ["PDF_TEMPLATES", "E_SIGN"]
}
```

- `applicantEmail` must be unique — duplicate submissions are rejected.
- `requestedFeatures` is a hint; the PA may grant different features on approval.

**Response `200`:**
```json
{
  "success": true,
  "message": "Your application has been received. We'll be in touch shortly.",
  "id": "req123"
}
```

---

### GET `/api/onboarding`
Returns all requests, optionally filtered by status.

**Query param:** `status` (`PENDING` \| `APPROVED` \| `REJECTED` \| `INFO_REQUIRED`)

---

### GET `/api/onboarding/count/pending`
Returns count of pending requests — used for the badge in the admin sidebar.

**Response `200`:** `{ "count": 7 }`

---

### GET `/api/onboarding/{id}`
Returns a single request by ID.

---

### PUT `/api/onboarding/{id}/review`
Reviews a request — approve, reject, or request more information.

**Request body:**
```json
{
  "action": "APPROVE",
  "note": "Approved. Welcome to Braify!",
  "approvedFeatures": ["PDF_TEMPLATES", "E_SIGN", "EMAIL_TEMPLATES"]
}
```

**Actions:**

| Action | Outcome |
|--------|---------|
| `APPROVE` | Creates the Organisation + ORG_ADMIN user; sends invite email to applicant |
| `REJECT` | Marks request as rejected; `note` is stored as rejection reason |
| `INFO_REQUIRED` | Marks request for follow-up; `note` describes what information is needed |

On `APPROVE`:
1. A new `Organization` is created with `approvedFeatures` enabled.
2. An `AppUser` (`ORG_ADMIN`) is created for `applicantEmail`.
3. A quota config is initialised with `FREE` plan defaults.
4. An invitation email is sent to the applicant.
5. `createdOrganizationId` is set on the request.

---

## Request Status Flow

```
PENDING → APPROVED (org + user created, invite sent)
        → REJECTED (with optional note)
        → INFO_REQUIRED (awaiting more details from applicant)
```

---

## Data Model

### OnboardingRequest (`onboarding_requests`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `applicantName` | String | |
| `applicantEmail` | String | Unique indexed |
| `organizationName` | String | |
| `description` | String | |
| `address`, `state`, `region`, `country` | String | Location fields |
| `requestedFeatures` | `List<String>` | Requested features |
| `status` | Enum | `PENDING` \| `APPROVED` \| `REJECTED` \| `INFO_REQUIRED` |
| `reviewNote` | String | PA's note |
| `reviewedBy` | String | PA's email |
| `reviewedAt` | LocalDateTime | |
| `approvedFeatures` | `List<String>` | Features actually granted |
| `createdOrganizationId` | String | Set on approval |
| `submittedAt` | LocalDateTime | Auto |

---

## Related
- [Organization Management](./03-organization-management.md) — org created on approval
- [User Management](./02-user-management.md) — ORG_ADMIN user created on approval
- [Authentication](./01-authentication.md) — invite email sent on approval
