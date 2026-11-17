package edu.sjsu.cmpe172.scheduler.ai.agent;

import edu.sjsu.cmpe172.scheduler.ai.AiModels;
import edu.sjsu.cmpe172.scheduler.ai.knowledge.KnowledgeBase;
import edu.sjsu.cmpe172.scheduler.dto.ChatMessageDto;
import edu.sjsu.cmpe172.scheduler.dto.ChatReply;
import edu.sjsu.cmpe172.scheduler.model.AppUser;
import edu.sjsu.cmpe172.scheduler.service.AppointmentService;
import edu.sjsu.cmpe172.scheduler.service.BookingService;
import edu.sjsu.cmpe172.scheduler.service.CatalogService;
import edu.sjsu.cmpe172.scheduler.service.SlotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;

/**
 * Agent 1, the conversational (reactive) booking agent. One call = one user message:
 * Spring AI sends the system prompt, the conversation so far (chat memory), the new message and
 * the tool definitions to Gemini. Gemini may answer directly or ask for tool calls; Spring AI runs
 * those tools (BookingAgentTools) and sends the results back, looping until Gemini gives a final
 * text answer. That loop is what makes multi-step requests work, e.g. "find the earliest slot
 * this week and book it" = checkAvailability, then proposeBooking, then a summary for the user.
 */
@Service
public class BookingAgent {

    private static final Logger log = LoggerFactory.getLogger(BookingAgent.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US);

    private final AiModels ai;
    private final ChatMemory memory;
    private final AgentSessions sessions;
    private final SlotService slots;
    private final CatalogService catalog;
    private final AppointmentService appointments;
    private final BookingService booking;
    private final KnowledgeBase knowledge;
    private final Clock clock;

    public BookingAgent(AiModels ai, ChatMemory memory, AgentSessions sessions, SlotService slots,
                        CatalogService catalog, AppointmentService appointments, BookingService booking,
                        KnowledgeBase knowledge, Clock clock) {
        this.ai = ai;
        this.memory = memory;
        this.sessions = sessions;
        this.slots = slots;
        this.catalog = catalog;
        this.appointments = appointments;
        this.booking = booking;
        this.knowledge = knowledge;
        this.clock = clock;
    }

    public ChatReply chat(AppUser user, String clientConversationId, String message) {
        String conversationId = conversationId(user, clientConversationId);
        AgentSessions.Session session = sessions.get(conversationId);
        synchronized (session) {   // one message at a time per conversation
            BookingAgentTools tools = startTurn(user, clientConversationId);
            log.info("Agent turn {} for {} ({}): \"{}\"", tools.turn(), user.username(), conversationId, message);
            String reply;
            try {
                reply = ai.chatClient().prompt()
                        .system(systemPrompt(user))
                        .user(message)
                        .tools(tools)
                        .advisors(MessageChatMemoryAdvisor.builder(memory).build())
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .call()
                        .content();
            } catch (RuntimeException e) {
                log.error("Agent call failed for {}: {}", user.username(), e.getMessage());
                if (!tools.actionsTaken().isEmpty()) {
                    // The write already committed (e.g. Gemini timed out while phrasing its answer).
                    // Report what was done instead of an error that would make the user retry.
                    return new ChatReply(clientConversationId,
                            "Done. (The assistant could not finish its reply, but your request was carried out.)",
                            null, tools.actionsTaken());
                }
                throw AiModels.callFailed(e);
            }
            PendingAction pending = session.pending();
            return new ChatReply(clientConversationId, reply == null ? "" : reply.trim(),
                    pending == null ? null : pending.summary(), tools.actionsTaken());
        }
    }

    /**
     * Starts the next turn of a conversation and returns the tools for it, bound to this user.
     * chat() uses it for every message; tests use it to drive the tools turn by turn without an LLM.
     */
    public BookingAgentTools startTurn(AppUser user, String clientConversationId) {
        AgentSessions.Session session = sessions.get(conversationId(user, clientConversationId));
        return new BookingAgentTools(user, session, session.nextTurn(), slots, catalog, appointments, booking,
                knowledge, clock);
    }

    /** The stored conversation (from PostgreSQL), so the chat page can be reloaded. */
    public List<ChatMessageDto> history(AppUser user, String clientConversationId) {
        return memory.get(conversationId(user, clientConversationId)).stream()
                .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
                .map(m -> new ChatMessageDto(m.getMessageType() == MessageType.USER ? "user" : "assistant", m.getText()))
                .toList();
    }

    public void forget(AppUser user, String clientConversationId) {
        String id = conversationId(user, clientConversationId);
        memory.clear(id);
        sessions.remove(id);
    }

    /** Prefixed with the user id: a user can only ever load their own conversations. */
    static String conversationId(AppUser user, String clientConversationId) {
        return user.id() + ":" + clientConversationId;
    }

    String systemPrompt(AppUser user) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDate sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        return """
                You are the MockPrep booking assistant. MockPrep lets software engineers book mock interviews
                and resume reviews with mentors. You are talking to %s (username %s), a customer, and you act
                only on their behalf.
                Today is %s and the time is %s, Pacific Time. "This week" means today through %s.

                Rules:
                1. Use the tools for every fact about services, mentors, slots, appointments and policies.
                   Never invent slots, ids, prices, times or rules.
                2. Booking is two steps. First find the slot (checkAvailability) and call proposeBooking.
                   Then show the user the summary and ask them to confirm. Call confirmPendingAction only
                   after the user clearly says yes in a later message. Never book on the first request,
                   even if the user says "book it".
                3. Cancelling works the same way: proposeCancellation, ask, then confirmPendingAction.
                4. If a slot is taken or nothing matches, say so and offer up to 3 alternatives from the tools.
                5. If the request is ambiguous (which service? which day?), ask one short clarifying question.
                6. When you list slots or appointments, number them and include the slot id or appointment id
                   in parentheses, e.g. "1. Mock Coding Interview with Alice Nguyen, Tue Nov 18, 10:00 AM (slot 12)",
                   so the user can refer to them later.
                7. For policy questions (cancellation, refunds, hours, preparation), use searchPolicies and
                   answer only from its results.
                8. Be brief and friendly. Plain text, no markdown tables.
                """.formatted(user.fullName(), user.username(), DAY.format(today),
                DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(now), DAY.format(sunday));
    }
}
