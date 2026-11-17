package edu.sjsu.cmpe172.scheduler.dto;

/** One message of a stored conversation; role is "user" or "assistant". */
public record ChatMessageDto(String role, String text) {
}
