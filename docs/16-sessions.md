# Sessions

Tracks active JWT sessions for security visibility and forced-logout capability. Every successful login creates a session record tied to the JWT's unique `jti` claim.

**Base path:** `/api/sessions`  
**Auth:** JWT required.

---

## Endpoints

### GET `/api/sessions`
Returns active sessions visible to the caller.

- `PLATFORM_ADMIN` → all active sessions across all orgs
- `ORG_ADMIN` → all sessions within their org
- `ADMIN` → sessions for users in their org
- `USER` → their own sessions only

**Response `200`:** `List<SessionResponse>`
```json
[
  {
    "id": "sess123",
    "userId": "user456",
    "userEmail": "jane@acme.com",
    "userName": "Jane Doe",
    "deviceInfo": "Chrome/Windows",
    "ipAddress": "192.168.1.10",
    "createdAt": "2026-05-20T08:00:00",
    "lastUsedAt": "2026-05-20T14:30:00",
    "expiresAt": "2026-05-21T08:00:00",
    "active": true
  }
]
```

---

### DELETE `/api/sessions/{id}`
Revokes a specific session immediately. The JWT associated with this session will be rejected on the next request.

**Access rules:**
- `PLATFORM_ADMIN` — can revoke any session.
- `ORG_ADMIN` — can revoke sessions within their org.
- `USER` — can only revoke their own sessions.

**Response `204`**

---

### DELETE `/api/sessions/user/{userId}`
Revokes **all active sessions** for a specific user. Used when deactivating a user or responding to a security incident.

**Response `204`**

---

## How Sessions Work

1. **Login** — `AuthService.login()` creates a `UserSession` record with `jti` (JWT ID), device info, IP, and expiry.
2. **Every request** — `JwtAuthFilter` extracts the `jti` from the token and calls `findByJtiAndActiveTrue(jti)`. If not found (revoked), the request is rejected with `401`.
3. **Logout** — `AuthService.logout(jti)` sets `active = false` on the session.
4. **Revocation** — Setting `active = false` on a session immediately blocks all further requests using that JWT, even if the token hasn't expired yet.
5. **Cleanup** — `SessionCleanupScheduler` runs every 6 hours and purges inactive sessions older than `jwt.expiration-hours` (default 24 hours).

---

## Session Limits

`AuthService` enforces a maximum of **5 concurrent active sessions per user**. When the limit is reached, the **oldest session** is automatically revoked before creating a new one. This is configurable.

---

## Background Scheduler

`SessionCleanupScheduler` runs **every 6 hours**:
- Deletes inactive (`active = false`) sessions where `lastUsedAt` is older than `jwt.expiration-hours`.
- This keeps the `user_sessions` collection bounded.
- Configurable via `jwt.expiration-hours` (default 24).

---

## Data Model

### UserSession (`user_sessions`)

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | MongoDB ID |
| `userId` | String | Indexed |
| `jti` | String | JWT `jti` claim — unique indexed |
| `organizationId` | String | For role-scoped listing |
| `userRole` | String | Snapshot of role at login |
| `deviceInfo` | String | e.g. `Chrome/Windows` |
| `ipAddress` | String | Client IP at login |
| `active` | boolean | False = revoked/logged out |
| `createdAt` | LocalDateTime | Auto |
| `expiresAt` | LocalDateTime | JWT expiry |
| `lastUsedAt` | LocalDateTime | Updated on each authenticated request |
| `revokedBy` | String | User ID of revoker |
| `revokedByName` | String | Display name of revoker |
| `revokedAt` | LocalDateTime | Revocation time |

---

## Security Notes

- The `jti` index on `user_sessions` is critical for performance — it is queried on **every authenticated request**.
- Sessions survive server restarts — revocation state persists in MongoDB.
- Force-logout a compromised account by calling `DELETE /api/sessions/user/{userId}`.
- JWT expiry and session revocation are **both** enforced — a valid unexpired JWT is still rejected if its session is inactive.

---

## Related
- [Authentication](./01-authentication.md) — login creates sessions, logout invalidates them
- [User Management](./02-user-management.md) — user deactivation revokes all sessions
- [Audit Logging](./05-audit-logging.md) — `SESSION_REVOKED` action recorded
