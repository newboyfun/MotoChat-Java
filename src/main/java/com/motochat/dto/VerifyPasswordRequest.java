package com.motochat.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyPasswordRequest(@NotBlank String password) {
}
