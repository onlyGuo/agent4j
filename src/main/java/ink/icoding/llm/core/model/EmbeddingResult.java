package ink.icoding.llm.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Embedding 调用结果。 */
public class EmbeddingResult {
    private final String model;
    private final String requestId;
    private final List<Embedding> embeddings;
    private final TokenUsage usage;

    public EmbeddingResult(String model, String requestId, List<Embedding> embeddings, TokenUsage usage) {
        if (embeddings == null || embeddings.isEmpty()) {
            throw new IllegalArgumentException("embedding result must contain at least one vector");
        }
        this.model = model;
        this.requestId = requestId;
        this.embeddings = Collections.unmodifiableList(new ArrayList<>(embeddings));
        this.usage = usage;
    }

    public String getModel() { return model; }
    public String getRequestId() { return requestId; }
    public List<Embedding> getEmbeddings() { return embeddings; }
    public TokenUsage getUsage() { return usage; }

    /** 返回第一个向量，适用于单输入调用。 */
    public Embedding getEmbedding() { return embeddings.get(0); }

    @Override
    public String toString() {
        return "EmbeddingResult{" +
                "model='" + model + '\'' +
                ", requestId='" + requestId + '\'' +
                ", embeddings=" + embeddings +
                ", usage=" + usage +
                '}';
    }
}
