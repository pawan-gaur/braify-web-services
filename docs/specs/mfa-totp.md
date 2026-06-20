# Spec — MFA / 2FA (TOTP)

**Status:** Draft for review · **Effort:** Medium-low (~2–3 days) · **Risk:** Low (additive; users without MFA are unaffected)

## Goal
Authenticator-app (TOTP, RFC 6238) second factor at login, with one-time recovery codes, **governed by a per-organization policy that PLATFORM_ADMIN controls**. Built on the **existing** email/password + JWT + `UserSession` model — no change to how sessions/JWTs work after login.

## Org-level MFA policy (PLATFORM_ADMIN)
PLATFORM_ADMIN sets an MFA policy on each `Organization`. Enforcement is computed at login from `(org policy, user enrollment)`:

| Org policy | User not enrolled | User already enrolled |
|---|---|---|
| **DISABLED** (default) | no MFA | **no MFA challenge** — enrollment is *preserved but inactive* |
| **OPTIONAL** | no MFA (may self-enroll) | challenged at login |
| **REQUIRED** | login succeeds but app is gated → **forced enrollment** (`mustSetupMfa`) | challenged at login |

**Critical rule (per request):** turning the **org** policy from REQUIRED/OPTIONAL → DISABLED **must NOT delete** a user's `mfaSecret`/`mfaEnabled`/recovery codes — it only suspends the challenge. If the org is later set back to REQUIRED/OPTIONAL, every previously-enrolled user is challenged again using their **existing** secret, exactly as before. (Only a *user* disabling their own MFA clears their secret — see endpoints below.)

`mfaRequiredForLogin(user, org)` → returns `NONE` | `CHALLENGE` | `MUST_SETUP`, encapsulating the table above. PLATFORM_ADMIN users have no org → treated as OPTIONAL (self-enroll).

## Grounding (current code)
- `AuthService.login()` validates password → enforces `MAX_SESSIONS` → issues JWT → saves `UserSession` → audits `LOGIN` → returns `LoginResponse` **with token** (`auth/service/AuthService.java`).
- `AuthController` exposes `/api/auth/login`, `/logout`, `/me` (`auth/controller/AuthController.java`).
- `AppUser` is the user document (`user/model/AppUser.java`).
- `EncryptionService` already exists (used for file storage) — reuse for secret-at-rest.

## Data model
### `Organization` (new field)
| Field | Type | Notes |
|---|---|---|
| `mfaPolicy` | `enum { DISABLED, OPTIONAL, REQUIRED }` (default `DISABLED`) | set by PLATFORM_ADMIN |

### `AppUser` (new fields) — preserved across org-policy toggles
| Field | Type | Notes |
|---|---|---|
| `mfaEnabled` | `boolean` (default false) | the user's own enrollment flag (NOT cleared by org-level disable) |
| `mfaSecret` | `String` | base32 TOTP secret, **encrypted at rest** via `EncryptionService`; null until enrolled |
| `mfaPendingSecret` | `String` | encrypted secret during enrollment, before first verify |
| `mfaRecoveryCodes` | `List<String>` | **BCrypt-hashed** one-time codes |
| `mfaEnrolledAt` | `LocalDateTime` | |

## Enrollment (user already logged in)
| Endpoint | Body | Returns |
|---|---|---|
| `POST /api/auth/mfa/setup` | – | `{ secret, otpauthUri, qrDataUri }` — generate secret, store as `mfaPendingSecret` |
| `POST /api/auth/mfa/enable` | `{ code }` | verify code vs pending secret → `mfaEnabled=true`, persist encrypted secret, generate **10 recovery codes** (returned once, stored hashed). Audit `MFA_ENABLED` |
| `POST /api/auth/mfa/disable` | `{ code }` (or password) | **rejected if org policy is REQUIRED**; otherwise verify → clear secret + recovery codes, `mfaEnabled=false`. Audit `MFA_DISABLED` |
| `GET  /api/auth/mfa/status` | – | `{ orgPolicy, enabled, enrolledAt, recoveryCodesRemaining }` |
| `POST /api/auth/mfa/recovery-codes/regenerate` | `{ code }` | re-verify → new codes (invalidate old) |

### Org policy (PLATFORM_ADMIN only)
| Endpoint | Body | Notes |
|---|---|---|
| `PUT /api/organizations/{id}/mfa-policy` | `{ policy }` | set DISABLED / OPTIONAL / REQUIRED. Audit `ORG_MFA_POLICY_CHANGED`. **Never touches user secrets** (preserve-on-disable). |

QR: backend returns the `otpauth://totp/...` URI **and** a QR PNG data-URI (the TOTP lib generates both), so no frontend QR dependency.

