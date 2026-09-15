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
import ink.icoding.llm.core.tool.ToolParam;
import ink.icoding.llm.core.tool.ToolStatus;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI Responses API模型实现.
 * <p>通过SSE流式调用 {@code /v1/responses} 接口与OpenAI交互.
 * 支持文本生成、推理(reasoning_text)和工具调用(function_call).
 * 内置Agent循环: 自动处理工具调用并将结果反馈给LLM, 直到返回文本响应.</p>
 *
 * @author gsk
 */
public class OpenAIResponseModel implements LLMModel {
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
     * 构造OpenAI Responses模型实例.
     *
     * @param baseUrl   API基础地址, 如 https://api.openai.com
     * @param modelName 模型名称
     * @param apiKey    API密钥
     */
    public OpenAIResponseModel(String baseUrl, String modelName, String apiKey) {
        this(baseUrl, modelName, apiKey, false);
    }

    public OpenAIResponseModel(String baseUrl, String modelName, String apiKey, boolean requestDebugEnabled) {
        this(baseUrl, modelName, apiKey, requestDebugEnabled, null);
    }

    public OpenAIResponseModel(String baseUrl, String modelName, String apiKey,
                               boolean requestDebugEnabled, Boolean thinkingEnabled) {
        this(baseUrl, modelName, apiKey, requestDebugEnabled, thinkingEnabled, null);
    }

    public OpenAIResponseModel(String baseUrl, String modelName, String apiKey,
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
                    .url(baseUrl + "/v1/responses")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + apiKey)
                    .build();
            LLMRequestDebugLogger.log(requestDebugEnabled, request, body.toString());

