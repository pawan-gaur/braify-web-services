package com.braify.feature.esign.service;

/**
 * Tokenised, email-client-safe HTML for the e-sign system (INTERNAL) emails.
 *
 * <p>Layout uses tables (not CSS flexbox) so alignment holds in Gmail, Outlook and
 * webmail clients. The seeder stores these bodies in {@code email_templates}
 * (type INTERNAL); {@link ESignEmailService} also uses them as the fallback when a
 * DB record is missing. {@code {{token}}} substitution happens in {@code EmailDispatcher}
 * from the value map assembled by {@link ESignEmailService}.
 *
 * <p>Shared brand tokens: {@code {{organizationName}} {{brandMark}} {{accent}}
 * {{accentSoft}} {{accentBorder}} {{footerContact}}}. {@code brandMark} /
 * {@code footerContact} carry sender-built HTML (logo-or-initial, contact line).
 */
final class ESignEmailTemplates {

    private ESignEmailTemplates() {}

    /** Brand bar (logo + org name on the left, SECURE E-SIGN on the right). */
    private static String brandBar() {
        return """
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-bottom:1px solid #EEF2F6;">
              <tr>
                <td style="padding:22px 32px;vertical-align:middle;">
                  <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
                    <td style="vertical-align:middle;padding-right:10px;">{{brandMark}}</td>
                    <td style="vertical-align:middle;font-size:15px;font-weight:700;color:#0F172A;">{{organizationName}}</td>
                  </tr></table>
                </td>
                <td align="right" style="padding:22px 32px;vertical-align:middle;white-space:nowrap;">
                  <span style="display:inline-block;width:6px;height:6px;border-radius:999px;background:#22C55E;vertical-align:middle;margin-right:6px;"></span><span style="font-size:10.5px;font-weight:700;letter-spacing:0.14em;color:#94A3B8;vertical-align:middle;">SECURE E-SIGN</span>
                </td>
              </tr>
            </table>
            """;
    }

    private static String footer(String introParagraph) {
        return """
            <div style="padding:22px 32px 28px;background:#F8FAFC;border-top:1px solid #EEF2F6;">
              <p style="margin:0 0 12px;font-size:12px;line-height:1.6;color:#94A3B8;">%s</p>
              {{footerContact}}
              <div style="margin-top:14px;font-size:11px;color:#CBD5E1;">Powered by <a href="https://braify.com/" style="color:#94A3B8;font-weight:700;text-decoration:none;">{{organizationName}} e-Sign</a> · 256-bit encrypted &amp; audit-logged</div>
            </div>
            """.formatted(introParagraph);
    }

    private static String docShellOpen() {
        return "<div style=\"border:1px solid #E2E8F0;border-radius:12px;background:#F8FAFC;overflow:hidden;\">";
    }

    /** A document card header row: PDF tile + name + sub-label, with an optional right-aligned status badge. */
    private static String docHeader(String subLabel, String badge) {
        String badgeCell = badge.isEmpty() ? "" :
            "<td align=\"right\" style=\"vertical-align:middle;padding:18px 20px 18px 0;white-space:nowrap;\">" + badge + "</td>";
        return """
            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0"><tr>
              <td width="40" style="vertical-align:middle;padding:18px 0 18px 20px;">
                <div style="width:40px;height:40px;line-height:40px;border-radius:9px;background:{{accentSoft}};color:{{accent}};text-align:center;font-size:11px;font-weight:800;">PDF</div>
              </td>
              <td style="vertical-align:middle;padding:18px 20px;">
                <div style="font-size:15px;font-weight:700;color:#0F172A;">{{documentName}}</div>
                <div style="font-size:12.5px;color:#94A3B8;margin-top:2px;">%s</div>
              </td>
              %s
            </tr></table>
            """.formatted(subLabel, badgeCell);
    }

