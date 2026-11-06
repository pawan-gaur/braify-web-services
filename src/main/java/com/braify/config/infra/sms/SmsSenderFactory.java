package com.braify.config.infra.sms;

import com.braify.feature.smsconfig.model.OrgSmsConfig.SmsProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Routes a {@link ResolvedSmsConfig} to the matching {@link SmsSender}. */
@Component
public class SmsSenderFactory {

    private final Map<SmsProvider, SmsSender> byProvider = new EnumMap<>(SmsProvider.class);

    public SmsSenderFactory(List<SmsSender> senders) {
        for (SmsSender s : senders) {
            byProvider.put(s.provider(), s);
        }
    }

    public SmsSender get(SmsProvider provider) {
        SmsSender sender = byProvider.get(provider);
        if (sender == null) {
            throw new IllegalStateException("No SMS sender registered for provider: " + provider);
        }
        return sender;
    }
}
