package edu.sjsu.cmpe172.scheduler.dto;

import java.time.Instant;

/** A successful login: the JWT to send as "Authorization: Bearer ...", and who it belongs to. */
public record LoginResponse(String token, String tokenType, Instant expiresAt, UserDto user) {
}
