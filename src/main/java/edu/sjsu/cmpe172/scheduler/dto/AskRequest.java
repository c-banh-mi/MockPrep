package edu.sjsu.cmpe172.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/assistant/ask. */
public record AskRequest(@NotBlank @Size(max = 500) String question) {
}
