# Spec — Scheduled & Recurring Bulk-Email Sends

**Status:** Draft for review · **Effort:** Medium (~3–4 days; recurrence +~1 day) · **Risk:** Low-medium (immediate path unchanged when no schedule is set)

## Goal
Let a user schedule a bulk-email job to send at a future time, and optionally repeat on a cadence (daily/weekly/monthly). Reuses the **existing** `BulkEmailJob` + `BulkEmailProcessor`; adds a minute-level poller that dispatches due jobs — mirroring the existing `ESignExpiryScheduler` pattern.

## Grounding (current code)
- `POST /api/bulk-email/jobs` → `BulkEmailService.createJob()` persists the job (`PENDING`) and **immediately** triggers `BulkEmailProcessor.processJobAsync()` via the async proxy (`bulkemail/...`).
- `BulkEmailJob` already has a `JobStatus` enum and caches `emailTemplateHtml` + rows at creation (`bulkemail/model/BulkEmailJob.java`).
- `ESignExpiryScheduler` is the established `@Scheduled(fixedDelay=…)` + targeted-query pattern that explicitly avoids `findAll()` to keep heavy embedded arrays off the heap (`esign/service/ESignExpiryScheduler.java`).
- Existing `/cancel`, `/resend`, `/retry-pending` endpoints (`BulkEmailController`).

## Data model — `BulkEmailJob` (new fields)
| Field | Type | Notes |
|---|---|---|
| `JobStatus.SCHEDULED` | new enum value | sits before `PENDING`; not yet dispatched |
| `scheduledAt` | `LocalDateTime` (UTC) | null = send now (today's behavior) |
| `timezone` | `String` (IANA) | for display + recurrence math; instant stored UTC |
| `recurrence` | `enum { NONE, DAILY, WEEKLY, MONTHLY }` | default NONE |
| `recurrenceEndsAt` | `LocalDateTime` | optional stop date |
| `nextRunAt` | `LocalDateTime` | recurring **parent** only |
| `parentJobId` | `String` | set on each spawned occurrence |

**Recurrence model:** the recurring "definition" is a parent job (status `SCHEDULED`, holds recurrence + `nextRunAt`). Each occurrence is a **cloned child** `BulkEmailJob` that runs as a normal job (its own progress/rows/audit). This keeps per-run history clean and reuses all existing job UI/endpoints for each run.

## API changes
- **Extend** `BulkEmailJobRequest`: add `scheduledAt`, `timezone`, `recurrence`, `recurrenceEndsAt`.
  - `scheduledAt` null or ≤ now → behave exactly as today (immediate).
  - `scheduledAt` future → status `SCHEDULED`, **do not** start processing. Audit `JOB_SCHEDULED`.
- **New** `POST /api/bulk-email/jobs/{id}/reschedule` `{ scheduledAt, recurrence? }` — edit a `SCHEDULED` job.
- **Reuse** `POST /{id}/cancel` — allow cancelling a `SCHEDULED` job; cancelling a recurring parent stops future occurrences.
- List endpoint: include `SCHEDULED` jobs (+ optional "upcoming" filter).
- New `BulkEmailAuditEvent.EventType`: `JOB_SCHEDULED`, `JOB_RESCHEDULED`, `OCCURRENCE_SPAWNED`.

## New scheduler — `BulkEmailScheduler` (mirrors `ESignExpiryScheduler`)
```java
@Scheduled(fixedDelay = 60_000) // every minute
public void dispatchDue() {
  // 1) One-time + occurrence dispatch — TARGETED query, no findAll()
  for (id : repo.findIdsByStatusAndScheduledAtBefore(SCHEDULED, now)) {
     // atomic claim to avoid double-dispatch (findAndModify SCHEDULED -> PENDING)
     if (claim(id)) { processor.processJobAsync(id); audit(PROCESSING_STARTED); }
  }
  // 2) Recurring parents due to spawn
  for (parent : repo.findRecurringDue(now)) {
     childId = cloneForRun(parent);     // fresh rows/config; re-read template HTML
     processor.processJobAsync(childId);
     parent.nextRunAt = computeNext(parent, tz);  // DST-aware
     if (past recurrenceEndsAt) parent.status = COMPLETED;
     repo.save(parent);
  }
}
```
- **Targeted query only** (project ids / lightweight fields) — never `findAll()` (same heap lesson as `ESignExpiryScheduler`, jobs embed up to 5 000 rows).
- **Atomic claim** (`findAndModify` SCHEDULED→PENDING) before dispatch so a job can't be double-sent (matters once there's >1 app instance).
- **Catch-up:** if the server was down past `scheduledAt`, the next tick still matches (`scheduledAt ≤ now`) and dispatches.

## Frontend (`BulkEmailSendPage`, `BulkEmailJobsPage`)
- Send step: **Send now / Schedule** toggle → date-time picker (default user's timezone) + optional recurrence selector.
- Jobs list: `SCHEDULED` badge + `scheduledAt`, recurrence summary; Reschedule / Cancel actions; an "Upcoming" filter.

## Decisions to confirm
- **Template freshness for recurring:** re-read the latest template HTML at each occurrence (recurring statements should reflect template edits) — vs. snapshot once. *Proposed: re-read per occurrence.*
- **Recipient data for recurring:** v1 re-uses the rows captured at creation (same recipients each run). Re-fetching from a live source per run is a later enhancement (ties into the "Data connectors" roadmap item).
- **Quota:** enforce at **dispatch** time, not schedule time, so a scheduled job respects the quota that's actually available when it runs (fail/skip with audit if over).

## Edge cases
- `scheduledAt` ≤ now → treat as immediate.
- DST/timezone: compute `nextRunAt` in the user's IANA tz.
- Cancel parent → stops future occurrences; an already-running child is unaffected.
- Server restart: no lost schedules (poller is stateless + catch-up).

## Rollout
Phase 1: one-time scheduled send (`scheduledAt`). Phase 2: recurrence. Phase 3: per-run data refresh via connectors.
