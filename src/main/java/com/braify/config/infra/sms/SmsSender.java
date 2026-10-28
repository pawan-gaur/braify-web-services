package com.braify.config.infra.sms;

import com.braify.feature.smsconfig.model.OrgSmsConfig.SmsProvider;

/** Provider adapter that delivers an {@link OutboundSms}. One per {@link SmsProvider}. */
public interface SmsSender {

    SmsProvider provider();

    SmsSendResult send(ResolvedSmsConfig cfg, OutboundSms sms);
}
