package ink.icoding.llm.core.model.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ink.icoding.llm.core.entity.EmbeddingInput;
import ink.icoding.llm.core.model.Embedding;
import ink.icoding.llm.core.model.EmbeddingModel;
import ink.icoding.llm.core.model.EmbeddingResult;
import ink.icoding.llm.core.model.LLMRequestDebugLogger;
import ink.icoding.llm.core.model.TokenUsage;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** DashScope 原生多模态 Embedding API 实现，支持文本、图片、视频和融合向量。 */
public class DashScopeMultimodalEmbeddingModel implements EmbeddingModel {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_PATH = "/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";
    private final String baseUrl;
    private final String modelName;
    private final String apiKey;
    private final OkHttpClient client;
    private boolean requestDebugEnabled;
    private Integer dimensions;
    private boolean fusionEnabled;

    /**
     * @param baseUrl DashScope 服务地址，通常为 {@code https://dashscope.aliyuncs.com}
     */
    public DashScopeMultimodalEmbeddingModel(String baseUrl, String modelName, String apiKey) {
        this(baseUrl, modelName, apiKey, false);
    }

    public DashScopeMultimodalEmbeddingModel(String baseUrl, String modelName, String apiKey,
                                              boolean requestDebugEnabled) {
        this.baseUrl = OpenAIEmbeddingModel.normalizeBaseUrl(baseUrl);
        this.modelName = OpenAIEmbeddingModel.requireValue(modelName, "modelName");
        this.apiKey = OpenAIEmbeddingModel.requireValue(apiKey, "apiKey");
        this.requestDebugEnabled = requestDebugEnabled;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public EmbeddingResult embed(String text) {
        return embed(EmbeddingInput.fromText(OpenAIEmbeddingModel.requireValue(text, "text")));
    }

    @Override
    public EmbeddingResult embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) throw new IllegalArgumentException("texts must not be empty");
        List<EmbeddingInput> inputs = new ArrayList<>(texts.size());
        for (String text : texts) inputs.add(EmbeddingInput.fromText(OpenAIEmbeddingModel.requireValue(text, "text")));
        return embedInputs(inputs);
    }

    @Override
    public EmbeddingResult embed(EmbeddingInput input) {
        return embedInputs(List.of(OpenAIEmbeddingModel.requireInput(input)));
    }

    @Override
    public EmbeddingResult embedInputs(List<EmbeddingInput> inputs) {
        if (inputs == null || inputs.isEmpty()) throw new IllegalArgumentException("inputs must not be empty");
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", modelName);
        ObjectNode input = body.putObject("input");
        ArrayNode contents = input.putArray("contents");
        for (EmbeddingInput embeddingInput : inputs) contents.add(buildContent(OpenAIEmbeddingModel.requireInput(embeddingInput)));

        ObjectNode parameters = MAPPER.createObjectNode();
        if (dimensions != null) parameters.put("dimension", dimensions);
        if (fusionEnabled) parameters.put("enable_fusion", true);
        if (!parameters.isEmpty()) body.set("parameters", parameters);
        return execute(body);
    }

    @Override
    public void setRequestDebugEnabled(boolean enabled) { this.requestDebugEnabled = enabled; }
    @Override
    public boolean isRequestDebugEnabled() { return requestDebugEnabled; }
    @Override
    public void setDimensions(Integer dimensions) { this.dimensions = OpenAIEmbeddingModel.validateDimensions(dimensions); }
    @Override
    public Integer getDimensions() { return dimensions; }
    @Override
    public void setFusionEnabled(boolean enabled) { this.fusionEnabled = enabled; }
    @Override
    public boolean isFusionEnabled() { return fusionEnabled; }

    private ObjectNode buildContent(EmbeddingInput input) {
        ObjectNode content = MAPPER.createObjectNode();
        List<String> images = new ArrayList<>();
        for (EmbeddingInput.Part part : input.getParts()) {
            switch (part.getType()) {
                case TEXT -> putOnce(content, "text", part.getValue());
                case IMAGE -> images.add(part.getValue());
                case VIDEO -> putOnce(content, "video", part.getValue());
            }
        }
        if (images.size() == 1) {
            content.put("image", images.get(0));
        } else if (images.size() > 1) {
            ArrayNode multiImages = content.putArray("multi_images");
            images.forEach(multiImages::add);
        }
        return content;
    }

    private void putOnce(ObjectNode content, String field, String value) {
        if (content.has(field)) {
            throw new IllegalArgumentException("An EmbeddingInput can contain at most one " + field + " part");
        }
        content.put(field, value);
    }

    private EmbeddingResult execute(ObjectNode body) {
        Request request = new Request.Builder()
                .url(baseUrl + API_PATH)
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + apiKey)
                .build();
        LLMRequestDebugLogger.log(requestDebugEnabled, request, body.toString());
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw OpenAIEmbeddingModel.apiException(response.code(), responseBody);
            return parseResponse(responseBody);
        } catch (IOException e) {
            throw new RuntimeException("Failed to call DashScope multimodal Embedding API", e);
        }
    }

    private EmbeddingResult parseResponse(String responseBody) throws IOException {
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode data = root.path("output").path("embeddings");
        if (!data.isArray() || data.isEmpty()) throw new RuntimeException("DashScope Embedding API returned no vectors");
        List<Embedding> embeddings = new ArrayList<>();
        for (JsonNode item : data) {
            embeddings.add(new Embedding(item.path("index").asInt(embeddings.size()),
                    item.has("type") ? item.path("type").asText() : null,
                    OpenAIEmbeddingModel.parseVector(item.path("embedding"))));
        }
        String requestId = root.has("request_id") ? root.path("request_id").asText() : null;
        return new EmbeddingResult(modelName, requestId, embeddings, OpenAIEmbeddingModel.parseUsage(root.path("usage")));
    }
}
