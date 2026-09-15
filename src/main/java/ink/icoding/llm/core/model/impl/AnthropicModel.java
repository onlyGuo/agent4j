package ink.icoding.llm.core.model.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ink.icoding.llm.core.entity.Message;
import ink.icoding.llm.core.entity.MessageAttachment;
import ink.icoding.llm.core.entity.MessageToolCall;
import ink.icoding.llm.core.model.LLMModel;
import ink.icoding.llm.core.model.LLMRequestDebugLogger;
import ink.icoding.llm.core.model.LLMResult;
import ink.icoding.llm.core.model.ResultHandler;
import ink.icoding.llm.core.model.TokenUsage;
import ink.icoding.llm.core.tool.Tool;
import ink.icoding.llm.core.tool.ToolDescriptor;
import ink.icoding.llm.core.tool.ToolExecutor;
import ink.icoding.llm.core.tool.ToolStatus;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Anthropic Messages API模型实现.
 * <p>通过SSE流式调用 {@code /v1/messages} 接口与Anthropic Claude模型交互.
 * 支持文本生成、思考(thinking)和工具调用(tool_use).
 * 内置Agent循环: 自动处理工具调用并将结果反馈给LLM, 直到返回文本响应.</p>
 *
 * @author gsk
 */
public class AnthropicModel implements LLMModel {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String baseUrl;
    private final String modelName;
    private final String apiKey;
    private final OkHttpClient client;
    private final Map<String, Tool> toolMap = new ConcurrentHashMap<>();
    private boolean requestDebugEnabled;
    private Boolean thinkingEnabled;
    private Double temperature;

    /**
     * 构造Anthropic模型实例.
     *
     * @param baseUrl   API基础地址, 如 https://api.anthropic.com
     * @param modelName 模型名称, 如 claude-sonnet-4-20250514
     * @param apiKey    API密钥
     */
    public AnthropicModel(String baseUrl, String modelName, String apiKey) {
        this(baseUrl, modelName, apiKey, false);
    }

    public AnthropicModel(String baseUrl, String modelName, String apiKey, boolean requestDebugEnabled) {
        this(baseUrl, modelName, apiKey, requestDebugEnabled, null);
    }

    public AnthropicModel(String baseUrl, String modelName, String apiKey,
                          boolean requestDebugEnabled, Boolean thinkingEnabled) {
        this(baseUrl, modelName, apiKey, requestDebugEnabled, thinkingEnabled, null);
    }

    public AnthropicModel(String baseUrl, String modelName, String apiKey,
                          boolean requestDebugEnabled, Boolean thinkingEnabled, Double temperature) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.modelName = modelName;
        this.apiKey = apiKey;
        this.requestDebugEnabled = requestDebugEnabled;
        this.thinkingEnabled = thinkingEnabled;
        this.temperature = temperature;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void setRequestDebugEnabled(boolean enabled) {
        this.requestDebugEnabled = enabled;
    }

    @Override
    public boolean isRequestDebugEnabled() {
        return requestDebugEnabled;
    }

    @Override
    public void setThinkingEnabled(Boolean enabled) {
        this.thinkingEnabled = enabled;
    }

    @Override
    public Boolean getThinkingEnabled() {
        return thinkingEnabled;
    }

    @Override
    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    @Override
    public Double getTemperature() {
        return temperature;
    }

    /** {@inheritDoc} */
    @Override
    public LLMResult ask(Message message) {
        return ask(List.of(message));
    }

    /** {@inheritDoc} */
    @Override
    public LLMResult ask(List<Message> messages) {
        return ask(messages, List.of());
    }

    /** {@inheritDoc} */
    @Override
    public LLMResult ask(List<Message> messages, List<Tool> tools) {
        return ask(messages, tools, (toolName, paramJson, descriptor, handler) -> {
            Tool tool = toolMap.get(toolName);
            if (tool == null) throw new RuntimeException("Tool not found: " + toolName);
            return ToolExecutor.defaultExecute(tool, paramJson, descriptor, handler);
        });
    }

    /** {@inheritDoc} */
    @Override
    public LLMResult ask(List<Message> messages, List<Tool> tools, ToolExecutor toolExecutor) {
        return ask(messages, tools, toolExecutor, thinkingEnabled, temperature);
    }

