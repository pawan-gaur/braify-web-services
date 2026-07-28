package com.braify.feature.bulkemail.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Aggregated engagement analytics for a single bulk-email campaign — powers the
 * Analytics tab (rates, opens/clicks timeline, top-clicked links).
 *
 * <p>Rates are fractions in [0,1]. Distinct-recipient counts here are computed exactly
 * from the event log, so they are authoritative (the job document's denormalised
 * counters are only a fast approximation for the list view).
 */
@Data @Builder
public class BulkEmailAnalyticsResponse {

    private String jobId;
    private String label;

    private int sentCount;
    private int openedRecipients;      // distinct recipients who opened ≥1
    private int clickedRecipients;     // distinct recipients who clicked ≥1
    private int unsubscribedCount;
    private int suppressedCount;

    private long totalOpens;           // raw open hits (inflated by mail-client prefetch)
    private long totalClicks;          // raw click hits

    private double openRate;           // openedRecipients / sentCount
    private double clickRate;          // clickedRecipients / sentCount
    private double clickToOpenRate;    // clickedRecipients / openedRecipients

    private List<TimePoint> timeline;  // hourly opens & clicks, chronological
    private List<LinkStat>  topLinks;  // most-clicked destination URLs

    @Data @Builder
    public static class TimePoint {
        private String bucket;   // "yyyy-MM-dd HH:00"
        private long   opens;
        private long   clicks;
    }

    @Data @Builder
    public static class LinkStat {
        private String url;
        private long   clicks;
    }
}
