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

/** OpenAI 兼容的文本 Embedding 模型实现。 */
public class OpenAIEmbeddingModel implements EmbeddingModel {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String baseUrl;
    private final String modelName;
    private final String apiKey;
    private final OkHttpClient client;
    private boolean requestDebugEnabled;
    private Integer dimensions;

    public OpenAIEmbeddingModel(String baseUrl, String modelName, String apiKey) {
        this(baseUrl, modelName, apiKey, false);
    }

    public OpenAIEmbeddingModel(String baseUrl, String modelName, String apiKey, boolean requestDebugEnabled) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.modelName = requireValue(modelName, "modelName");
        this.apiKey = requireValue(apiKey, "apiKey");
        this.requestDebugEnabled = requestDebugEnabled;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public EmbeddingResult embed(String text) {
        return embedTexts(List.of(requireValue(text, "text")));
    }

    @Override
    public EmbeddingResult embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("texts must not be empty");
        }
        return execute(buildRequestBody(texts));
    }

    private ObjectNode buildRequestBody(List<String> texts) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", modelName);
        if (dimensions != null) body.put("dimensions", dimensions);
        ArrayNode input = body.putArray("input");
        for (String text : texts) input.add(requireValue(text, "text"));
        return body;
    }

    @Override
    public EmbeddingResult embed(EmbeddingInput input) {
        return embedInputs(List.of(requireInput(input)));
    }

    @Override
    public EmbeddingResult embedInputs(List<EmbeddingInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("inputs must not be empty");
        }
        List<String> texts = new ArrayList<>(inputs.size());
        for (EmbeddingInput input : inputs) {
            EmbeddingInput validInput = requireInput(input);
            if (validInput.hasMultimedia() || validInput.getParts().size() != 1
                    || validInput.getParts().get(0).getType() != EmbeddingInput.PartType.TEXT) {
                throw new UnsupportedOperationException(
                        "OpenAI-compatible Embedding API supports text only; use DashScopeMultimodalEmbedding for images or videos");
            }
            texts.add(validInput.getParts().get(0).getValue());
        }
        return embedTexts(texts);
    }

    @Override
    public void setRequestDebugEnabled(boolean enabled) { this.requestDebugEnabled = enabled; }
    @Override
    public boolean isRequestDebugEnabled() { return requestDebugEnabled; }
    @Override
    public void setDimensions(Integer dimensions) { this.dimensions = validateDimensions(dimensions); }
    @Override
    public Integer getDimensions() { return dimensions; }

    private EmbeddingResult execute(ObjectNode body) {
        Request request = new Request.Builder()
                .url(baseUrl + "/v1/embeddings")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .header("Authorization", "Bearer " + apiKey)
                .build();
        LLMRequestDebugLogger.log(requestDebugEnabled, request, body.toString());
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw apiException(response.code(), responseBody);
            return parseResponse(responseBody);
        } catch (IOException e) {
            throw new RuntimeException("Failed to call OpenAI-compatible Embedding API", e);
        }
    }

    private EmbeddingResult parseResponse(String responseBody) throws IOException {
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) throw new RuntimeException("Embedding API returned no vectors");
        List<Embedding> embeddings = new ArrayList<>();
        for (JsonNode item : data) {
            embeddings.add(new Embedding(item.path("index").asInt(embeddings.size()), null,
                    parseVector(item.path("embedding"))));
        }
        return new EmbeddingResult(root.path("model").asText(modelName), null, embeddings, parseUsage(root.path("usage")));
    }

    static List<Double> parseVector(JsonNode vector) {
        if (!vector.isArray() || vector.isEmpty()) throw new RuntimeException("Embedding API returned an invalid vector");
        List<Double> values = new ArrayList<>(vector.size());
        for (JsonNode value : vector) values.add(value.asDouble());
        return values;
    }

    static TokenUsage parseUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) return null;
        int input = usage.has("prompt_tokens") ? usage.path("prompt_tokens").asInt()
                : usage.path("input_tokens").asInt();
        int total = usage.has("total_tokens") ? usage.path("total_tokens").asInt() : input;
        return new TokenUsage(input, 0, total);
    }

    static IllegalArgumentException apiException(int status, String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            String message = root.path("error").path("message").asText(root.path("message").asText(responseBody));
            return new IllegalArgumentException("Embedding API request failed (HTTP " + status + "): " + message);
        } catch (IOException ignored) {
            return new IllegalArgumentException("Embedding API request failed (HTTP " + status + "): " + responseBody);
        }
    }

    static String normalizeBaseUrl(String baseUrl) {
        String url = requireValue(baseUrl, "baseUrl");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    static String requireValue(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    static EmbeddingInput requireInput(EmbeddingInput input) {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("input must not be empty");
        return input;
    }

    static Integer validateDimensions(Integer dimensions) {
        if (dimensions != null && dimensions <= 0) throw new IllegalArgumentException("dimensions must be positive");
        return dimensions;
    }
}
