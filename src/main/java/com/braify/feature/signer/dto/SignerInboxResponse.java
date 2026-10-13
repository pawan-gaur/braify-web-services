package com.braify.feature.signer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** A page of the signer inbox plus the per-tab counts and org filter options. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignerInboxResponse {

    private List<SignerDocumentItem> items;
    private long total;
    private int  page;
    private int  size;

    /** Count per tab (ACTION / UPCOMING / SIGNED / OTHER), ignoring the org + search filters. */
    private Map<String, Long> counts;

    /** Organizations that have sent this signer documents — for the org filter. */
    private List<OrgOption> organizations;

    public record OrgOption(String id, String name) {}
}
