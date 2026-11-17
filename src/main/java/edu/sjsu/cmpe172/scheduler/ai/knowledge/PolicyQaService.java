package edu.sjsu.cmpe172.scheduler.ai.knowledge;

import edu.sjsu.cmpe172.scheduler.ai.AiModels;
import edu.sjsu.cmpe172.scheduler.dto.AskReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Grounded Q&A over the MockPrep handbook (RAG). Spring AI's QuestionAnswerAdvisor does the
 * retrieval step: before the LLM call it searches the vector store with the question and pastes
 * the matching chunks into the prompt as "context". The system prompt tells the model to answer
 * only from that context, and the reply lists which handbook sections were used.
 */
@Service
public class PolicyQaService {

    private static final Logger log = LoggerFactory.getLogger(PolicyQaService.class);
    private static final int TOP_K = 4;

    static final String SYSTEM = """
            You are MockPrep's help desk. MockPrep is a platform where software engineers book mock
            interviews and resume reviews with mentors.
            Answer ONLY with facts from the context provided with the question. If the context does not
            contain the answer, say that you don't know and suggest contacting MockPrep support.
            Never invent prices, times or rules. Answer in at most 4 short sentences, in plain text.
            """;

    private final AiModels ai;
    private final KnowledgeBase knowledge;

    public PolicyQaService(AiModels ai, KnowledgeBase knowledge) {
        this.ai = ai;
        this.knowledge = knowledge;
    }

    public AskReply ask(String question) {
        try {
            QuestionAnswerAdvisor rag = QuestionAnswerAdvisor.builder(knowledge.vectorStore())
                    .searchRequest(knowledge.searchRequest(TOP_K).build())
                    .build();
            ChatResponse response = ai.chatClient().prompt()
                    .system(SYSTEM)
                    .advisors(rag)
                    .user(question)
                    .call()
                    .chatResponse();
            List<Document> used = response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
            List<String> sources = used == null ? List.of()
                    : used.stream().map(KnowledgeBase::section).distinct().toList();
            String answer = response.getResult().getOutput().getText();
            log.info("Policy Q&A: \"{}\" answered from {}", question, sources);
            return new AskReply(answer == null ? "" : answer.trim(), sources);
        } catch (RuntimeException e) {
            log.error("Policy Q&A failed: {}", e.getMessage());
            throw AiModels.callFailed(e);
        }
    }
}
