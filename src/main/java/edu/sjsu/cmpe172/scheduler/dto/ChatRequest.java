package edu.sjsu.cmpe172.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/assistant/chat.
 *
 * @param conversationId chosen by the client (a UUID); the same id continues the same conversation
 */
public record ChatRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{8,40}") String conversationId,
        @NotBlank @Size(max = 1000) String message) {
}
