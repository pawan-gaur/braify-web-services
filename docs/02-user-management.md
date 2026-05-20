# User Management

CRUD operations for users, profile editing, password change, and avatar upload.

**Base paths:** `/api/users`, `/api/profile`  
**Auth:** JWT required. Role restrictions per endpoint.

---

## Endpoints

### GET `/api/users`
Lists all users visible to the caller.
- `PLATFORM_ADMIN` → all users across all orgs
- `ORG_ADMIN` / `ADMIN` → users in their org only

**Response `200`:** Array of `UserResponse`.

---

### GET `/api/users/search?q=&orgId=`
Full-text search on name and email, with optional org filter.

| Param | Required | Description |
|-------|----------|-------------|
| `q` | No | Search string (default empty = return all) |
| `orgId` | No | Filter to specific org (PLATFORM_ADMIN only) |

---

### GET `/api/users/{id}`
Returns a single user by ID. Respects org-scope visibility.

---

### POST `/api/users`
Creates a new user and optionally sends an email invitation.

**Required role:** `PLATFORM_ADMIN`, `ORG_ADMIN`, or `ADMIN`

**Request body:**
```json
{
  "email": "jane@acme.com",
  "firstName": "Jane",
  "lastName": "Doe",
  "role": "ADMIN",
  "organizationId": "org456",
  "sendInvite": true
}
```

- `password` is optional — if omitted, `sendInvite: true` triggers an email with a set-password link.
- `role` values: `ORG_ADMIN`, `ADMIN`, `USER`.
- `organizationId` required unless caller is `PLATFORM_ADMIN` creating another PA.

**Response `200`:** `UserResponse`

---

### PUT `/api/users/{id}`
Updates a user's name, role, or org assignment.

**Request body:** same shape as POST (all fields optional on update).

---

### DELETE `/api/users/{id}`
Soft-deactivates a user (`active = false`). Does not delete the record.

**Response `204`**

---

### PUT `/api/users/{id}/enable`
Re-activates a previously deactivated user.

---

### POST `/api/users/{id}/resend-invite`
Resends the invitation email for a user who hasn't yet set their password.

---

## Profile Endpoints

### GET `/api/profile`
Returns the authenticated user's profile.

### PUT `/api/profile`
Updates first name, last name, and bio.

**Request body:**
```json
{
  "firstName": "Jane",
  "lastName": "Smith",
  "bio": "Senior Engineer"
}
```

### POST `/api/profile/change-password`
Changes the authenticated user's own password.

**Request body:**
```json
{
  "currentPassword": "old",
  "newPassword": "new123"
}
```

### POST `/api/profile/avatar`
Sets the profile picture.

**Request body:**
```json
{ "avatarBase64": "data:image/png;base64,..." }
```

---

## Data Model

### AppUser (`users`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `email` | String | Unique, indexed |
| `password` | String | BCrypt hashed, never returned in API |
| `firstName` | String | |
| `lastName` | String | |
| `role` | Enum | `PLATFORM_ADMIN` \| `ORG_ADMIN` \| `ADMIN` \| `USER` |
| `organizationId` | String | Null for `PLATFORM_ADMIN` |
| `active` | boolean | `false` = deactivated |
| `mustChangePassword` | boolean | `true` until invite/reset completed |
| `profilePicture` | String | Base64 data-URL |
| `bio` | String | Short bio |
| `createdAt` | LocalDateTime | Auto |
| `updatedAt` | LocalDateTime | Auto |

### Roles

| Role | Description |
|------|-------------|
| `PLATFORM_ADMIN` | Full system access; not bound to any org |
| `ORG_ADMIN` | Full access within their org |
| `ADMIN` | Manages users and content within their org |
| `USER` | Standard end-user access |

---

## Related
- [Authentication](./01-authentication.md) — login and invite flow
- [Organization Management](./03-organization-management.md) — org assignment
- [Sessions](./16-sessions.md) — JWT session revocation per user
