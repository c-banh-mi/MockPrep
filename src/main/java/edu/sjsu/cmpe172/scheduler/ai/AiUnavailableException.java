package edu.sjsu.cmpe172.scheduler.ai;

/** The LLM cannot be used right now (no API key configured, quota exhausted, network). HTTP 503. */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