    /** {@inheritDoc} */
    @Override
    public LLMResult ask(List<Message> messages, List<Tool> tools, ToolExecutor toolExecutor,
                         Boolean requestThinkingEnabled) {
        return ask(messages, tools, toolExecutor, requestThinkingEnabled, temperature);
    }

    /** {@inheritDoc} */
    @Override
    public LLMResult ask(List<Message> messages, List<Tool> tools, ToolExecutor toolExecutor,
                         Boolean requestThinkingEnabled, Double requestTemperature) {
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (Tool tool : tools) {
            ToolDescriptor desc = ToolDescriptor.fromTool(tool);
            descriptors.add(desc);
            toolMap.put(desc.getName(), tool);
        }

        return new LLMResult(r -> executeAgentLoop(r, new ArrayList<>(messages), descriptors,
                toolExecutor, requestThinkingEnabled, requestTemperature));
    }

    /**
     * 执行Agent循环: LLM -> 工具调用 -> 反馈结果 -> LLM, 直到返回文本响应.
     */
    private void executeAgentLoop(LLMResult result, List<Message> messages,
                                   List<ToolDescriptor> tools, ToolExecutor toolExecutor,
                                   Boolean requestThinkingEnabled, Double requestTemperature) {
        try {
            ObjectNode body = buildRequestBody(messages, tools, requestThinkingEnabled, requestTemperature);
            Request request = new Request.Builder()
                    .url(baseUrl + "/v1/messages")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .build();
            LLMRequestDebugLogger.log(requestDebugEnabled, request, body.toString());

            EventSource.Factory factory = EventSources.createFactory(client);
            factory.newEventSource(request, new EventSourceListener() {
                private String currentBlockType;
                private String currentToolId;
                private String currentToolName;
                private final StringBuilder toolInputBuffer = new StringBuilder();
                private final StringBuilder contentBuffer = new StringBuilder();
                private final StringBuilder thinkBuffer = new StringBuilder();
                private final StringBuilder thinkSignatureBuffer = new StringBuilder();
                private final List<ToolCallEntry> toolCalls = new ArrayList<>();
                private String stopReason;
                private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
                private final AtomicBoolean turnHandled = new AtomicBoolean(false);

                private void finishCurrentTurn() {
                    if (!turnHandled.compareAndSet(false, true)) {
                        return;
                    }
                    if (shouldContinueWithToolCalls(stopReason, toolCalls)) {
                        handleToolCallsAndContinue(result, messages, tools, toolExecutor,
                                requestThinkingEnabled, requestTemperature,
                                contentBuffer.toString(), thinkBuffer.toString(),
                                thinkSignatureBuffer.toString(), new ArrayList<>(toolCalls));
                    } else {
                        addFinalAssistantMessage(result, contentBuffer.toString(), thinkBuffer.toString());
                        result.complete(contentBuffer.toString());
                    }
                }

                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    if (cancelRequested.get()) return;
                    LLMRequestDebugLogger.logStreamEvent(requestDebugEnabled, id, type, data);
                    try {
                        JsonNode json = MAPPER.readTree(data);
                        TokenUsage usage = parseTokenUsage(json);
                        if (usage != null) {
                            result.addUsage(usage);
                        }
                        String eventType = json.has("type") ? json.get("type").asText() : type;

                        switch (eventType) {
                            case "content_block_start" -> {
                                JsonNode contentBlock = json.get("content_block");
                                if (contentBlock != null) {
                                    currentBlockType = contentBlock.get("type").asText();
                                    if ("tool_use".equals(currentBlockType)) {
                                        currentToolId = contentBlock.get("id").asText();
                                        currentToolName = contentBlock.get("name").asText();
                                        toolInputBuffer.setLength(0);
                                        notifyToolPreparing(result, currentToolName, currentToolId);
                                    } else if ("thinking".equals(currentBlockType) && contentBlock.has("signature")) {
                                        thinkSignatureBuffer.append(contentBlock.get("signature").asText());
                                    }
                                }
                            }
                            case "content_block_delta" -> {
                                JsonNode delta = json.get("delta");
                                if (delta != null) {
                                    String deltaType = delta.get("type").asText();
                                    ResultHandler handler = result.getHandler();
                                    switch (deltaType) {
                                        case "thinking_delta" -> {
                                            String thinking = delta.get("thinking").asText();
                                            thinkBuffer.append(thinking);
                                            if (handler != null) handler.onThink(thinking);
                                        }
                                        case "signature_delta" -> {
                                            if (delta.has("signature")) {
                                                thinkSignatureBuffer.append(delta.get("signature").asText());
                                            }
                                        }
                                        case "text_delta" -> {
                                            String text = delta.get("text").asText();
                                            contentBuffer.append(text);
                                            if (handler != null) handler.onMessage(text);
                                        }
                                        case "input_json_delta" -> {
                                            toolInputBuffer.append(delta.get("partial_json").asText());
                                        }
                                    }
                                }
                            }
                            case "content_block_stop" -> {
                                if ("tool_use".equals(currentBlockType)) {
                                    ToolCallEntry entry = new ToolCallEntry();
                                    entry.callId = currentToolId;
                                    entry.toolName = currentToolName;
                                    entry.argsJson = toolInputBuffer.toString();
                                    toolCalls.add(entry);
                                }
                                currentBlockType = null;
                            }
                            case "message_delta" -> {
                                JsonNode delta = json.get("delta");
                                if (delta != null && delta.has("stop_reason")) {
                                    stopReason = delta.get("stop_reason").asText();
                                }
                            }
                            case "message_stop" -> {
                                cancelRequested.set(true);
                                eventSource.cancel();
                                finishCurrentTurn();
                            }
                            case "error" -> {
                                String errorMsg = json.has("message") ? json.get("message").asText() : "Unknown error";
                                handleError(result, new RuntimeException("Anthropic API error: " + errorMsg));
                            }
                        }
                    } catch (Exception e) {
                        handleError(result, e);
                    }
                }

                @Override
                public void onFailure(EventSource eventSource, Throwable t, Response response) {
                    // This stream was deliberately cancelled after its terminal event.
                    // Its callbacks may arrive while a subsequent tool turn is running.
                    if (cancelRequested.get()) {
                        return;
                    }
                    String errMsg = "SSE connection failed";
                    if (response != null) {
                        errMsg += ": HTTP " + response.code();
                        if (!response.isSuccessful()) {
                            try {
                                String body = response.body() != null ? response.body().string() : "";
                                if (!body.isEmpty()) errMsg += ": " + body;
                            } catch (Exception ignored) {}
                        }
                    }
                    if (t != null) {
                        errMsg += ": " + t;
                    }
                    handleError(result, new RuntimeException(errMsg, t));
                }

                @Override
                public void onClosed(EventSource eventSource) {}
            });
        } catch (Exception e) {
            handleError(result, e);
        }
    }

    /**
     * 处理工具调用并继续Agent循环.
     */
    private void handleToolCallsAndContinue(LLMResult result, List<Message> messages,
                                             List<ToolDescriptor> tools, ToolExecutor toolExecutor,
                                             Boolean requestThinkingEnabled, Double requestTemperature,
                                             String content, String think, String thinkSignature,
                                             List<ToolCallEntry> toolCalls) {
        try {
            // 添加协议无关的assistant消息
            Message assistantMessage = Message.fromAssistant();
            if (content != null && !content.isEmpty()) {
                assistantMessage.appendContent(content);
            }
            if (think != null && !think.isEmpty()) {
                assistantMessage.appendThink(think);
            }
            if (thinkSignature != null && !thinkSignature.isEmpty()) {
                assistantMessage.setThinkSignature(thinkSignature);
            }
            for (ToolCallEntry entry : toolCalls) {
                assistantMessage.appendToolCall(entry.callId, entry.toolName, entry.argsJson);
            }
            messages.add(assistantMessage);
            result.addAppendedMessage(assistantMessage);

            // 执行每个工具调用
            List<String> toolResults = new ArrayList<>();
            for (ToolCallEntry entry : toolCalls) {
                ToolDescriptor descriptor = ToolDescriptor.fromTool(toolMap.get(entry.toolName));
                descriptor.setCallId(entry.callId);
                descriptor.setInputParams(entry.argsJson);

                ResultHandler toolHandler = suppressPreparing(result.getHandler());
                String toolResult;
                try {
                    toolResult = toolExecutor.execute(entry.toolName, entry.argsJson, descriptor, toolHandler);
                } catch (Exception e) {
                    toolResult = ToolExecutor.handleToolError(descriptor, toolHandler, e);
                }
                toolResults.add(toolResult);
            }

            for (int i = 0; i < toolCalls.size(); i++) {
                Message toolMessage = Message.fromTool().withToolResult(toolCalls.get(i).callId, toolResults.get(i));
                messages.add(toolMessage);
                result.addAppendedMessage(toolMessage);
            }

            // 继续Agent循环
            executeAgentLoop(result, messages, tools, toolExecutor,
                    requestThinkingEnabled, requestTemperature);
        } catch (Exception e) {
            handleError(result, e);
        }
    }

    /**
     * 构建assistant消息JSON.
     */
    private String buildAssistantMessage(String content, String think, String thinkSignature, List<ToolCallEntry> toolCalls) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "assistant");
        ArrayNode contentArray = MAPPER.createArrayNode();
        if ((isMiMoModel() && think != null && !think.isEmpty()) || (thinkSignature != null && !thinkSignature.isEmpty())) {
            ObjectNode thinkBlock = MAPPER.createObjectNode();
            thinkBlock.put("type", "thinking");
            if (think != null && !think.isEmpty()) {
                thinkBlock.put("thinking", think);
            }
            if (thinkSignature != null && !thinkSignature.isEmpty()) {
                thinkBlock.put("signature", thinkSignature);
            }
            contentArray.add(thinkBlock);
        }
        if (content != null && !content.isEmpty()) {
            ObjectNode textBlock = MAPPER.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", content);
            contentArray.add(textBlock);
        }
        for (ToolCallEntry entry : toolCalls) {
            ObjectNode toolBlock = MAPPER.createObjectNode();
            toolBlock.put("type", "tool_use");
            toolBlock.put("id", entry.callId);
            toolBlock.put("name", entry.toolName);
            toolBlock.set("input", MAPPER.valueToTree(parseJsonSafe(entry.argsJson)));
            contentArray.add(toolBlock);
        }
        msg.set("content", contentArray);
        return msg.toString();
    }

    /**
     * 构建工具结果消息JSON.
     */
    private String buildToolResultMessage(List<ToolCallEntry> toolCalls, List<String> results) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "user");
        ArrayNode contentArray = MAPPER.createArrayNode();
        for (int i = 0; i < toolCalls.size(); i++) {
            ObjectNode resultBlock = MAPPER.createObjectNode();
            resultBlock.put("type", "tool_result");
            resultBlock.put("tool_use_id", toolCalls.get(i).callId);
            resultBlock.put("content", results.get(i));
            contentArray.add(resultBlock);
        }
        msg.set("content", contentArray);
        return msg.toString();
    }

    private Object parseJsonSafe(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /**
     * 工具调用条目.
     */
    private static class ToolCallEntry {
        String callId;
        String toolName;
        String argsJson;
    }

    private void notifyToolPreparing(LLMResult result, String toolName, String callId) {
        if (toolName == null || toolName.isEmpty()) {
            return;
        }
        Tool tool = toolMap.get(toolName);
        if (tool == null) {
            return;
        }
        ToolDescriptor descriptor = ToolDescriptor.fromTool(tool);
        descriptor.setCallId(callId);
        ResultHandler handler = result.getHandler();
        if (handler != null) {
            handler.onTool(descriptor, ToolStatus.PREPARING, null);
        }
    }

    private ResultHandler suppressPreparing(ResultHandler handler) {
        if (handler == null) {
            return null;
        }
        return new ResultHandler() {
            @Override
            public void onMessage(String message) {
                handler.onMessage(message);
            }

            @Override
            public void onThink(String think) {
                handler.onThink(think);
            }

            @Override
            public void onTool(ToolDescriptor tool, ToolStatus status, Object toolResult) {
                if (status != ToolStatus.PREPARING) {
                    handler.onTool(tool, status, toolResult);
                }
            }

            @Override
            public void onUsage(TokenUsage usage) {
                handler.onUsage(usage);
            }

            @Override
            public void onToolError(ToolDescriptor tool, Exception error) {
                handler.onToolError(tool, error);
            }
        };
    }

    private void addFinalAssistantMessage(LLMResult result, String content, String think) {
        if ((content == null || content.isEmpty()) && (think == null || think.isEmpty())) {
            return;
        }
        Message assistantMessage = Message.fromAssistant();
        if (content != null && !content.isEmpty()) {
            assistantMessage.appendContent(content);
        }
        if (think != null && !think.isEmpty()) {
            assistantMessage.appendThink(think);
        }
        result.addAppendedMessage(assistantMessage);
    }

    /**
     * 构建Anthropic Messages请求体.
     */
    private ObjectNode buildRequestBody(List<Message> messages, List<ToolDescriptor> tools) {
        return buildRequestBody(messages, tools, thinkingEnabled, temperature);
    }

    private ObjectNode buildRequestBody(List<Message> messages, List<ToolDescriptor> tools,
                                        Boolean requestThinkingEnabled) {
        return buildRequestBody(messages, tools, requestThinkingEnabled, temperature);
    }

    private ObjectNode buildRequestBody(List<Message> messages, List<ToolDescriptor> tools,
                                        Boolean requestThinkingEnabled, Double requestTemperature) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", modelName);
        body.put("max_tokens", 4096);
        body.put("stream", true);
        if (requestTemperature != null) {
            body.put("temperature", requestTemperature);
        }

        ArrayNode messagesArray = MAPPER.createArrayNode();
        for (Message msg : messages) {
            if (appendNeutralMessage(messagesArray, msg)) {
                continue;
            }
            if (isLegacyStructuredHistory(msg)) {
                continue;
            }

            ObjectNode msgNode = MAPPER.createObjectNode();
            msgNode.put("role", msg.getRole() == Message.Role.tool ? "user" : msg.getRole().name());

            boolean includeThinking = isMiMoModel() && msg.getRole() == Message.Role.assistant
                    && msg.getThink() != null && !msg.getThink().isEmpty();
            if (includeThinking || (msg.getAttachments() != null && !msg.getAttachments().isEmpty())) {
                ArrayNode contentArray = MAPPER.createArrayNode();
                if (includeThinking) {
                    ObjectNode thinkPart = MAPPER.createObjectNode();
                    thinkPart.put("type", "thinking");
                    thinkPart.put("thinking", msg.getThink());
                    contentArray.add(thinkPart);
                }
                if (msg.getContent() != null) {
                    ObjectNode textPart = MAPPER.createObjectNode();
                    textPart.put("type", "text");
                    textPart.put("text", msg.getContent());
                    contentArray.add(textPart);
                }
                for (MessageAttachment attachment : msg.getAttachments()) {
                    ObjectNode imagePart = MAPPER.createObjectNode();
                    imagePart.put("type", "image");
                    ObjectNode source = MAPPER.createObjectNode();
                    source.put("type", "base64");
                    source.put("media_type", attachment.getContentType());
                    source.put("data", Base64.getEncoder().encodeToString(attachment.getData()));
                    imagePart.set("source", source);
                    contentArray.add(imagePart);
                }
                msgNode.set("content", contentArray);
            } else {
                msgNode.put("content", msg.getContent());
            }
            messagesArray.add(msgNode);
        }
        body.set("messages", messagesArray);

        if (!tools.isEmpty()) {
            ArrayNode toolsArray = MAPPER.createArrayNode();
            for (ToolDescriptor desc : tools) {
                toolsArray.add(MAPPER.valueToTree(desc.toLLMContent(ink.icoding.llm.core.entity.ModelType.Anthropic)));
            }
            body.set("tools", toolsArray);
        }
        applyThinkingOptions(body, requestThinkingEnabled);

        return body;
    }

    private void applyThinkingOptions(ObjectNode body, Boolean requestThinkingEnabled) {
        if (!Boolean.TRUE.equals(requestThinkingEnabled)) {
            return;
        }
        ObjectNode thinking = MAPPER.createObjectNode();
        thinking.put("type", "enabled");
        thinking.put("budget_tokens", 1024);
        body.set("thinking", thinking);
    }

    private boolean appendNeutralMessage(ArrayNode messagesArray, Message msg) {
        if (msg.getRole() == Message.Role.assistant
                && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            ObjectNode msgNode = MAPPER.createObjectNode();
            msgNode.put("role", "assistant");
            ArrayNode contentArray = MAPPER.createArrayNode();
            appendThinkingBlock(contentArray, msg.getThink(), msg.getThinkSignature());
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                ObjectNode textBlock = MAPPER.createObjectNode();
                textBlock.put("type", "text");
                textBlock.put("text", msg.getContent());
                contentArray.add(textBlock);
            }
            for (MessageToolCall call : msg.getToolCalls()) {
                appendToolUseBlock(contentArray, call.getId(), call.getName(), call.getArgumentsJson());
            }
            msgNode.set("content", contentArray);
            messagesArray.add(msgNode);
            return true;
        }
        if (msg.getRole() == Message.Role.tool && msg.getToolResult() != null) {
            ObjectNode msgNode = MAPPER.createObjectNode();
            msgNode.put("role", "user");
            ArrayNode contentArray = MAPPER.createArrayNode();
            appendToolResultBlock(contentArray, msg.getToolResult().getToolCallId(), msg.getToolResult().getContent());
            msgNode.set("content", contentArray);
            messagesArray.add(msgNode);
            return true;
        }
        return false;
    }

    private boolean isLegacyStructuredHistory(Message msg) {
        return msg.getRole() != Message.Role.user
                && msg.getContent() != null
                && msg.getContent().stripLeading().startsWith("{");
    }

    private void appendThinkingBlock(ArrayNode contentArray, String think, String signature) {
        if ((!isMiMoModel() || think == null || think.isEmpty()) && (signature == null || signature.isEmpty())) {
            return;
        }
        ObjectNode thinkBlock = MAPPER.createObjectNode();
        thinkBlock.put("type", "thinking");
        if (think != null && !think.isEmpty()) {
            thinkBlock.put("thinking", think);
        }
        if (signature != null && !signature.isEmpty()) {
            thinkBlock.put("signature", signature);
        }
        contentArray.add(thinkBlock);
    }

    private void appendToolUseBlock(ArrayNode contentArray, String id, String name, String argumentsJson) {
        ObjectNode toolBlock = MAPPER.createObjectNode();
        toolBlock.put("type", "tool_use");
        toolBlock.put("id", id);
        toolBlock.put("name", name);
        toolBlock.set("input", MAPPER.valueToTree(parseJsonSafe(argumentsJson)));
        contentArray.add(toolBlock);
    }

    private void appendToolResultBlock(ArrayNode contentArray, String toolCallId, String content) {
        ObjectNode resultBlock = MAPPER.createObjectNode();
        resultBlock.put("type", "tool_result");
        resultBlock.put("tool_use_id", toolCallId);
        resultBlock.put("content", content);
        contentArray.add(resultBlock);
    }

    private TokenUsage parseTokenUsage(JsonNode json) {
        JsonNode usage = json.get("usage");
        if (usage == null || usage.isNull()) {
            JsonNode message = json.get("message");
            if (message != null && !message.isNull()) {
                usage = message.get("usage");
            }
        }
        if (usage == null || usage.isNull()) {
            JsonNode delta = json.get("delta");
            if (delta != null && !delta.isNull()) {
                usage = delta.get("usage");
            }
        }
        if (usage == null || usage.isNull()) {
            usage = json.get("used");
        }
        if (usage == null || usage.isNull()) {
            return null;
        }
        if (usage.isNumber()) {
            return new TokenUsage(usage.asInt(), 0, usage.asInt());
        }
        int input = firstInt(usage, "input_tokens", "prompt_tokens");
        int output = firstInt(usage, "output_tokens", "completion_tokens");
        int total = firstInt(usage, "total_tokens", "used");
        int cached = firstInt(usage, "cache_read_input_tokens", "cached_tokens",
                "prompt_cache_hit_tokens", "cache_hit_tokens");
        if (input <= 0 && output <= 0 && total <= 0 && cached <= 0) {
            return null;
        }
        return new TokenUsage(input, output, total, cached);
    }

    private int firstInt(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) {
                return value.asInt();
            }
        }
        return 0;
    }

    /**
     * 处理错误, 将异常传递给错误回调处理器.
     */
    private void handleError(LLMResult result, Throwable t) {
        result.fail(t);
    }

    private static boolean shouldContinueWithToolCalls(String stopReason, List<ToolCallEntry> toolCalls) {
        return "tool_use".equals(stopReason) && toolCalls != null && !toolCalls.isEmpty();
    }

    private boolean isMiMoModel() {
        return modelName != null && modelName.regionMatches(true, 0, "MiMo", 0, 4);
    }
}
