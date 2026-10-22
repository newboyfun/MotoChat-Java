package com.motochat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RevealKeyRequest(String password,
                               @JsonProperty("key_type") String keyType) {

    public RevealKeyRequest {
        if (keyType == null || keyType.isBlank()) {
            keyType = "llm";
        }
    }
}
