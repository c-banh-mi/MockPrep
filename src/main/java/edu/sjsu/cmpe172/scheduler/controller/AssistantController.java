package edu.sjsu.cmpe172.scheduler.controller;

import edu.sjsu.cmpe172.scheduler.ai.AiRateLimiter;
import edu.sjsu.cmpe172.scheduler.ai.agent.BookingAgent;
import edu.sjsu.cmpe172.scheduler.ai.knowledge.PolicyQaService;
import edu.sjsu.cmpe172.scheduler.dto.AskReply;
import edu.sjsu.cmpe172.scheduler.dto.AskRequest;
import edu.sjsu.cmpe172.scheduler.dto.ChatMessageDto;
import edu.sjsu.cmpe172.scheduler.dto.ChatReply;
import edu.sjsu.cmpe172.scheduler.dto.ChatRequest;
import edu.sjsu.cmpe172.scheduler.model.AppUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Milestone 4 AI endpoints. Chat (Agent 1) is customer-only; Q&A is for any logged-in user
 * (see SecurityConfig). Both answer 503 when Gemini is not configured or not reachable.
 */
@RestController
@RequestMapping("/api/assistant")
@Validated
public class AssistantController {

    private static final String ID = "[A-Za-z0-9-]{8,40}";

    private final BookingAgent agent;
    private final PolicyQaService qa;
    private final AiRateLimiter limiter;

    public AssistantController(BookingAgent agent, PolicyQaService qa, AiRateLimiter limiter) {
        this.agent = agent;
        this.qa = qa;
        this.limiter = limiter;
    }

    /** One message to the booking agent; the same conversationId continues the conversation. */
    @PostMapping("/chat")
    public ChatReply chat(@AuthenticationPrincipal(expression = "user") AppUser me, @Valid @RequestBody ChatRequest req) {
        limiter.check(me.id());
        return agent.chat(me, req.conversationId(), req.message());
    }

    /** The stored messages of one of my conversations (persisted in PostgreSQL). */
    @GetMapping("/chat/{conversationId}")
    public List<ChatMessageDto> history(@AuthenticationPrincipal(expression = "user") AppUser me,
                                        @PathVariable @Pattern(regexp = ID) String conversationId) {
        return agent.history(me, conversationId);
    }

    @DeleteMapping("/chat/{conversationId}")
    public ResponseEntity<Void> forget(@AuthenticationPrincipal(expression = "user") AppUser me,
                                       @PathVariable @Pattern(regexp = ID) String conversationId) {
        agent.forget(me, conversationId);
        return ResponseEntity.noContent().build();
    }

    /** Grounded Q&A over the MockPrep handbook (RAG). */
    @PostMapping("/ask")
    public AskReply ask(@AuthenticationPrincipal(expression = "user") AppUser me, @Valid @RequestBody AskRequest req) {
        limiter.check(me.id());
        return qa.ask(req.question());
    }
}
