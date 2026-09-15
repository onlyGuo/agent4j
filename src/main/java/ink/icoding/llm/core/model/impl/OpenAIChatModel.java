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

/**
 * OpenAI Chat Completions API模型实现.
 * <p>通过SSE流式调用 {@code /v1/chat/completions} 接口与OpenAI兼容的LLM交互.
 * 支持文本生成、思考推理(reasoning_content)和工具调用(function_call).
 * 内置Agent循环: 自动处理工具调用并将结果反馈给LLM, 直到返回文本响应.</p>
 *
 * @author gsk
 */
public class OpenAIChatModel implements LLMModel {
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
     * 构造OpenAI Chat模型实例.
     *
     * @param baseUrl   API基础地址, 如 https://api.openai.com
     * @param modelName 模型名称, 如 gpt-4o
     * @param apiKey    API密钥
     */
    public OpenAIChatModel(String baseUrl, String modelName, String apiKey) {
        this(baseUrl, modelName, apiKey, false);
    }

    public OpenAIChatModel(String baseUrl, String modelName, String apiKey, boolean requestDebugEnabled) {
        this(baseUrl, modelName, apiKey, requestDebugEnabled, null);
    }

    public OpenAIChatModel(String baseUrl, String modelName, String apiKey,
                           boolean requestDebugEnabled, Boolean thinkingEnabled) {
        this(baseUrl, modelName, apiKey, requestDebugEnabled, thinkingEnabled, null);
    }

