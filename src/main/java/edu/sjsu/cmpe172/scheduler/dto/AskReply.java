package edu.sjsu.cmpe172.scheduler.dto;

import java.util.List;

/** A grounded answer and the handbook sections it was based on. */
public record AskReply(String answer, List<String> sources) {
}
