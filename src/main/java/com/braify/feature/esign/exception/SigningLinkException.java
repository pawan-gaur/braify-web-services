package com.braify.feature.esign.exception;

/**
 * Thrown when a signing link cannot be opened, carrying a classified {@link Reason}
 * so the signing UI can show a specific, actionable message instead of a generic error.
 */
public class SigningLinkException extends RuntimeException {

    public enum Reason {
        EXPIRED,        // link expired / superseded, document still awaiting this signer
        ALREADY_SIGNED, // this signer (or the whole document) is already signed/completed
        CANCELLED,      // the sender cancelled/voided the document
        INVALID         // token can't be decoded, or the document no longer exists
    }

    private final Reason reason;

    public SigningLinkException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
