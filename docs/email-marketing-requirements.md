# Email Marketing — Requirements (Draft v0.1)

> Status: **DRAFT for review**. Nothing here is built yet. Decisions in §12 must be
> settled before an implementation plan is produced.
> Owner: Pawan • Last updated: 2026-07-14

---

## 1. Summary
Add an **email marketing** capability so an organization can run email campaigns to its
own audiences, track how recipients engage (delivered, opened, clicked → redirected to the
target, bounced, complained), and let recipients **unsubscribe** — all multi-tenant,
org-scoped, and compliant.

## 2. Goals
- G1 — Let a marketer build, schedule, and send a campaign to a chosen audience.
- G2 — Track engagement per campaign and per contact (opens, clicks, deliverability).
- G3 — Give recipients a reliable, one-click **unsubscribe** and honor it everywhere.
- G4 — Stay compliant (CAN-SPAM / GDPR) and protect sender deliverability.
- G5 — Reuse existing building blocks (templates, Resend, bulk-send pipeline, quotas).

### Non-goals (this phase)
- Marketing **automations / drip sequences** (later).
- Landing-page / form builder for lead capture (later).
- SMS / WhatsApp / push channels.
- Deep CRM (pipelines, deals).

## 3. Builds on existing features
| Existing | Reused for |
|---|---|
| `EmailTemplate` + GrapesJS builder | campaign content |
| `EmailDispatcher` (Resend) | sending; extend with tags, `List-Unsubscribe`, tracking |
| `BulkEmailProcessor` (async pool, per-row status, throttle, quota) | campaign send engine pattern |
| Quota service | marketing send / contact limits |
| Public endpoint + token pattern (e-sign sign/verify, logo) | tracking pixel, click redirect, unsubscribe page |
| Org Branding | from-name, reply-to, footer, logo, physical address |
| Audit log | campaign + suppression actions |

## 4. How it differs from today's Bulk Email
Bulk Email = one-off spreadsheet blast (upload XLSX → send, no audiences/tracking/unsubscribe).
Marketing adds reusable **Contacts + Audiences**, **Campaigns** (draft/schedule/report),
**engagement tracking**, and **unsubscribe/suppression**. Bulk Email stays as-is for now
(see decision D7).

## 5. Personas & roles
- **Marketer** (Org Admin / Admin) — manages contacts, audiences, campaigns; views reports.
- **Recipient / Subscriber** — receives email, clicks, unsubscribes; no login.
- **Platform Admin** — oversees usage, deliverability, abuse across orgs.

## 6. Glossary
- **Contact** — a person/email in an org's marketing database.
- **Audience / List** — a named, reusable group of contacts.
- **Segment** — a saved filter over contacts (dynamic membership).
- **Campaign** — a single scheduled/sent email to an audience/segment.
- **Suppression** — emails that must never be marketed to (unsubscribed / bounced / complained / manual).
- **Event** — an engagement record (delivered/opened/clicked/…).

---

## 7. Functional requirements

### 7.1 Contacts & audiences
- FR-C1 Create / edit / delete contacts; unique per (org, email); soft-delete + GDPR hard-delete.
- FR-C2 Import contacts via CSV/XLSX (reuse existing parser); dedupe by email; import report (added/updated/skipped/invalid).
- FR-C3 Custom fields per org (used as personalization tokens, e.g. `{{firstName}}`, `{{company}}`).
- FR-C4 Consent status per contact: `SUBSCRIBED | UNSUBSCRIBED | BOUNCED | COMPLAINED | PENDING`, with source + timestamp.
- FR-C5 Tags on contacts; create audiences (static membership) and segments (dynamic filter).
- FR-C6 Segment filters (min set): tag, consent status, date added, engaged-with-campaign (opened/clicked), not-engaged.
- FR-C7 Estimated reachable count for an audience/segment (after suppression + non-subscribed removed).

