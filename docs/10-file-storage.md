# File Storage

Upload, retrieve, and manage files in the organisation's configured cloud storage (AWS S3, Azure Blob, or Google Cloud Storage).

**Base path:** `/api/files`  
**Auth:** JWT required or API Key with `FILE_STORAGE` feature.  
**Feature flag:** `FILE_STORAGE` must be enabled for the org.  
**Prerequisite:** Cloud storage must be configured via [Cloud Config](./15-cloud-config.md).

---

## Endpoints

### GET `/api/files`
Lists all active files for the caller's org, with optional filtering.

**Query params:**

| Param | Description |
|-------|-------------|
| `folder` | Filter by folder path |
| `documentType` | Filter by document type |
| `page` | Page number (0-based) |
| `size` | Page size |

**Response `200`:** Paginated `FileListResponse`

---

### POST `/api/files/upload`
Uploads a file to the org's cloud storage.

**Request:** `multipart/form-data`

| Field | Required | Description |
|-------|----------|-------------|
| `file` | Yes | The file bytes |
| `folder` | No | Virtual folder path (e.g. `invoices/2026`) |
| `documentType` | No | Business classification |
| `description` | No | Free-text description |
| `tags` | No | Comma-separated tags |
| `documentExpiryDate` | No | `yyyy-MM-dd` |

**Response `200`:**
```json
{
  "fileId": "F2026052000000001",
  "storageKey": "braify/org456/F2026052000000001/contract.pdf",
  "originalFilename": "contract.pdf",
  "contentType": "application/pdf",
  "fileSizeBytes": 204800,
  "fileSizeMb": 0.195,
  "downloadUrl": "https://s3.amazonaws.com/...",
  "expiresIn": 3600
}
```

- File ID format: `F<yyyyMMdd><8-digit-seq>` (e.g. `F2026052000000001`), globally unique, daily-resetting sequence.
- Increments org's `storageMb` usage counter.

---

### GET `/api/files/{fileId}`
Returns metadata for a specific file.

---

### GET `/api/files/{fileId}/download`
Generates a time-limited pre-signed download URL.

**Query param:** `expiresIn` (seconds, default from org's cloud config setting)

**Response `200`:**
```json
{
  "downloadUrl": "https://s3.amazonaws.com/bucket/key?X-Amz-Expires=3600&...",
  "expiresIn": 3600,
  "filename": "contract.pdf"
}
```

---

### PUT `/api/files/{fileId}`
Updates file metadata (description, tags, folder, documentType, documentExpiryDate). Does not re-upload the file.

---

### DELETE `/api/files/{fileId}`
Soft-deletes the file record (`status = DELETED`). The object in cloud storage is **not** removed unless the org has a retention policy configured.

---

### POST `/api/files/{fileId}/archive`
Transitions file status to `ARCHIVED`.

---

## Admin Endpoints

### GET `/api/admin/files`
`PLATFORM_ADMIN` only. Lists files across all orgs.

### GET `/api/admin/files/storage-stats`
Returns per-org storage usage summary.

---

## External API (API Key)

### POST `/api/external/files/upload`
Upload a file using an API key with `FILE_STORAGE` feature.

### GET `/api/external/files/{fileId}/download`
Generate a download URL using an API key.

---

## Document Types

`CONTRACT` · `INVOICE` · `REPORT` · `RECEIPT` · `CERTIFICATE` · `IDENTITY` · `POLICY` · `AGREEMENT` · `PRESENTATION` · `SPREADSHEET` · `IMAGE` · `VIDEO` · `AUDIO` · `OTHER`

---

## Data Model

### OrgFile (`org_files`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `fileId` | String | Human-readable `F<yyyyMMdd><seq>` (unique) |
| `organizationId` | String | Owning org |
| `uploadedBy` | String | Email or `api-key:<keyPrefix>` |
| `originalFilename` | String | |
| `storageKey` | String | Cloud object path |
| `bucket` | String | Cloud bucket / container |
| `cloudProvider` | Enum | `AWS` \| `AZURE` \| `GCP` |
| `contentType` | String | MIME type |
| `fileSizeBytes` | long | |
| `fileSizeMb` | double | Denormalised for quota queries |
| `folder` | String | Virtual folder |
| `documentType` | Enum | Business classification |
| `documentExpiryDate` | LocalDate | Optional |
| `description` | String | |
| `tags` | `List<String>` | |
| `status` | Enum | `ACTIVE` \| `ARCHIVED` \| `DELETED` |
| `downloadCount` | long | Pre-signed URL generation count |
| `createdAt` | LocalDateTime | Auto |
| `updatedAt` | LocalDateTime | Auto |
| `deletedAt` | LocalDateTime | Set on soft-delete |

---

## Cloud Providers

| Provider | Implementation |
|----------|---------------|
| AWS S3 | `AwsS3Uploader` |
| Azure Blob Storage | `AzureBlobUploader` |
| Google Cloud Storage | `GcpStorageUploader` |

The correct uploader is resolved via `CloudUploaderFactory` based on the org's `OrgCloudConfig.cloud` value.

---

## File ID Generation

`FileIdGenerator` uses MongoDB atomic `$inc` on a per-day counter:
```
F<yyyyMMdd><zero-padded-8-digit-sequence>
e.g. F2026052000000001
```
Counter resets each calendar day. Prevents duplicates under concurrent uploads.

---

## Related
- [Cloud Config](./15-cloud-config.md) — Configure cloud credentials
- [Quota & Usage](./12-quota-usage.md) — Storage quota enforcement