    /** Two-column meta grid (label/value | label/value) with a top divider. */
    private static String metaGrid(String leftLabel, String leftValue, String rightLabel, String rightValue) {
        return """
            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="border-top:1px solid #E2E8F0;"><tr>
              <td width="50%%" style="padding:14px 20px;border-right:1px solid #E2E8F0;vertical-align:top;">
                <div style="font-size:10.5px;font-weight:700;letter-spacing:0.1em;color:#94A3B8;margin-bottom:5px;">%s</div>%s
              </td>
              <td width="50%%" style="padding:14px 20px;vertical-align:top;">
                <div style="font-size:10.5px;font-weight:700;letter-spacing:0.1em;color:#94A3B8;margin-bottom:5px;">%s</div>%s
              </td>
            </tr></table>
            """.formatted(leftLabel, leftValue, rightLabel, rightValue);
    }

    private static String head(String accentBar) {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="margin:0;padding:0;background:#EEF2F6;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background:#EEF2F6;padding:32px 12px;"><tr><td align="center">
              <div style="width:600px;max-width:100%%;text-align:left;background:#fff;border:1px solid #E2E8F0;border-radius:16px;overflow:hidden;box-shadow:0 12px 32px -12px rgba(15,23,42,0.12);">
                <div style="height:4px;background:%s;"></div>
            """.formatted(accentBar);
    }

    private static final String TAIL = """
                </div>
            </td></tr></table></body></html>
            """;

    // ── Template 01 — Document to sign ────────────────────────────────────────
    static final String INVITATION =
        head("{{accent}}") + brandBar() + """
            <div style="padding:36px 32px 8px;">
              <div style="font-size:11px;font-weight:700;letter-spacing:0.14em;color:{{accent}};margin-bottom:14px;">SIGNATURE REQUESTED</div>
              <h1 style="margin:0 0 14px;font-size:26px;line-height:1.25;font-weight:700;color:#0F172A;letter-spacing:-0.02em;">You have a document to sign</h1>
              <p style="margin:0 0 26px;font-size:15px;line-height:1.6;color:#475569;">Hi <strong style="color:#0F172A;">{{signerName}}</strong>, <strong style="color:#0F172A;">{{organizationName}}</strong> has requested your electronic signature on the document below.</p>
            """
        + docShellOpen()
        + docHeader("Awaiting your signature", "")
        + metaGrid("SIGNER",
                   "<div style=\"font-size:13.5px;font-weight:600;color:#0F172A;\">{{signerName}}</div><div style=\"font-size:12px;color:#64748B;margin-top:1px;\">{{signerEmail}}</div>",
                   "EXPIRES",
                   "<div style=\"font-size:13.5px;font-weight:600;color:#0F172A;\">{{expiryDate}}</div><div style=\"display:inline-block;margin-top:5px;font-size:11px;font-weight:700;color:#B45309;background:#FEF3C7;border-radius:6px;padding:2px 8px;\">{{expiresIn}}</div>")
        + """
              </div>
              <div style="text-align:center;margin:30px 0 8px;"><a href="{{signingLink}}" style="display:inline-block;background:{{accent}};color:#fff;font-size:15px;font-weight:700;padding:15px 40px;border-radius:10px;text-decoration:none;">Review &amp; Sign Document</a></div>
              <p style="margin:0 auto 30px;text-align:center;font-size:12.5px;line-height:1.5;color:#94A3B8;max-width:360px;">This is a secure, single-use link unique to you. Please don't forward this email.</p>
            </div>
            """
        + footer("If you weren't expecting this request, you can safely ignore this email — no signature will be recorded and no further action is required.")
        + TAIL;

    // ── Template 01b — Signing reminder ───────────────────────────────────────
    static final String REMINDER =
        head("{{accent}}") + brandBar() + """
            <div style="padding:36px 32px 8px;">
              <div style="font-size:11px;font-weight:700;letter-spacing:0.14em;color:#B45309;margin-bottom:14px;">REMINDER · ACTION NEEDED</div>
              <h1 style="margin:0 0 14px;font-size:26px;line-height:1.25;font-weight:700;color:#0F172A;letter-spacing:-0.02em;">Your signature is still needed</h1>
              <p style="margin:0 0 26px;font-size:15px;line-height:1.6;color:#475569;">Hi <strong style="color:#0F172A;">{{signerName}}</strong>, a friendly reminder that <strong style="color:#0F172A;">{{organizationName}}</strong> is still waiting for your electronic signature on the document below.</p>
            """
        + docShellOpen()
        + docHeader("Awaiting your signature", "")
        + metaGrid("SIGNER",
                   "<div style=\"font-size:13.5px;font-weight:600;color:#0F172A;\">{{signerName}}</div><div style=\"font-size:12px;color:#64748B;margin-top:1px;\">{{signerEmail}}</div>",
                   "EXPIRES",
                   "<div style=\"font-size:13.5px;font-weight:600;color:#0F172A;\">{{expiryDate}}</div><div style=\"display:inline-block;margin-top:5px;font-size:11px;font-weight:700;color:#B45309;background:#FEF3C7;border-radius:6px;padding:2px 8px;\">{{expiresIn}}</div>")
        + """
              </div>
              <div style="text-align:center;margin:30px 0 8px;"><a href="{{signingLink}}" style="display:inline-block;background:{{accent}};color:#fff;font-size:15px;font-weight:700;padding:15px 40px;border-radius:10px;text-decoration:none;">Review &amp; Sign Document</a></div>
              <p style="margin:0 auto 30px;text-align:center;font-size:12.5px;line-height:1.5;color:#94A3B8;max-width:360px;">This is a secure, single-use link unique to you. Please don't forward this email.</p>
            </div>
            """
        + footer("If you've already signed, no further action is needed — you can safely ignore this reminder.")
        + TAIL;

    // ── Template 02 — Signed document completed ───────────────────────────────
    static final String COMPLETION =
        head("#16A34A") + brandBar() + """
            <div style="padding:36px 32px 8px;text-align:center;">
              <div style="width:56px;height:56px;line-height:56px;border-radius:999px;background:#DCFCE7;color:#16A34A;text-align:center;font-size:28px;font-weight:700;margin:0 auto 20px;">✓</div>
              <div style="font-size:11px;font-weight:700;letter-spacing:0.14em;color:#16A34A;margin-bottom:12px;">SIGNATURE COMPLETE</div>
              <h1 style="margin:0 0 14px;font-size:26px;line-height:1.25;font-weight:700;color:#0F172A;letter-spacing:-0.02em;">Your document has been signed</h1>
              <p style="margin:0 auto 26px;font-size:15px;line-height:1.6;color:#475569;max-width:420px;">Hi <strong style="color:#0F172A;">{{signerName}}</strong>, your signature was recorded successfully. A signed copy is attached to this email.</p>
            </div>
            <div style="padding:0 32px 8px;">
            """
        + docShellOpen()
        + docHeader("Signed copy attached",
                    "<span style=\"font-size:11px;font-weight:700;color:#16A34A;background:#DCFCE7;border-radius:6px;padding:3px 9px;\">SIGNED</span>")
        + metaGrid("SIGNED BY",
                   "<div style=\"font-size:13.5px;font-weight:600;color:#0F172A;\">{{signerName}}</div><div style=\"font-size:12px;color:#64748B;margin-top:1px;\">{{signerEmail}}</div>",
                   "SIGNED ON",
                   "<div style=\"font-size:13.5px;font-weight:600;color:#0F172A;\">{{signedOn}}</div>")
        + "{{documentHashBlock}}"
        + """
              </div>
              <div style="text-align:center;margin:28px 0 6px;"><a href="{{verifyLink}}" style="display:inline-block;background:{{accent}};color:#fff;font-size:15px;font-weight:700;padding:15px 40px;border-radius:10px;text-decoration:none;">Verify Document</a></div>
            </div>
            """
        + footer("This confirmation was sent to all parties on the document. If you believe you received this in error, please ignore this email.")
        + TAIL;

    // ── Template 03 — CC / for-your-information (view-only) ────────────────────
    static final String CC_NOTIFICATION =
        head("#64748B") + brandBar() + """
            <div style="padding:36px 32px 8px;">
              <div style="display:inline-block;font-size:11px;font-weight:700;letter-spacing:0.14em;color:#475569;background:#F1F5F9;border:1px solid #E2E8F0;border-radius:999px;padding:5px 12px;margin-bottom:16px;">FOR YOUR INFORMATION</div>
              <h1 style="margin:0 0 14px;font-size:26px;line-height:1.25;font-weight:700;color:#0F172A;letter-spacing:-0.02em;">A document was sent for signature</h1>
              <p style="margin:0 0 26px;font-size:15px;line-height:1.6;color:#475569;">Hi <strong style="color:#0F172A;">{{ccName}}</strong>, you're being kept informed. <strong style="color:#0F172A;">{{organizationName}}</strong> has sent the document below to <strong style="color:#0F172A;">{{signerName}}</strong> for electronic signature.</p>
            """
        + docShellOpen()
        + docHeader("Sent for signature",
                    "<span style=\"font-size:11px;font-weight:700;color:#B45309;background:#FEF3C7;border-radius:6px;padding:3px 9px;\">AWAITING</span>")
        + metaGrid("SENT TO",
                   "<div style=\"font-size:13.5px;font-weight:600;color:#0F172A;\">{{signerName}}</div><div style=\"font-size:12px;color:#64748B;margin-top:1px;\">{{signerEmail}}</div>",
                   "SENT ON",
                   "<div style=\"font-size:13.5px;font-weight:600;color:#0F172A;\">{{sentOn}}</div>")
        + """
              </div>
              <div style="text-align:center;margin:30px 0 8px;"><a href="{{viewLink}}" style="display:inline-block;background:#fff;color:#0F172A;border:1.5px solid #CBD5E1;font-size:15px;font-weight:700;padding:14px 40px;border-radius:10px;text-decoration:none;">View Document</a></div>
              <p style="margin:0 auto 30px;text-align:center;font-size:12.5px;line-height:1.5;color:#94A3B8;max-width:380px;">This is a view-only link — you can't sign or edit the document. No action is required from you.</p>
            </div>
            """
        + footer("You received this because you were added as an informed party on this document. If you believe this was in error, you can safely ignore this email.")
        + TAIL;

    // ── CC completion — view-only signed notice ───────────────────────────────
    static final String CC_COMPLETION =
        head("#16A34A") + brandBar() + """
            <div style="padding:36px 32px 8px;text-align:center;">
              <div style="width:56px;height:56px;line-height:56px;border-radius:999px;background:#DCFCE7;color:#16A34A;text-align:center;font-size:28px;font-weight:700;margin:0 auto 20px;">✓</div>
              <div style="font-size:11px;font-weight:700;letter-spacing:0.14em;color:#16A34A;margin-bottom:12px;">COMPLETED</div>
              <h1 style="margin:0 0 14px;font-size:26px;line-height:1.25;font-weight:700;color:#0F172A;letter-spacing:-0.02em;">A document has been signed</h1>
              <p style="margin:0 auto 26px;font-size:15px;line-height:1.6;color:#475569;max-width:420px;">The document <strong style="color:#0F172A;">{{documentName}}</strong> has been signed by all parties on {{signedOn}}.</p>
              <div style="margin:8px 0 28px;"><a href="{{viewLink}}" style="display:inline-block;background:{{accent}};color:#fff;font-size:15px;font-weight:700;padding:15px 40px;border-radius:10px;text-decoration:none;">View Document</a></div>
            </div>
            """
        + footer("This is a view-only link — no action is required from you.")
        + TAIL;
}
