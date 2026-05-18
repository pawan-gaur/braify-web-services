package com.braify.feature.quota.exception;

/**
 * Thrown when an organisation exceeds one of its configured quota limits.
 * GlobalExceptionHandler converts this to HTTP 429 Too Many Requests.
 */
public class QuotaExceededException extends RuntimeException {

    private final String quotaType;
    private final long   limit;
    private final long   current;

    public QuotaExceededException(String quotaType, long limit, long current) {
        super(buildMessage(quotaType, limit, current));
        this.quotaType = quotaType;
        this.limit     = limit;
        this.current   = current;
    }

    public String getQuotaType() { return quotaType; }
    public long   getLimit()     { return limit; }
    public long   getCurrent()   { return current; }

    private static String buildMessage(String quotaType, long limit, long current) {
        return String.format(
                "%s quota exceeded (%d/%d). Please upgrade your subscription plan to continue.",
                quotaType, current, limit);
    }
}
