package com.braify.feature.esign.dto;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Generic paginated response wrapper.
 * Use {@link #of(Page, List)} to build from a Spring Data {@link Page}.
 */
@Data @Builder
public class PageResponse<T> {

    private List<T>  content;
    private int      page;
    private int      size;
    private long     totalElements;
    private int      totalPages;
    private boolean  first;
    private boolean  last;

    public static <T> PageResponse<T> of(Page<?> page, List<T> mappedContent) {
        return PageResponse.<T>builder()
                .content(mappedContent)
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .first(page.isFirst())
                .last(page.isLast())
                .build();
    }
}