## Login flow change (the only login change)
After a valid password, `AuthService.login()` loads the org and calls `mfaRequiredForLogin(user, org)`:
1. **`NONE`** (org DISABLED, or OPTIONAL+not-enrolled) → unchanged: issue session + full `LoginResponse`.
2. **`CHALLENGE`** (enrolled, and org OPTIONAL/REQUIRED) → return `{ mfaRequired: true, mfaToken }` and **do not** issue the session JWT yet. `mfaToken` is a short-lived (5 min) JWT with `purpose=mfa-challenge`, `sub=userId` — not usable as a session token.
   - `POST /api/auth/login/mfa` `{ mfaToken, code }`: validate token + `code` (TOTP **or** recovery code) → run the **same** session-creation logic as today (`MAX_SESSIONS`, JWT, `UserSession`, audit `LOGIN` with `mfa=true`) → full `LoginResponse`.
   - Recovery code: match a stored hash → consume → audit `MFA_RECOVERY_USED`; warn when few remain. Rate-limit per `mfaToken`.
3. **`MUST_SETUP`** (org REQUIRED + not enrolled) → issue the session normally **but** set `mustSetupMfa: true` in `LoginResponse`. The frontend gates the app to mandatory enrollment (same pattern as `mustChangePassword`); enrollment endpoints require this session.

## DTO changes
- `LoginResponse`: add `boolean mfaRequired`, `String mfaToken` (challenge path), and `boolean mustSetupMfa` (forced-enrollment path).
- New `MfaVerifyRequest { mfaToken, code }`; new `OrgMfaPolicyRequest { policy }`.

## Frontend
- `AuthContext.login()`: if `mfaRequired`, don't store token — expose a challenge state; add `verifyMfa(mfaToken, code)` calling `/login/mfa`. If `mustSetupMfa`, store the token but route to mandatory MFA setup (gate the rest of the app, like `mustChangePassword`).
- `LoginPage`: after password, show a 6-digit input + "use a recovery code" link.
- `ProfilePage` → **Security** section, behavior depends on org policy (`GET /mfa/status.orgPolicy`):
  - **DISABLED**: show "MFA is turned off for your organization." If the user has a preserved enrollment, note it's retained and will reactivate if the org re-enables MFA. Hide enroll/disable.
  - **OPTIONAL**: full self-serve — Enable (QR + verify), show recovery codes once, Disable, regenerate.
  - **REQUIRED**: Enable + regenerate available; **Disable hidden/blocked** (must stay on).
- **PLATFORM_ADMIN org admin** (`OrganizationsPage` / `OrgDetailPage`): an MFA-policy selector (Disabled / Optional / Required) per org → `PUT /organizations/{id}/mfa-policy`.

## Security
- Encrypt `mfaSecret` at rest (`EncryptionService`); hash recovery codes (BCrypt); show codes once.
- TOTP window tolerance ±1 step (clock skew). Rate-limit login + `/login/mfa`.
- `mfaToken`: 5-min TTL, single-purpose claim, validated in `/login/mfa`.
- Only challenge **after** a correct password (don't leak MFA status to anonymous callers).
- Audit: `MFA_ENABLED`, `MFA_DISABLED`, `MFA_RECOVERY_USED`, `MFA_FAILED`, `LOGIN(mfa=true)`.

## Edge cases
- **Org disable preserves enrollment** (the key requirement): DISABLED suspends the challenge but keeps each user's `mfaSecret`/`mfaEnabled`/recovery codes. Re-enabling REQUIRED/OPTIONAL resumes challenges with the existing secrets — no re-enrollment. Only a *user* self-disabling clears their own secret.
- Org policy DISABLED→REQUIRED: already-enrolled users are challenged immediately; not-enrolled users get `mustSetupMfa` on next login. Existing sessions stay valid until they expire (enforcement applies at next login) — optionally add a "force re-auth on policy tighten" toggle later.
- A user under REQUIRED policy cannot self-disable MFA (endpoint rejects).
- Lost device → recovery codes; if exhausted → ORG_ADMIN / PLATFORM_ADMIN can reset a user's MFA (clears secret so they re-enroll), audited.
- `mustChangePassword` + MFA: force password change first, then MFA challenge / forced setup.
- PLATFORM_ADMIN has no org → OPTIONAL self-enroll (a future global/platform policy could govern this).

## Library
Add `dev.samstevens.totp:totp` (secret gen, code verify, QR data-URI) to `pom.xml`. No frontend lib needed.

## Rollout
Phase 1: opt-in self-enroll. Phase 2: org-enforced policy. Phase 3: admin-forced reset UI.
