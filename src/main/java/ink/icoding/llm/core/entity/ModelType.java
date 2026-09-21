package ink.icoding.llm.core.entity;

/**
 * 大语言模型类型枚举.
 * <p>定义了框架支持的LLM提供商类型, 用于 {@link ink.icoding.llm.core.model.LLMModel#create} 工厂方法.</p>
 *
 * @author gsk
 */
public enum ModelType {

    /** OpenAI Chat Completions API */
    OpenAI,

    /** Anthropic Messages API */
    Anthropic,

    /** OpenAI Responses API */
    OpenAIResponse,

    /** OpenAI-compatible text Embeddings API */
    OpenAIEmbedding,

    /** DashScope native multimodal Embedding API */
    DashScopeMultimodalEmbedding
}
