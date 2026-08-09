package com.braify.feature.esign.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * Org-level automatic e-sign reminder policy, editable by ORG_ADMIN (own org) or PLATFORM_ADMIN.
 * Bounds keep the schedule sane (hours in [1, 720] = up to 30 days; at most 50 reminders).
 */
@Data
public class EsignReminderPolicyRequest {

    /** Master switch for automatic reminders across the org. */
    private boolean enabled = true;

    @Min(value = 1,   message = "First reminder must be at least 1 hour after sending")
    @Max(value = 720, message = "First reminder cannot be more than 720 hours (30 days) after sending")
    private int firstReminderAfterHours = 24;

    @Min(value = 1,   message = "Repeat interval must be at least 1 hour")
    @Max(value = 720, message = "Repeat interval cannot exceed 720 hours (30 days)")
    private int repeatEveryHours = 24;

    @Min(value = 1,  message = "Max reminders must be at least 1")
    @Max(value = 50, message = "Max reminders cannot exceed 50")
    private int maxReminders = 10;
}
