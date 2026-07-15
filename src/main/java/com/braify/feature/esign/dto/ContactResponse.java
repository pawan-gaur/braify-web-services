package com.braify.feature.esign.dto;

/**
 * A single recipient suggestion returned to the send-flow autocomplete.
 * {@code useCount} lets the client keep the server's ranking if it re-sorts locally.
 */
public record ContactResponse(String name, String email, long useCount) {}
