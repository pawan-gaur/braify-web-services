package com.braify.feature.ai.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.function.Consumer;

/** Shared JSON-over-HTTP plumbing for the concrete providers. */
abstract class AbstractHttpAiProvider implements AiProvider {

    protected final ObjectMapper mapper = new ObjectMapper();
    private final RestClient http = RestClient.create();

    /** POST a JSON body and parse the response as a JsonNode. */
    protected JsonNode postJson(String url, Consumer<org.springframework.http.HttpHeaders> headers, Map<String, Object> body) {
        try {
            String json = http.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return mapper.readTree(json == null ? "{}" : json);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            throw new AiProviderException(id() + " request failed (" + e.getStatusCode().value() + "): "
                    + truncate(e.getResponseBodyAsString()), e);
        } catch (Exception e) {
            throw new AiProviderException(id() + " request failed: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
