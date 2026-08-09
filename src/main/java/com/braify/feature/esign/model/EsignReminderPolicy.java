package com.braify.feature.esign.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Organisation-level policy for automatic e-sign signing reminders.
 * Embedded on {@link com.braify.feature.organization.model.Organization}; when unset the
 * built-in defaults apply (first reminder 24h after sending, then every 24h).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EsignReminderPolicy {

    /** Whether automatic reminders run for this org's documents at all. */
    @Builder.Default
    private boolean enabled = true;

    /** Hours after the invitation before the first reminder. */
    @Builder.Default
    private int firstReminderAfterHours = 24;

    /** Hours between subsequent reminders. */
    @Builder.Default
    private int repeatEveryHours = 24;

    /** Hard cap on reminders per signatory (also bounded by the document's expiry). */
    @Builder.Default
    private int maxReminders = 10;

    public static EsignReminderPolicy defaults() {
        return EsignReminderPolicy.builder().build();
    }
}
