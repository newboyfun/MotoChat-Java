package com.motochat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
        @JsonProperty("old_password") @NotBlank String oldPassword,
        @JsonProperty("new_password") @NotBlank String newPassword
) {
}
