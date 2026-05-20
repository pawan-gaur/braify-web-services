# Cloud Configuration

Manage organisation-level cloud storage credentials (AWS S3, Azure Blob, GCP) for file upload and download. Sensitive credentials are encrypted at rest using AES-256-GCM.

**Base path:** `/api/organizations/{orgId}/cloud-config`  
**Auth:** JWT required. `PLATFORM_ADMIN` can manage any org; `ORG_ADMIN` can only manage their own.

---

## Endpoints

### GET `/api/organizations/{orgId}/cloud-config`
Returns the current cloud configuration. Credential fields are always **masked** in the response.

**Response `200`:**
```json
{
  "configured": true,
  "cloud": "AWS",
  "bucket": "my-org-documents",
  "path": "braify",
  "module": "files",
  "accessKey": "AKIA****5678",
  "secretKey": "wJal****rXUt",
  "awsRegion": "us-east-1",
  "allowedFileTypes": ["pdf", "jpg", "png"],
  "maxUploadSizeMb": 50,
  "retentionDays": 365,
  "presignedUrlExpiration": 60,
  "status": "ACTIVE",
  "createdAt": "2026-01-10T09:00:00",
  "updatedAt": "2026-05-01T14:00:00"
}
```

`configured: false` when no config has been saved.

Masking format: first 4 + `****` + last 4 chars. Strings ≤ 8 chars are fully masked as `****`.

---

### PUT `/api/organizations/{orgId}/cloud-config`
Replaces all cloud config fields.

**Request body:**
```json
{
  "cloud": "AWS",
  "bucket": "my-org-documents",
  "path": "braify",
  "accessKey": "AKIAIOSFODNN7EXAMPLE",
  "secretKey": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  "awsRegion": "us-east-1",
  "allowedFileTypes": ["pdf", "jpg", "png", "docx"],
  "maxUploadSizeMb": 50,
  "retentionDays": 365,
  "presignedUrlExpiration": 60
}
```

**Field rules:**
- `cloud` — `AWS`, `AZURE`, or `GCP`.
- `accessKey` / `secretKey` — plaintext values are encrypted before storage. Send `null` to keep the existing value unchanged. Send `""` (empty string) to clear.
- `maxUploadSizeMb` — must be > 0 when provided.
- `presignedUrlExpiration` — minutes; must be > 0 when provided.
- `retentionDays` — must be > 0 when provided.
- `allowedFileTypes` — lowercase extensions without dots (e.g. `["pdf", "jpg"]`).

**Response `200`:** Updated config with credentials masked.

---

### POST `/api/organizations/{orgId}/cloud-config/test`
Tests the stored credentials by attempting a lightweight cloud provider connectivity check (pre-signed URL generation).

**Response `200`:**
```json
{
  "success": true,
  "message": "Connectivity test passed — credentials accepted by AWS."
}
```
or
```json
{
  "success": false,
  "message": "Connectivity test failed: The security token included in the request is invalid."
}
```

Does not require a request body. Uses the stored (decrypted) credentials.

---

## Credential Security

| Layer | Mechanism |
|-------|-----------|
| Storage | AES-256-GCM encryption via `EncryptionService` |
| API responses | Masked (first 4 + `****` + last 4 chars) |
| Keep-existing | Sending `null` for a credential field preserves the existing encrypted value |
| Clear field | Send `""` (empty string) to explicitly clear a credential |

The encryption key is configured via `app.encryption.key` in `application.yml`.

---

## Provider-Specific Notes

### AWS S3
- `accessKey` = AWS Access Key ID
- `secretKey` = AWS Secret Access Key
- `awsRegion` = region code (e.g. `us-east-1`)
- `bucket` = S3 bucket name

### Azure Blob Storage
- `accessKey` = Azure Storage connection string (entire string)
- `bucket` = container name

### GCP Cloud Storage
- `accessKey` = GCP service account JSON (entire JSON string)
- `bucket` = GCS bucket name

---

## Data Model

### OrgCloudConfig (embedded in `Organization`)

Cloud config is stored as an **embedded BSON object** in the `Organization` document.

| Field | Type | Notes |
|-------|------|-------|
| `cloud` | Enum | `AWS` \| `AZURE` \| `GCP` |
| `bucket` | String | Bucket / container name |
| `path` | String | Object key prefix |
| `module` | String | Module sub-path |
| `accessKey` | String | AES-256-GCM encrypted ciphertext |
| `secretKey` | String | AES-256-GCM encrypted ciphertext |
| `awsRegion` | String | AWS region code |
| `allowedFileTypes` | `List<String>` | Permitted extensions |
| `maxUploadSizeMb` | Integer | Upload size cap |
| `retentionDays` | Integer | Object lifetime |
| `presignedUrlExpiration` | Integer | URL validity in minutes |
| `status` | Enum | `ONBOARD` \| `ACTIVE` \| `ERROR` |
| `createdAt` | LocalDateTime | |
| `updatedAt` | LocalDateTime | |

---

## Related
- [File Storage](./10-file-storage.md) — Uses cloud config for uploads/downloads
- [Organization Management](./03-organization-management.md) — Cloud config is embedded in the org
