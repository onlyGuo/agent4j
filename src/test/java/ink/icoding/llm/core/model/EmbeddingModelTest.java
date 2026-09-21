package ink.icoding.llm.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ink.icoding.llm.core.entity.EmbeddingInput;
import ink.icoding.llm.core.entity.ModelType;
import ink.icoding.llm.core.model.impl.DashScopeMultimodalEmbeddingModel;
import ink.icoding.llm.core.model.impl.OpenAIEmbeddingModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingModelTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void openAiCompatibleModelBuildsTextBatchAndParsesVectors() throws Exception {
        OpenAIEmbeddingModel model = new OpenAIEmbeddingModel("https://example.com", "text-embedding-3-small", "test-key");
        model.setDimensions(512);

        ObjectNode body = (ObjectNode) invoke(model, "buildRequestBody", new Class[]{List.class}, List.of("first", "second"));
        EmbeddingResult result = (EmbeddingResult) invoke(model, "parseResponse", new Class[]{String.class}, """
                {"model":"text-embedding-3-small","data":[
                  {"index":0,"embedding":[0.1,0.2]},
                  {"index":1,"embedding":[0.3,0.4]}
                ],"usage":{"prompt_tokens":7,"total_tokens":7}}
                """);

        assertEquals("text-embedding-3-small", body.path("model").asText());
        assertEquals(512, body.path("dimensions").asInt());
        assertEquals(List.of("first", "second"), MAPPER.convertValue(body.path("input"), List.class));
        assertEquals(2, result.getEmbeddings().size());
        assertEquals(List.of(0.1, 0.2), result.getEmbedding().getVector());
        assertEquals(7, result.getUsage().getInputTokens());
    }

    @Test
    void dashScopeModelBuildsFusedTextAndImageInputAndParsesTypes() throws Exception {
        DashScopeMultimodalEmbeddingModel model = new DashScopeMultimodalEmbeddingModel(
                "https://example.com", "qwen3-vl-embedding", "test-key");
        model.setDimensions(1024);
        model.setFusionEnabled(true);
        EmbeddingInput input = EmbeddingInput.create()
                .appendText("red shoe")
                .appendImage(new byte[]{1, 2, 3}, "image/png")
                .appendImage("https://example.com/shoe-side.png");

        ObjectNode content = (ObjectNode) invoke(model, "buildContent", new Class[]{EmbeddingInput.class}, input);
        EmbeddingResult result = (EmbeddingResult) invoke(model, "parseResponse", new Class[]{String.class}, """
                {"request_id":"request-1","output":{"embeddings":[
                  {"index":0,"type":"fused","embedding":[0.5,0.6]}
                ]},"usage":{"input_tokens":11}}
                """);

        assertEquals("red shoe", content.path("text").asText());
        assertEquals(2, content.path("multi_images").size());
        assertTrue(content.path("multi_images").get(0).asText().startsWith("data:image/png;base64,"));
        assertEquals(1024, model.getDimensions());
        assertTrue(model.isFusionEnabled());
        assertEquals("request-1", result.getRequestId());
        assertEquals("fused", result.getEmbedding().getType());
        assertEquals(List.of(0.5, 0.6), result.getEmbedding().getVector());
    }

    @Test
    void factorySelectsEmbeddingImplementationsAndRejectsWrongKind() {
        assertInstanceOf(OpenAIEmbeddingModel.class,
                EmbeddingModel.create(ModelType.OpenAIEmbedding, "https://example.com", "text", "key"));
        assertInstanceOf(DashScopeMultimodalEmbeddingModel.class,
                EmbeddingModel.create(ModelType.DashScopeMultimodalEmbedding, "https://example.com", "vision", "key"));
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingModel.create(ModelType.OpenAI, "https://example.com", "text", "key"));
    }

    @Test
    void openAiCompatibleModelRejectsMultimediaInput() {
        EmbeddingModel model = new OpenAIEmbeddingModel("https://example.com", "text", "key");
        assertThrows(UnsupportedOperationException.class,
                () -> model.embed(EmbeddingInput.create().appendImage("https://example.com/image.png")));
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
