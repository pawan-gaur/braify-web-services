package com.braify.feature.placeholder.dto;

import com.braify.feature.placeholder.model.GlobalPlaceholder;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class GlobalPlaceholderResponse {

    private String id;
    private String key;
    private String value;
    private String label;
    private GlobalPlaceholder.Type type;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static GlobalPlaceholderResponse from(GlobalPlaceholder p) {
        return GlobalPlaceholderResponse.builder()
                .id(p.getId())
                .key(p.getKey())
                .value(p.getValue())
                .label(p.getLabel())
                .type(p.getType())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
