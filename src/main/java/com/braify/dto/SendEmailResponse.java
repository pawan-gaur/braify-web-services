package com.braify.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body for POST /api/email-templates/{id}/send
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendEmailResponse {

    /** Resend message ID returned by the Resend API. */
    private String messageId;

    /** Recipient address the email was sent to. */
    private String to;

    /** Subject that was used (template subject or caller override). */
    private String subject;
}
