package edu.sjsu.cmpe172.scheduler.ai.knowledge;

import edu.sjsu.cmpe172.scheduler.ai.AiUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The knowledge source for retrieval-augmented generation (RAG).
 *
 * <p>Ingestion: the handbook (knowledge/mockprep-handbook.md) is split at its "##" headings,
 * one chunk per section; each chunk is embedded (Gemini embedding model, text -> vector) and
 * stored in Spring AI's in-memory SimpleVectorStore. Retrieval: a question is embedded the same
 * way, and the store returns the chunks whose vectors are most similar (cosine similarity).
 */
@Component
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final Resource source;
    private final double minSimilarity;
    private volatile VectorStore store;

    public KnowledgeBase(ObjectProvider<EmbeddingModel> embeddingModels,
                         @Value("${app.ai.knowledge-file:classpath:knowledge/mockprep-handbook.md}") Resource source,
                         @Value("${app.ai.rag.min-similarity:0.5}") double minSimilarity) {
        this.embeddingModels = embeddingModels;
        this.source = source;
        this.minSimilarity = minSimilarity;
    }

    public boolean available() {
        return embeddingModels.getIfAvailable() != null;
    }

    /** Ingest at startup in the background, so the first question does not wait for it. */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!available()) {
            log.info("Knowledge base not loaded: no embedding model (GEMINI_API_KEY not set)");
            return;
        }
        try {
            vectorStore();
        } catch (RuntimeException e) {
            log.warn("Knowledge base ingestion failed, will retry on first question: {}", e.getMessage());
        }
    }

    /** The loaded vector store; ingests the handbook on first use. */
    public VectorStore vectorStore() {
        VectorStore loaded = store;
        if (loaded != null) {
            return loaded;
        }
        synchronized (this) {
            if (store == null) {
                EmbeddingModel model = embeddingModels.getIfAvailable();
                if (model == null) {
                    throw new AiUnavailableException("AI is not configured on this server (set GEMINI_API_KEY)");
                }
                List<Document> chunks = chunks();
                SimpleVectorStore vectors = SimpleVectorStore.builder(model).build();
                vectors.add(chunks);   // calls the embedding model once per chunk
                store = vectors;
                log.info("Knowledge base ready: {} chunks from {} embedded into SimpleVectorStore",
                        chunks.size(), source.getFilename());
            }
            return store;
        }
    }

    /** The top-k most similar chunks to the query, above the minimum similarity. */
    public List<Document> search(String query, int topK) {
        return vectorStore().similaritySearch(searchRequest(topK).query(query).build());
    }

    public SearchRequest.Builder searchRequest(int topK) {
        return SearchRequest.builder().topK(topK).similarityThreshold(minSimilarity);
    }

    /** One Document per "## " section, with the section title as metadata (shown as the source). */
    List<Document> chunks() {
        String text;
        try {
            text = source.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read knowledge file " + source, e);
        }
        List<Document> docs = new ArrayList<>();
        String title = null;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.startsWith("## ")) {
                addSection(docs, title, body);
                title = line.substring(3).trim();
                body.setLength(0);
            } else if (title != null) {
                body.append(line).append('\n');
            }
        }
        addSection(docs, title, body);
        return docs;
    }

    private void addSection(List<Document> docs, String title, StringBuilder body) {
        if (title != null && !body.toString().isBlank()) {
            docs.add(new Document(title + "\n" + body.toString().trim(),
                    Map.of("section", title, "source", String.valueOf(source.getFilename()))));
        }
    }

    public static String section(Document doc) {
        return String.valueOf(doc.getMetadata().getOrDefault("section", "Handbook"));
    }
}