### 7.2 Campaign management
- FR-M1 Create campaign: pick template (or inline), subject, preheader, from-name, reply-to (defaults from Org Branding).
- FR-M2 Choose one or more audiences/segments; show live estimated recipient count.
- FR-M3 Personalization tokens resolved from contact fields; graceful fallback for missing values.
- FR-M4 **Test send** to arbitrary address(es) before launch.
- FR-M5 Save as **draft**; schedule for a datetime (org timezone) or **send now**.
- FR-M6 Campaign lifecycle: `DRAFT → SCHEDULED → SENDING → SENT` (+ `PAUSED`, `CANCELLED`, `FAILED`).
- FR-M7 Every campaign email MUST include an unsubscribe link + the org's physical postal address (enforced at send; block send if address missing — see D4).
- FR-M8 (v2) A/B subject-line test with auto-pick winner.
- FR-M9 Duplicate / clone a campaign.

### 7.3 Sending & deliverability
- FR-S1 Send through the async pipeline (reuse `BulkEmailProcessor` pattern): concurrency-limited, per-recipient status, retry, resumable.
- FR-S2 **Suppression + consent check per recipient** immediately before send; skip and log skips.
- FR-S3 Enforce marketing quota (per plan/month) and contact-count limits.
- FR-S4 Verified sending domain w/ SPF+DKIM+DMARC (see D1).
- FR-S5 Set `List-Unsubscribe` + `List-Unsubscribe-Post: List-Unsubscribe=One-Click` headers.
- FR-S6 Tag each message with `campaignId` + `contactId` (for webhook attribution).
- FR-S7 Rate-limit / spread large sends to protect reputation (batch pacing).

### 7.4 Engagement tracking
- FR-T1 **Delivered / bounced / complained** captured via **Resend webhooks** (signature-verified, idempotent).
- FR-T2 **Opens** via tracking pixel `GET /t/o/{token}.png` (or Resend opens) — dedupe repeat opens per contact.
- FR-T3 **Clicks + redirect to target**: rewrite each link to `GET /t/c/{token}` → record click → `302` to original URL. Per-link + per-contact attribution.
- FR-T4 Hard bounce or spam complaint → **auto-suppress** the contact + mark consent `BOUNCED/COMPLAINED`.
- FR-T5 Events stored append-only (`EmailEvent`); counts rolled up per campaign + per recipient.
- FR-T6 Per-contact engagement timeline.

### 7.5 Unsubscribe & preferences
- FR-U1 One-click unsubscribe link in every campaign (opaque token) → public confirmation page.
- FR-U2 Honor `List-Unsubscribe-Post` one-click (no landing page) for Gmail/Apple Mail.
- FR-U3 Unsubscribe adds email to the **org suppression list** immediately; blocks all future marketing sends.
- FR-U4 Hosted, no-login, org-branded unsubscribe / preference page.
- FR-U5 (v2) Preference center: leave specific audiences vs unsubscribe-all; optional resubscribe.
- FR-U6 Marketer can manually add/remove suppression entries; import a suppression list.

### 7.6 Analytics & reporting
- FR-R1 Per-campaign metrics: recipients, delivered, open rate, click rate (CTR), click-to-open, bounce rate, complaint rate, unsubscribes.
- FR-R2 Top clicked links; open/click timeline.
- FR-R3 Per-contact engagement history.
- FR-R4 Org dashboard: campaigns list + KPIs; CSV export of recipients + events.

### 7.7 Compliance & consent (must-have)
- FR-P1 CAN-SPAM / GDPR: visible unsubscribe honored instantly, accurate sender identity, physical postal address in footer.
- FR-P2 Only send to `SUBSCRIBED` contacts; record consent source + date.
- FR-P3 (Decision D3) single vs double opt-in (confirmation email).
- FR-P4 GDPR data-subject: export + erase a contact and its events.
- FR-P5 Configurable event/data retention window.

---

## 8. Non-functional requirements
- NFR-1 **Multi-tenancy**: every entity carries `orgId`; strict isolation on every query (no cross-org reads — apply the IDOR lessons already learned).
- NFR-2 **Scale**: campaigns of 10k–100k+ recipients; async pool + batched DB writes; `EmailEvent` grows large → proper indexes + retention/rollup.
- NFR-3 **Security**: tracking/unsubscribe tokens opaque + unguessable (signed or random); no PII in URLs; public endpoints rate-limited; webhook signature verified.
- NFR-4 **Idempotency**: webhook + one-click handlers safe under retries/duplicates.
- NFR-5 **Privacy**: store minimal IP/UA for opens/clicks (consider hashing); respect retention.
- NFR-6 **Resilience**: a send is resumable after a crash; no double-send of the same recipient.
- NFR-7 **Observability**: per-campaign send progress, webhook processing logs, deliverability alerts (bounce/complaint spikes).

