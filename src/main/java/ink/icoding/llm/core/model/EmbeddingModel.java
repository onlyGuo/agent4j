package ink.icoding.llm.core.model;

import ink.icoding.llm.core.entity.EmbeddingInput;
import ink.icoding.llm.core.entity.ModelType;
import ink.icoding.llm.core.model.impl.DashScopeMultimodalEmbeddingModel;
import ink.icoding.llm.core.model.impl.OpenAIEmbeddingModel;

import java.util.List;

/**
 * Embedding 模型统一抽象。
 * <p>文本模型使用 OpenAI 兼容的 {@code /v1/embeddings} 接口；多模态模型使用
 * DashScope 原生多模态 Embedding 接口。两者均返回 {@link EmbeddingResult}。</p>
 */
public interface EmbeddingModel {

    /** 根据模型类型创建 Embedding 模型。 */
    static EmbeddingModel create(ModelType type, String baseUrl, String modelName, String apiKey) {
        return create(type, baseUrl, modelName, apiKey, false);
    }

    /** 根据模型类型创建 Embedding 模型，并可选输出脱敏请求调试日志。 */
    static EmbeddingModel create(ModelType type, String baseUrl, String modelName, String apiKey,
                                 boolean requestDebugEnabled) {
        return switch (type) {
            case OpenAIEmbedding -> new OpenAIEmbeddingModel(baseUrl, modelName, apiKey, requestDebugEnabled);
            case DashScopeMultimodalEmbedding -> new DashScopeMultimodalEmbeddingModel(
                    baseUrl, modelName, apiKey, requestDebugEnabled);
            default -> throw new IllegalArgumentException(type + " is not an Embedding model type");
        };
    }

    /** 为单段文本生成向量。 */
    EmbeddingResult embed(String text);

    /** 为多段文本批量生成向量。 */
    EmbeddingResult embedTexts(List<String> texts);

    /** 为一个文本或多模态语义单元生成向量。 */
    EmbeddingResult embed(EmbeddingInput input);

    /** 为多个文本或多模态语义单元批量生成向量。 */
    EmbeddingResult embedInputs(List<EmbeddingInput> inputs);

    default void setRequestDebugEnabled(boolean enabled) {}
    default boolean isRequestDebugEnabled() { return false; }

    /** 设置请求的目标向量维度；null 表示使用模型默认维度。 */
    default void setDimensions(Integer dimensions) {}
    default Integer getDimensions() { return null; }

    /**
     * 是否请求多模态融合向量。仅 DashScope 的 qwen3-vl-embedding 等模型需要该开关；
     * 新版按 content 对象自动融合的模型可保持默认值。
     */
    default void setFusionEnabled(boolean enabled) {}
    default boolean isFusionEnabled() { return false; }
}
