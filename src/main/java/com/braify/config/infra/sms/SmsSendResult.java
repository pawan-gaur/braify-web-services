package com.braify.config.infra.sms;

/** Provider-neutral result of an outbound SMS send. */
public class SmsSendResult {

    private final String id;
    private final String provider;

    public SmsSendResult(String id, String provider) {
        this.id = id;
        this.provider = provider;
    }

    public String getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }
}
