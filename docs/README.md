# Braify Web Services — Documentation Index

**Spring Boot 3.2.4 · Java 17 · MongoDB · Multi-tenant SaaS**

Base URL: `http://localhost:8080` (configurable)  
Auth: JWT Bearer token (user sessions) or `brfy_*` API Key header  
All timestamps are UTC `LocalDateTime`.

---

## Feature Modules

| # | Module | Base Path | Description |
|---|--------|-----------|-------------|
| 1 | [Authentication](./01-authentication.md) | `/api/auth` | Login, logout, invite, password reset |
| 2 | [User Management](./02-user-management.md) | `/api/users`, `/api/profile` | CRUD, roles, profile, avatar |
| 3 | [Organization Management](./03-organization-management.md) | `/api/organizations` | Tenants, features, subscriptions |
| 4 | [API Keys](./04-api-keys.md) | `/api/organizations/{orgId}/api-keys` | Per-org programmatic access |
| 5 | [Audit Logging](./05-audit-logging.md) | `/api/audit-logs` | Tamper-proof activity trail |
| 6 | [Dashboard](./06-dashboard.md) | `/api/dashboard` | Aggregated KPIs and analytics |
| 7 | [PDF Templates](./07-pdf-templates.md) | `/api/templates`, `/api/generate-pdf` | Builder, versioning, generation |
| 8 | [Email Templates](./08-email-templates.md) | `/api/email-templates` | HTML templates, dispatch via Resend |
| 9 | [E-Sign](./09-esign.md) | `/api/esign/documents`, `/api/esign/sign` | Full document signing lifecycle |
| 10 | [File Storage](./10-file-storage.md) | `/api/files` | Multi-cloud upload, download, metadata |
| 11 | [Onboarding](./11-onboarding.md) | `/api/onboarding` | Self-service org registration & review |
| 12 | [Quota & Usage](./12-quota-usage.md) | `/api/organizations/{orgId}/quota` | Limits, enforcement, history |
| 13 | [Template Sharing](./13-template-sharing.md) | `/api/sharing` | Cross-org template access |
| 14 | [Branding](./14-branding.md) | `/api/organizations/{orgId}/branding` | Logo, colours, email sender |
| 15 | [Cloud Config](./15-cloud-config.md) | `/api/organizations/{orgId}/cloud-config` | AWS / Azure / GCP credentials |
| 16 | [Sessions](./16-sessions.md) | `/api/sessions` | Active JWT sessions, revocation |

---

## Roles & Access

| Role | Scope |
|------|-------|
| `PLATFORM_ADMIN` | Full access to all organisations and admin endpoints |
| `ORG_ADMIN` | Full access within their own organisation |
| `ADMIN` | Manages users and content within their org |
| `USER` | Read/use access within their org |

---

## Authentication

Every protected endpoint requires:
```
Authorization: Bearer <jwt-token>
```

External API endpoints accept:
```
X-Api-Key: brfy_<key>
```

---

## Error Format

All errors return a consistent JSON body:

```json
{
  "status": 400,
  "message": "Validation failed",
  "errors": { "email": "must not be blank" },
  "timestamp": "2026-05-20T10:23:45Z"
}
```

| Status | Meaning |
|--------|---------|
| 400 | Validation error / bad request |
| 401 | Missing or invalid credentials |
| 403 | Insufficient role |
| 404 | Resource not found |
| 429 | Quota exceeded |
| 500 | Unexpected server error |

---

## MongoDB Collections

| Collection | Feature |
|------------|---------|
| `users` | User accounts |
| `organizations` | Tenant orgs (embeds branding + cloud config) |
| `templates` | PDF templates |
| `template_versions` | PDF template snapshots |
| `email_templates` | Email templates |
| `email_template_versions` | Email template snapshots |
| `esign_documents` | E-sign document lifecycle |
| `esign_signature_fields` | Field placements per document |
| `esign_signing_tokens` | Short-lived ESIGN JWTs |
| `esign_audit_events` | Immutable e-sign event log |
| `org_api_keys` | Hashed API keys |
| `org_files` | File metadata |
| `org_usage` | Monthly usage counters |
| `org_quota_configs` | Per-org quota limits |
| `audit_logs` | System-wide audit trail |
| `user_sessions` | Active JWT sessions |
| `invitation_tokens` | Invite + password-reset tokens |
| `onboarding_requests` | Self-service sign-up queue |
| `shared_templates` | Cross-org template shares |
| `file_id_counters` | Daily file ID sequences |