    public OpenAIChatModel(String baseUrl, String modelName, String apiKey,
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
     *
     * @param result       结果对象
     * @param messages     消息列表(会被修改)
     * @param tools        工具描述列表
     * @param toolExecutor 工具执行器
     */
    private void executeAgentLoop(LLMResult result, List<Message> messages, List<ToolDescriptor> tools,
                                  ToolExecutor toolExecutor, Boolean requestThinkingEnabled,
                                  Double requestTemperature) {
        try {
            ObjectNode body = buildRequestBody(messages, tools, requestThinkingEnabled, requestTemperature);
            Request request = new Request.Builder()
                    .url(baseUrl + "/v1/chat/completions")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .header("Authorization", "Bearer " + apiKey)
                    .build();
            LLMRequestDebugLogger.log(requestDebugEnabled, request, body.toString());

            EventSource.Factory factory = EventSources.createFactory(client);
            factory.newEventSource(request, new EventSourceListener() {
                private final StringBuilder contentBuffer = new StringBuilder();
                private final StringBuilder thinkBuffer = new StringBuilder();
                private final List<ToolCallEntry> toolCalls = new ArrayList<>();
                private int currentToolCallIndex = -1;
                private volatile boolean cancelRequested;

                @Override
                public void onEvent(EventSource eventSource, String id, String type, String data) {
                    if (cancelRequested) return;
                    LLMRequestDebugLogger.logStreamEvent(requestDebugEnabled, id, type, data);
                    if ("[DONE]".equals(data)) return;
                    try {
                        JsonNode json = MAPPER.readTree(data);
                        TokenUsage usage = parseTokenUsage(json);
                        if (usage != null) {
                            result.addUsage(usage);
                        }
                        JsonNode choices = json.get("choices");
                        if (choices == null || choices.isEmpty()) return;

                        JsonNode delta = choices.get(0).get("delta");
                        if (delta == null) return;

                        // 处理思考/推理内容
                        if (delta.has("reasoning_content")) {
                            JsonNode reasoning = delta.get("reasoning_content");
                            if (reasoning != null && !reasoning.isNull()) {
                                thinkBuffer.append(reasoning.asText());
                                ResultHandler handler = result.getHandler();
                                if (handler != null) handler.onThink(reasoning.asText());
                            }
                        }else if (delta.has("reasoning")) {
                            JsonNode reasoning = delta.get("reasoning");
                            if (reasoning != null && !reasoning.isNull()) {
                                thinkBuffer.append(reasoning.asText());
                                ResultHandler handler = result.getHandler();
                                if (handler != null) handler.onThink(reasoning.asText());
                            }
                        }else if (delta.has("thinking")) {
                            JsonNode reasoning = delta.get("thinking");
                            if (reasoning != null && !reasoning.isNull()) {
                                thinkBuffer.append(reasoning.asText());
                                ResultHandler handler = result.getHandler();
                                if (handler != null) handler.onThink(reasoning.asText());
                            }
                        }else if (delta.has("thinking_content")) {
                            JsonNode reasoning = delta.get("thinking_content");
                            if (reasoning != null && !reasoning.isNull()) {
                                thinkBuffer.append(reasoning.asText());
                                ResultHandler handler = result.getHandler();
                                if (handler != null) handler.onThink(reasoning.asText());
                            }
                        }

                        // 处理文本内容
                        JsonNode content = delta.get("content");
                        if (content != null && !content.isNull()) {
                            contentBuffer.append(content.asText());
                            ResultHandler handler = result.getHandler();
                            if (handler != null) handler.onMessage(content.asText());
                        }

                        // 处理工具调用
                        JsonNode toolCallsNode = delta.get("tool_calls");
                        if (toolCallsNode != null) {
                            for (JsonNode toolCall : toolCallsNode) {
                                JsonNode indexNode = toolCall.get("index");
                                if (indexNode != null) {
                                    int idx = indexNode.asInt();
                                    while (toolCalls.size() <= idx) {
                                        toolCalls.add(new ToolCallEntry());
                                    }
                                    currentToolCallIndex = idx;
                                    ToolCallEntry entry = toolCalls.get(idx);
                                    JsonNode idNode = toolCall.get("id");
                                    if (hasTextValue(idNode)) entry.callId = idNode.asText();
                                    JsonNode function = toolCall.get("function");
                                    if (function != null) {
                                        JsonNode nameNode = function.get("name");
                                        if (hasTextValue(nameNode)) {
                                            entry.nameBuffer.append(nameNode.asText());
                                            entry.toolName = entry.nameBuffer.toString();
                                            notifyToolPreparing(result, entry);
                                        }
                                        JsonNode args = function.get("arguments");
                                        if (hasTextValue(args)) entry.argsBuffer.append(args.asText());
                                    }
                                }
                            }
                        }

                        // 检查完成原因
                        JsonNode finishReason = choices.get(0).get("finish_reason");
                        if (finishReason != null) {
                            String reason = finishReason.asText();
                            if ("tool_calls".equals(reason)) {
                                cancelRequested = true;
                                eventSource.cancel();
                                handleToolCallsAndContinue(result, messages, tools, toolExecutor,
                                        requestThinkingEnabled, requestTemperature,
                                        contentBuffer.toString(), thinkBuffer.toString(), toolCalls);
                            } else if ("stop".equals(reason)) {
                                cancelRequested = true;
                                eventSource.cancel();
                                addFinalAssistantMessage(result, contentBuffer.toString(), thinkBuffer.toString());
                                result.complete(contentBuffer.toString());
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
                    if (cancelRequested) {
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
            // 添加协议无关的assistant消息(含工具调用)
            Message assistantMessage = Message.fromAssistant();
            if (content != null && !content.isEmpty()) {
                assistantMessage.appendContent(content);
            }
            if (think != null && !think.isEmpty()) {
                assistantMessage.appendThink(think);
            }
            for (int i = 0; i < toolCalls.size(); i++) {
                ToolCallEntry entry = toolCalls.get(i);
                assistantMessage.appendToolCall(entry.callId, entry.toolName, entry.argsBuffer.toString());
            }
            messages.add(assistantMessage);
            result.addAppendedMessage(assistantMessage);

            // 执行每个工具调用并添加结果
            for (ToolCallEntry entry : toolCalls) {
                ToolDescriptor descriptor = ToolDescriptor.fromTool(toolMap.get(entry.toolName));
                descriptor.setCallId(entry.callId);
                descriptor.setInputParams(entry.argsBuffer.toString());

                ResultHandler toolHandler = suppressPreparingIfAlreadyNotified(result.getHandler(), entry.preparingNotified);
                String toolResult;
                try {
                    toolResult = toolExecutor.execute(entry.toolName, entry.argsBuffer.toString(), descriptor, toolHandler);
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
     * 工具调用条目.
     */
    private static class ToolCallEntry {
        String callId;
        String toolName;
        boolean preparingNotified;
        final StringBuilder nameBuffer = new StringBuilder();
        final StringBuilder argsBuffer = new StringBuilder();
    }

    private void notifyToolPreparing(LLMResult result, ToolCallEntry entry) {
        if (entry.preparingNotified || entry.toolName == null || entry.toolName.isEmpty()) {
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
            handler.onTool(descriptor, ToolStatus.PREPARING, null);
        }
        entry.preparingNotified = true;
    }

    private ResultHandler suppressPreparingIfAlreadyNotified(ResultHandler handler, boolean suppressPreparing) {
        if (!suppressPreparing || handler == null) {
            return handler;
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

    /**
     * 构建OpenAI Chat Completions请求体.
     *
     * @param messages 消息列表
     * @param tools    工具描述列表
     * @return 请求体JSON节点
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
        ObjectNode streamOptions = MAPPER.createObjectNode();
        streamOptions.put("include_usage", true);
        body.set("stream_options", streamOptions);
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
            msgNode.put("role", msg.getRole().name());
            if (isMiMoModel() && msg.getRole() == Message.Role.assistant && msg.getThink() != null && !msg.getThink().isEmpty()) {
                msgNode.put("reasoning_content", msg.getThink());
            }

            if (msg.getAttachments() != null && !msg.getAttachments().isEmpty()) {
                ArrayNode contentArray = MAPPER.createArrayNode();
                if (msg.getContent() != null) {
                    ObjectNode textPart = MAPPER.createObjectNode();
                    textPart.put("type", "text");
                    textPart.put("text", msg.getContent());
                    contentArray.add(textPart);
                }
                for (MessageAttachment attachment : msg.getAttachments()) {
                    ObjectNode imagePart = MAPPER.createObjectNode();
                    imagePart.put("type", "image_url");
                    ObjectNode imageUrl = MAPPER.createObjectNode();
                    imageUrl.put("url", "data:" + attachment.getContentType() + ";base64," +
                            Base64.getEncoder().encodeToString(attachment.getData()));
                    imagePart.set("image_url", imageUrl);
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
                toolsArray.add(MAPPER.valueToTree(desc.toLLMContent(ink.icoding.llm.core.entity.ModelType.OpenAI)));
            }
            body.set("tools", toolsArray);
        }
        applyThinkingOptions(body, requestThinkingEnabled);

        return body;
    }

    private void applyThinkingOptions(ObjectNode body, Boolean requestThinkingEnabled) {
        if (requestThinkingEnabled == null || !isQwenModel()) {
            return;
        }
        ObjectNode chatTemplateKwargs = MAPPER.createObjectNode();
        chatTemplateKwargs.put("enable_thinking", requestThinkingEnabled);
        body.set("chat_template_kwargs", chatTemplateKwargs);
    }

    private boolean appendNeutralMessage(ArrayNode messagesArray, Message msg) {
        if (msg.getRole() == Message.Role.assistant
                && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            ObjectNode assistantMsg = MAPPER.createObjectNode();
            assistantMsg.put("role", "assistant");
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                assistantMsg.put("content", msg.getContent());
            } else {
                assistantMsg.putNull("content");
            }
            if (isMiMoModel() && msg.getThink() != null && !msg.getThink().isEmpty()) {
                assistantMsg.put("reasoning_content", msg.getThink());
            }
            ArrayNode toolCallsArray = MAPPER.createArrayNode();
            for (MessageToolCall call : msg.getToolCalls()) {
                ObjectNode tc = MAPPER.createObjectNode();
                tc.put("id", call.getId());
                tc.put("type", "function");
                ObjectNode fn = MAPPER.createObjectNode();
                fn.put("name", call.getName());
                fn.put("arguments", call.getArgumentsJson());
                tc.set("function", fn);
                toolCallsArray.add(tc);
            }
            assistantMsg.set("tool_calls", toolCallsArray);
            messagesArray.add(assistantMsg);
            return true;
        }
        if (msg.getRole() == Message.Role.tool && msg.getToolResult() != null) {
            ObjectNode toolMsg = MAPPER.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", msg.getToolResult().getToolCallId());
            toolMsg.put("content", msg.getToolResult().getContent());
            messagesArray.add(toolMsg);
            return true;
        }
        return false;
    }

    private boolean isLegacyStructuredHistory(Message msg) {
        return msg.getRole() != Message.Role.user
                && msg.getContent() != null
                && msg.getContent().stripLeading().startsWith("{");
    }

    private TokenUsage parseTokenUsage(JsonNode json) {
        JsonNode usage = json.get("usage");
        if (usage == null || usage.isNull()) {
            usage = json.get("used");
        }
        if (usage == null || usage.isNull()) {
            return null;
        }
        if (usage.isNumber()) {
            return new TokenUsage(usage.asInt(), 0, usage.asInt());
        }
        int input = firstInt(usage, "prompt_tokens", "input_tokens");
        int output = firstInt(usage, "completion_tokens", "output_tokens");
        int total = firstInt(usage, "total_tokens", "used");
        int cached = firstInt(usage, "cached_tokens", "prompt_cache_hit_tokens", "cache_hit_tokens");
        cached = Math.max(cached, nestedFirstInt(usage, "prompt_tokens_details", "cached_tokens"));
        cached = Math.max(cached, nestedFirstInt(usage, "input_tokens_details", "cached_tokens"));
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

    private boolean hasTextValue(JsonNode node) {
        return node != null && !node.isNull();
    }

    /**
     * 处理错误, 将异常传递给错误回调处理器.
     *
     * @param result 结果对象
     * @param t      异常
     */
    private void handleError(LLMResult result, Throwable t) {
        result.fail(t);
    }

    private boolean isMiMoModel() {
        return modelName != null && modelName.regionMatches(true, 0, "MiMo", 0, 4);
    }

    private boolean isQwenModel() {
        if (modelName == null) {
            return false;
        }
        String lower = modelName.toLowerCase(Locale.ROOT);
        return lower.contains("qwen") || lower.contains("qwq");
    }
}
