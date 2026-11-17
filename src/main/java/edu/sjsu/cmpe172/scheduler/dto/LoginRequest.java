package edu.sjsu.cmpe172.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/auth/login. */
public record LoginRequest(@NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 100) String password) {
}