## 9. New public endpoints (unauthenticated; token-gated, rate-limited)
| Endpoint | Purpose |
|---|---|
| `GET /t/o/{token}.png` | open-tracking pixel |
| `GET /t/c/{token}` | click → record → 302 redirect to target |
| `GET /u/{token}` + `POST /u/{token}` | unsubscribe page + one-click POST |
| `POST /webhooks/resend` | Resend delivery/bounce/complaint/open/click events (verify signature) |

## 10. Data model sketch (Mongo collections)
- `contacts` — id, orgId, email(unique per org), name, fields{}, tags[], consentStatus, source, createdAt, updatedAt.
- `audiences` — id, orgId, name, type(STATIC|SEGMENT), memberContactIds[] or filter{}, counts.
- `campaigns` — id, orgId, name, templateId, subject, preheader, fromName, replyTo, audienceIds[], status, scheduledAt, sentAt, settings{trackOpens,trackClicks}, stats{}.
- `campaign_recipients` — id, orgId, campaignId, contactId, email, status, messageId, opensCount, clicksCount, firstOpenAt, lastClickAt, bounced, complained, unsubscribedAt.
- `email_events` — id, orgId, campaignId, contactId, type, at, ip, ua, url, meta{}. (append-only, indexed by campaignId + contactId + type)
- `suppressions` — id, orgId, email(unique per org), reason(UNSUBSCRIBED|BOUNCED|COMPLAINED|MANUAL), source, at.
- `tracked_links` — id, campaignId, token, originalUrl. (or encode in the click token)

## 11. Success metrics
- Marketer can create + send a campaign in < 5 minutes.
- Accurate open/click/bounce/unsubscribe reporting (reconciles with Resend within tolerance).
- 0 sends to suppressed/unsubscribed contacts.
- Deliverability: bounce < 2%, complaint < 0.1% on healthy lists.

---

## 12. Open decisions (need answers before implementation plan)
- **D1 — Sending domain / deliverability**: per-org verified domain (best deliverability, more setup) vs shared Braify subdomain (easy, shared reputation risk) vs hybrid (per-org with shared fallback). *Recommendation: hybrid.*
- **D2 — Tracking engine**: own pixel + link-rewrite redirect vs Resend built-in opens/clicks vs **hybrid** (own click-rewrite for redirect + per-link stats; Resend webhooks for delivered/bounce/complaint/opens). *Recommendation: hybrid.*
- **D3 — Opt-in**: single opt-in vs double opt-in (confirmation email).
- **D4 — Physical address**: required per org before any campaign send? (compliance) — recommend yes, sourced from Org Branding/settings.
- **D5 — Contacts model**: full Contacts + Audiences + **Segments** now, or static lists only for MVP.
- **D6 — Unsubscribe scope**: global per-org suppression (MVP) vs per-audience preference center from day one.
- **D7 — Bulk Email**: keep the XLSX bulk tool separate, or fold it into the campaign engine over time.
- **D8 — Quotas/pricing**: separate marketing send + contact-count limits per plan?
- **D9 — Retention**: how long to keep `email_events` / IP-UA data.

## 13. Risks & dependencies
- Deliverability/reputation (shared domain abuse) — mitigate via per-org domains + complaint auto-suppression + sending caps.
- Compliance liability (spam) — enforce unsubscribe + address + consent; platform-admin abuse controls.
- Resend feature/limits dependency (webhooks, domains, rate limits, pricing at volume).
- `email_events` growth — needs retention + rollups from the start.
- Scope creep toward a full ESP — keep MVP tight (§14).

## 14. Suggested phasing
- **MVP**: Contacts + static audiences; campaign (template/schedule/send via existing pipeline); suppression + one-click unsubscribe + physical address; delivered/bounce/complaint + opens/clicks via hybrid tracking; per-campaign analytics; shared sending domain.
- **v2**: Segments; per-org domain verification; preference center + resubscribe; A/B testing; richer analytics + export.
- **v3**: Automations/drip; lead-capture forms; deeper deliverability tooling.

---

### Changelog
- v0.1 (2026-07-14) — initial draft for review.
