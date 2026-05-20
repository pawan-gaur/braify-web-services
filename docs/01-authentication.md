# Authentication

Handles login, logout, current-user lookup, email invitation acceptance, and password reset.

**Base path:** `/api/auth`  
**Auth required:** public (login, forgot-password, reset-password, validate-token, accept-invite); JWT required for logout and `/me`

---

## Endpoints

### POST `/api/auth/login`
Authenticates a user and returns a JWT + session record.

**Request body:**
```json
{
  "email": "user@example.com",
  "password": "secret",
  "deviceInfo": "Chrome/Windows"
}
```

**Response `200`:**
```json
{
  "token": "eyJhbGci...",
  "user": {
    "id": "abc123",
    "email": "user@example.com",
    "firstName": "Jane",
    "lastName": "Doe",
    "role": "ORG_ADMIN",
    "organizationId": "org456"
  }
}
```

- Creates a `UserSession` record for audit and forced-logout support.
- IP address resolved from `X-Forwarded-For` → `X-Real-IP` → `RemoteAddr`.
- Fails with `401` if credentials are invalid or the account is inactive.

---

### POST `/api/auth/logout`
Invalidates the current JWT by marking its session as inactive.

**Headers:** `Authorization: Bearer <token>`  
**Response `204`:** No content.

---

### GET `/api/auth/me`
Returns the full profile of the currently authenticated user.

**Headers:** `Authorization: Bearer <token>`  
**Response `200`:** `UserResponse` (see [User Management](./02-user-management.md)).

---

### GET `/api/auth/validate-token?token=<uuid>`
Validates an invitation or password-reset token before the user submits a new password. Used by the frontend to pre-fill the email field and confirm the link hasn't expired.

**Response `200`:**
```json
{
  "email": "user@example.com",
  "firstName": "Jane",
  "type": "INVITE"
}
```
Token types: `INVITE`, `PASSWORD_RESET`.

---

### POST `/api/auth/accept-invite`
Completes an email invitation — sets the user's password for the first time.

**Request body:**
```json
{
  "token": "<uuid-from-email>",
  "password": "newSecurePassword"
}
```
- Password must be at least 6 characters.
- Marks `mustChangePassword = false` on the user.
- Marks the token as used.

**Response `200`:**
```json
{ "message": "Password set successfully. You can now log in." }
```

---

### POST `/api/auth/forgot-password`
Sends a password-reset email. Always returns `200` — does not reveal whether the email is registered.

**Request body:**
```json
{ "email": "user@example.com" }
```

**Response `200`:**
```json
{ "message": "If that email is registered you'll receive a reset link shortly." }
```

---

### POST `/api/auth/reset-password`
Completes a password reset using the token from the email link.

**Request body:**
```json
{
  "token": "<uuid-from-email>",
  "password": "newPassword"
}
```
- Token expires after 1 hour.
- Password must be at least 6 characters.

**Response `200`:**
```json
{ "message": "Password reset successfully. You can now log in." }
```

---

## Data Model

### InvitationToken (`invitation_tokens`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `userId` | String | Owner user ID |
| `token` | String | Secure UUID (unique index) |
| `type` | Enum | `INVITE` \| `PASSWORD_RESET` |
| `expiresAt` | LocalDateTime | INVITE: 7 days, RESET: 1 hour |
| `used` | boolean | Flipped on consumption |
| `usedAt` | LocalDateTime | When consumed |
| `createdAt` | LocalDateTime | Auto |

---

## Flow Diagrams

### New User Invite
```
Admin creates user → EmailInviteService sends invite email
  → User clicks link → GET /validate-token (confirm link valid)
  → POST /accept-invite (set password)
  → User logs in via POST /login
```

### Forgot Password
```
User submits email → POST /forgot-password
  → EmailInviteService sends reset email
  → User clicks link → GET /validate-token
  → POST /reset-password
  → User logs in via POST /login
```

---

## Related
- [User Management](./02-user-management.md) — user entity and profile
- [Sessions](./16-sessions.md) — JWT session tracking and revocation