            EventSource.Factory factory = EventSources.createFactory(client);
            factory.newEventSource(request, new EventSourceListener() {
                private final StringBuilder contentBuffer = new StringBuilder();
                private final StringBuilder thinkBuffer = new StringBuilder();
                private final Map<String, ToolCallEntry> toolCallEntries = new LinkedHashMap<>();
                private final List<ToolCallEntry> toolCalls = new ArrayList<>();
                private ToolCallEntry currentToolCall;
                private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
                private final AtomicBoolean turnHandled = new AtomicBoolean(false);

                private void finishCurrentTurn() {
                    if (!turnHandled.compareAndSet(false, true)) {
                        return;
                    }
                    if (!toolCalls.isEmpty()) {
                        handleToolCallsAndContinue(result, messages, tools, toolExecutor,
                                requestThinkingEnabled, requestTemperature,
                                contentBuffer.toString(), thinkBuffer.toString(), new ArrayList<>(toolCalls));
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
                        String eventType = json.has("type") ? json.get("type").asText() : "";

                        switch (eventType) {
                            case "response.output_text.delta" -> {
                                String text = json.get("delta").asText();
                                contentBuffer.append(text);
                                ResultHandler handler = result.getHandler();
                                if (handler != null) handler.onMessage(text);
                            }
                            case "response.output_text.done" -> {}
                            case "response.reasoning_text.delta", "response.reasoning_content.delta", "response.reasoning.delta" -> {
                                thinkBuffer.append(json.get("delta").asText());
                                ResultHandler handler = result.getHandler();
                                if (handler != null) handler.onThink(json.get("delta").asText());
                            }
                            case "response.output_item.added" -> {
                                ToolCallEntry entry = applyOutputItemEvent(json, toolCallEntries, currentToolCall);
                                if (entry != null) {
                                    currentToolCall = entry;
                                    notifyToolPreparing(result, entry);
                                }
                            }
                            case "response.function_call.start" -> {
                                ToolCallEntry entry = applyFunctionCallStartEvent(json, toolCallEntries);
                                currentToolCall = entry;
                                notifyToolPreparing(result, entry);
                            }
                            case "response.function_call_arguments.delta" -> {
                                currentToolCall = applyFunctionCallArgumentsDeltaEvent(json, toolCallEntries, currentToolCall);
                            }
                            case "response.function_call_arguments.done" -> {
                                currentToolCall = applyFunctionCallArgumentsDoneEvent(json, toolCallEntries, currentToolCall, toolCalls);
                            }
                            case "response.output_item.done" -> {
                                ToolCallEntry entry = applyOutputItemEvent(json, toolCallEntries, currentToolCall);
                                if (entry != null) {
                                    notifyToolPreparing(result, entry);
                                    addToolCallOnce(toolCalls, entry);
                                    currentToolCall = entry;
                                }
                            }
                            case "response.completed" -> {
                                applyCompletedResponseOutput(json, toolCallEntries, toolCalls, currentToolCall);
                                cancelRequested.set(true);
                                eventSource.cancel();
                                finishCurrentTurn();
                            }
                            case "error" -> {
                                String errorMsg = json.has("message") ? json.get("message").asText() : "Unknown error";
                                handleError(result, new RuntimeException("OpenAI Response API error: " + errorMsg));
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
                                             String content, String think, List<ToolCallEntry> toolCalls) {
        try {
            Message assistantMessage = Message.fromAssistant();
            if (content != null && !content.isEmpty()) {
                assistantMessage.appendContent(content);
            }
            if (think != null && !think.isEmpty()) {
                assistantMessage.appendThink(think);
            }
            for (ToolCallEntry entry : toolCalls) {
                assistantMessage.appendToolCall(entry.callId, entry.toolName, entry.argsJson);
            }
            if ((assistantMessage.getContent() != null && !assistantMessage.getContent().isEmpty())
                    || (assistantMessage.getThink() != null && !assistantMessage.getThink().isEmpty())
                    || (assistantMessage.getToolCalls() != null && !assistantMessage.getToolCalls().isEmpty())) {
                messages.add(assistantMessage);
                result.addAppendedMessage(assistantMessage);
            }

            // 执行每个工具调用并添加function_call_output
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

                Message toolMessage = Message.fromTool().withToolResult(entry.callId, toolResult);
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
     * 工具调用条目.
     */
    private static class ToolCallEntry {
        String itemId;
        String callId;
        String toolName;
        StringBuilder argsBuffer = new StringBuilder();
        String argsJson;
        boolean preparingNotified;
        boolean added;
    }

    private void notifyToolPreparing(LLMResult result, ToolCallEntry entry) {
        if (entry == null || entry.preparingNotified || entry.toolName == null || entry.toolName.isEmpty()) {
            return;
        }
        Tool tool = toolMap.get(entry.toolName);
        if (tool == null) {
            return;
        }
        ToolDescriptor descriptor = ToolDescriptor.fromTool(tool);
        descriptor.setCallId(entry.callId);
        ResultHandler handler = result.getHandler();
        if (handler != null) {
            entry.preparingNotified = true;
            handler.onTool(descriptor, ToolStatus.PREPARING, null);
        }
    }

    private static ToolCallEntry applyOutputItemEvent(JsonNode json,
                                                       Map<String, ToolCallEntry> toolCallEntries,
                                                       ToolCallEntry currentToolCall) {
        JsonNode item = json == null ? null : json.get("item");
        return applyFunctionCallItem(item, json, toolCallEntries, currentToolCall);
    }

    private static ToolCallEntry applyFunctionCallStartEvent(JsonNode json,
                                                              Map<String, ToolCallEntry> toolCallEntries) {
        ToolCallEntry entry = getOrCreateToolCallEntry(json, toolCallEntries, null);
        if (entry == null) {
            return null;
        }
        entry.callId = textValue(json.get("call_id"), entry.callId);
        entry.toolName = textValue(json.get("name"), entry.toolName);
        entry.argsBuffer.setLength(0);
        entry.argsJson = null;
        return entry;
    }

    private static ToolCallEntry findToolCallEntry(JsonNode json,
                                                    Map<String, ToolCallEntry> toolCallEntries,
                                                    ToolCallEntry currentToolCall) {
        return getOrCreateToolCallEntry(json, toolCallEntries, currentToolCall);
    }

    private static ToolCallEntry applyFunctionCallArgumentsDeltaEvent(JsonNode json,
                                                                       Map<String, ToolCallEntry> toolCallEntries,
                                                                       ToolCallEntry currentToolCall) {
        ToolCallEntry entry = findToolCallEntry(json, toolCallEntries, currentToolCall);
        if (entry != null && json.has("delta") && !json.get("delta").isNull()) {
            entry.argsBuffer.append(json.get("delta").asText());
        }
        return entry;
    }

    private static ToolCallEntry applyFunctionCallArgumentsDoneEvent(JsonNode json,
                                                                      Map<String, ToolCallEntry> toolCallEntries,
                                                                      ToolCallEntry currentToolCall,
                                                                      List<ToolCallEntry> toolCalls) {
        ToolCallEntry entry = findToolCallEntry(json, toolCallEntries, currentToolCall);
        if (entry == null) {
            return null;
        }
        if (json.has("arguments") && !json.get("arguments").isNull()) {
            entry.argsJson = json.get("arguments").asText();
        } else {
            entry.argsJson = entry.argsBuffer.toString();
        }
        addToolCallOnce(toolCalls, entry);
        return entry;
    }

    private static void applyCompletedResponseOutput(JsonNode json,
                                                      Map<String, ToolCallEntry> toolCallEntries,
                                                      List<ToolCallEntry> toolCalls,
                                                      ToolCallEntry currentToolCall) {
        JsonNode response = json == null ? null : json.get("response");
        JsonNode output = response == null ? null : response.get("output");
        if (output == null || !output.isArray()) {
            return;
        }
        for (JsonNode item : output) {
            ToolCallEntry entry = applyFunctionCallItem(item, json, toolCallEntries, currentToolCall);
            if (entry != null) {
                addToolCallOnce(toolCalls, entry);
            }
        }
    }

    private static ToolCallEntry applyFunctionCallItem(JsonNode item,
                                                        JsonNode event,
                                                        Map<String, ToolCallEntry> toolCallEntries,
                                                        ToolCallEntry currentToolCall) {
        if (item == null || item.isNull() || !"function_call".equals(textValue(item.get("type"), null))) {
            return null;
        }
        ToolCallEntry entry = getOrCreateToolCallEntry(item, toolCallEntries, currentToolCall);
        if (entry == null) {
            return null;
        }
        entry.itemId = textValue(item.get("id"), entry.itemId);
        entry.callId = textValue(item.get("call_id"), entry.callId);
        entry.toolName = textValue(item.get("name"), entry.toolName);
        if (item.has("arguments") && !item.get("arguments").isNull()) {
            entry.argsJson = item.get("arguments").asText();
            if (entry.argsJson != null && entry.argsBuffer.length() == 0) {
                entry.argsBuffer.append(entry.argsJson);
            }
        }
        rememberToolCallEntry(event, toolCallEntries, entry);
        rememberToolCallEntry(item, toolCallEntries, entry);
        return entry;
    }

    private static ToolCallEntry getOrCreateToolCallEntry(JsonNode json,
                                                           Map<String, ToolCallEntry> toolCallEntries,
                                                           ToolCallEntry currentToolCall) {
        String key = toolCallKey(json);
        if (key == null || key.isEmpty()) {
            return currentToolCall;
        }
        return toolCallEntries.computeIfAbsent(key, ignored -> {
            ToolCallEntry entry = new ToolCallEntry();
            if (key.startsWith("item:")) {
                entry.itemId = key.substring("item:".length());
            } else if (key.startsWith("call:")) {
                entry.callId = key.substring("call:".length());
            }
            return entry;
        });
    }

    private static void rememberToolCallEntry(JsonNode json,
                                               Map<String, ToolCallEntry> toolCallEntries,
                                               ToolCallEntry entry) {
        if (json == null || entry == null) {
            return;
        }
        String key = toolCallKey(json);
        if (key != null && !key.isEmpty()) {
            toolCallEntries.put(key, entry);
        }
    }

    private static String toolCallKey(JsonNode json) {
        if (json == null) {
            return null;
        }
        String itemId = textValue(json.get("item_id"), null);
        if (itemId == null) {
            itemId = textValue(json.get("id"), null);
        }
        if (itemId != null && !itemId.isEmpty()) {
            return "item:" + itemId;
        }
        String callId = textValue(json.get("call_id"), null);
        if (callId != null && !callId.isEmpty()) {
            return "call:" + callId;
        }
        JsonNode outputIndex = json.get("output_index");
        if (outputIndex != null && !outputIndex.isNull()) {
            return "output:" + outputIndex.asText();
        }
        return null;
    }

    private static void addToolCallOnce(List<ToolCallEntry> toolCalls, ToolCallEntry entry) {
        if (entry == null || entry.added) {
            return;
        }
        if (entry.argsJson == null) {
            entry.argsJson = entry.argsBuffer.toString();
        }
        entry.added = true;
        toolCalls.add(entry);
    }

    private static String textValue(JsonNode node, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        String text = node.asText();
        return text == null || text.isEmpty() ? fallback : text;
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
     * 构建OpenAI Responses请求体.
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
        body.put("stream", true);
        if (requestTemperature != null) {
            body.put("temperature", requestTemperature);
        }

        ArrayNode inputArray = MAPPER.createArrayNode();
        for (Message msg : messages) {
            if (appendNeutralMessage(inputArray, msg)) {
                continue;
            }
            if (isLegacyStructuredHistory(msg)) {
                continue;
            }
            inputArray.add(buildTextItem(msg));
        }
        body.set("input", inputArray);

        if (!tools.isEmpty()) {
            ArrayNode toolsArray = MAPPER.createArrayNode();
            for (ToolDescriptor desc : tools) {
                ObjectNode toolNode = MAPPER.createObjectNode();
                toolNode.put("type", "function");
                toolNode.put("name", desc.getName());
                toolNode.put("description", desc.getDescription());
                JsonNode openaiSchema = MAPPER.valueToTree(desc.toLLMContent(ink.icoding.llm.core.entity.ModelType.OpenAIResponse));
                toolNode.set("parameters", openaiSchema.get("function").get("parameters"));
                toolsArray.add(toolNode);
            }
            body.set("tools", toolsArray);
        }
        applyThinkingOptions(body, requestThinkingEnabled);

        return body;
    }

    private void applyThinkingOptions(ObjectNode body, Boolean requestThinkingEnabled) {
        if (requestThinkingEnabled == null) {
            return;
        }
        ObjectNode reasoning = MAPPER.createObjectNode();
        reasoning.put("effort", requestThinkingEnabled ? "medium" : "none");
        body.set("reasoning", reasoning);
    }

    private boolean appendNeutralMessage(ArrayNode inputArray, Message msg) {
        boolean appended = false;
        if (msg.getRole() == Message.Role.assistant
                && msg.getContent() != null && !msg.getContent().isEmpty()
                && !looksLikeStructuredJson(msg.getContent())) {
            inputArray.add(buildTextItem(msg));
            appended = true;
        }
        if (msg.getRole() == Message.Role.assistant
                && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            for (MessageToolCall call : msg.getToolCalls()) {
                ObjectNode callItem = MAPPER.createObjectNode();
                callItem.put("type", "function_call");
                callItem.put("call_id", call.getId());
                callItem.put("name", call.getName());
                callItem.put("arguments", call.getArgumentsJson());
                inputArray.add(callItem);
            }
            appended = true;
        }
        if (msg.getRole() == Message.Role.tool && msg.getToolResult() != null) {
            ObjectNode outputItem = MAPPER.createObjectNode();
            outputItem.put("type", "function_call_output");
            outputItem.put("call_id", msg.getToolResult().getToolCallId());
            outputItem.put("output", msg.getToolResult().getContent());
            inputArray.add(outputItem);
            appended = true;
        }
        return appended;
    }

    private boolean looksLikeStructuredJson(String content) {
        return content != null && content.stripLeading().startsWith("{");
    }

    private boolean isLegacyStructuredHistory(Message msg) {
        return msg.getRole() != Message.Role.user
                && looksLikeStructuredJson(msg.getContent());
    }

    /**
     * 构建文本输入项.
     */
    private ObjectNode buildTextItem(Message msg) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("role", msg.getRole().name());
        if (isMiMoModel() && msg.getRole() == Message.Role.assistant && msg.getThink() != null && !msg.getThink().isEmpty()) {
            item.put("reasoning_content", msg.getThink());
        }
        String textType = msg.getRole() == Message.Role.assistant ? "output_text" : "input_text";

        if (msg.getAttachments() != null && !msg.getAttachments().isEmpty()) {
            ArrayNode contentArray = MAPPER.createArrayNode();
            if (msg.getContent() != null) {
                ObjectNode textPart = MAPPER.createObjectNode();
                textPart.put("type", textType);
                textPart.put("text", msg.getContent());
                contentArray.add(textPart);
            }
            for (MessageAttachment attachment : msg.getAttachments()) {
                ObjectNode imagePart = MAPPER.createObjectNode();
                imagePart.put("type", "input_image");
                imagePart.put("image_url", "data:" + attachment.getContentType() + ";base64," +
                        Base64.getEncoder().encodeToString(attachment.getData()));
                contentArray.add(imagePart);
            }
            item.set("content", contentArray);
        } else {
            ArrayNode contentArray = MAPPER.createArrayNode();
            if (msg.getContent() != null) {
                ObjectNode textPart = MAPPER.createObjectNode();
                textPart.put("type", textType);
                textPart.put("text", msg.getContent());
                contentArray.add(textPart);
            }
            item.set("content", contentArray);
        }
        return item;
    }

    private TokenUsage parseTokenUsage(JsonNode json) {
        JsonNode usage = json.get("usage");
        if (usage == null || usage.isNull()) {
            JsonNode response = json.get("response");
            if (response != null && !response.isNull()) {
                usage = response.get("usage");
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
        int cached = firstInt(usage, "cached_tokens", "prompt_cache_hit_tokens", "cache_hit_tokens");
        cached = Math.max(cached, nestedFirstInt(usage, "input_tokens_details", "cached_tokens"));
        cached = Math.max(cached, nestedFirstInt(usage, "prompt_tokens_details", "cached_tokens"));
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

    private int nestedFirstInt(JsonNode node, String objectName, String... names) {
        JsonNode child = node.get(objectName);
        return child == null || child.isNull() ? 0 : firstInt(child, names);
    }

    /**
     * 处理错误, 将异常传递给错误回调处理器.
     */
    private void handleError(LLMResult result, Throwable t) {
        result.fail(t);
    }

    private boolean isMiMoModel() {
        return modelName != null && modelName.regionMatches(true, 0, "MiMo", 0, 4);
    }
}
