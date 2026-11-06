package com.braify.config.infra.sms;

/** A provider-neutral outbound SMS. */
public record OutboundSms(String from, String to, String body) {}
