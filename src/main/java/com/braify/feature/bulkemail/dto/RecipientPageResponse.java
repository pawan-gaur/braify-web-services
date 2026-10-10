package com.braify.feature.bulkemail.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** One page of a campaign's recipients plus the counts for every filter chip. */
@Data @Builder
public class RecipientPageResponse {
    private List<BulkEmailJobResponse.RowResponse> content;
    private int  page;
    private int  size;
    /** Recipients matching the current filter + search. */
    private long totalElements;
    private int  totalPages;
    /** Count per filter (all/sent/failed/pending/opened/clicked/unopened/unsubscribed), whole campaign. */
    private Map<String, Long> counts;
}
