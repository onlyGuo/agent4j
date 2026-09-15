package ink.icoding.llm.core.model.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ink.icoding.llm.core.entity.Message;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MiMoReasoningHistoryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void anthropicAssistantHistoryIncludesThinkingForMiMo() throws Exception {
        AnthropicModel model = new AnthropicModel("https://example.com", "MiMo-v2.5-pro", "test-key");
        Method method = AnthropicModel.class.getDeclaredMethod("buildAssistantMessage", String.class, String.class, String.class, List.class);
        method.setAccessible(true);

        String json = (String) method.invoke(model, "done", "step-by-step", null, List.of());
        JsonNode assistant = MAPPER.readTree(json);

        assertEquals("thinking", assistant.get("content").get(0).get("type").asText());
        assertEquals("step-by-step", assistant.get("content").get(0).get("thinking").asText());
        assertEquals("text", assistant.get("content").get(1).get("type").asText());
    }

    @Test
    void anthropicAssistantHistorySkipsThinkingForNonMiMo() throws Exception {
        AnthropicModel model = new AnthropicModel("https://example.com", "claude-sonnet-4", "test-key");
        Method method = AnthropicModel.class.getDeclaredMethod("buildAssistantMessage", String.class, String.class, String.class, List.class);
        method.setAccessible(true);

        String json = (String) method.invoke(model, "done", "step-by-step", null, List.of());
        JsonNode assistant = MAPPER.readTree(json);

        assertEquals(1, assistant.get("content").size());
        assertEquals("text", assistant.get("content").get(0).get("type").asText());
    }

    @Test
    void anthropicAssistantHistoryIncludesSignatureForNonMiMo() throws Exception {
        AnthropicModel model = new AnthropicModel("https://example.com", "claude-sonnet-4", "test-key");
        Method method = AnthropicModel.class.getDeclaredMethod("buildAssistantMessage", String.class, String.class, String.class, List.class);
        method.setAccessible(true);

        String json = (String) method.invoke(model, "done", "step-by-step", "sig-123", List.of());
        JsonNode assistant = MAPPER.readTree(json);

        assertEquals("thinking", assistant.get("content").get(0).get("type").asText());
        assertEquals("step-by-step", assistant.get("content").get(0).get("thinking").asText());
        assertEquals("sig-123", assistant.get("content").get(0).get("signature").asText());
        assertEquals("text", assistant.get("content").get(1).get("type").asText());
    }

    @Test
    void anthropicAssistantHistoryCanReplaySignatureWithoutMiMoThinkingGate() throws Exception {
        AnthropicModel model = new AnthropicModel("https://example.com", "claude-sonnet-4", "test-key");
        Method method = AnthropicModel.class.getDeclaredMethod("buildAssistantMessage", String.class, String.class, String.class, List.class);
        method.setAccessible(true);

        String json = (String) method.invoke(model, "done", null, "sig-123", List.of());
        JsonNode assistant = MAPPER.readTree(json);

        assertEquals("thinking", assistant.get("content").get(0).get("type").asText());
        assertEquals("sig-123", assistant.get("content").get(0).get("signature").asText());
        assertFalse(assistant.get("content").get(0).has("thinking"));
        assertEquals("text", assistant.get("content").get(1).get("type").asText());
    }

    @Test
    void anthropicToolUseStopReasonStillRequiresContinuation() throws Exception {
        Method method = AnthropicModel.class.getDeclaredMethod("shouldContinueWithToolCalls", String.class, List.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(null, "tool_use", List.of(new Object())));
        assertFalse((Boolean) method.invoke(null, "end_turn", List.of(new Object())));
        assertFalse((Boolean) method.invoke(null, "tool_use", List.of()));
    }

    @Test
    void openAIChatRequestIncludesAssistantThinkingForMiMo() throws Exception {
        OpenAIChatModel model = new OpenAIChatModel("https://example.com", "mimo-v2.5-pro", "test-key");
        Method method = OpenAIChatModel.class.getDeclaredMethod("buildRequestBody", List.class, List.class);
        method.setAccessible(true);

        Message assistantMessage = Message.fromAssistant("done").appendThink("step-by-step");
        ObjectNode body = (ObjectNode) method.invoke(model, List.of(assistantMessage), List.of());
        JsonNode assistant = body.get("messages").get(0);

        assertEquals("assistant", assistant.get("role").asText());
        assertEquals("step-by-step", assistant.get("reasoning_content").asText());
    }

    @Test
    void openAIResponseAssistantHistoryIncludesThinkingForMiMo() throws Exception {
        OpenAIResponseModel model = new OpenAIResponseModel("https://example.com", "MiMo-v2.5-pro", "test-key");
        Method method = OpenAIResponseModel.class.getDeclaredMethod("buildTextItem", Message.class);
        method.setAccessible(true);

        Message assistantMessage = Message.fromAssistant("done").appendThink("step-by-step");
        ObjectNode item = (ObjectNode) method.invoke(model, assistantMessage);

        assertEquals("assistant", item.get("role").asText());
        assertEquals("step-by-step", item.get("reasoning_content").asText());
        assertEquals("output_text", item.get("content").get(0).get("type").asText());
    }

    @Test
    void openAIResponseAssistantHistorySkipsThinkingForNonMiMo() throws Exception {
        OpenAIResponseModel model = new OpenAIResponseModel("https://example.com", "gpt-4.1", "test-key");
        Method method = OpenAIResponseModel.class.getDeclaredMethod("buildTextItem", Message.class);
        method.setAccessible(true);

        Message assistantMessage = Message.fromAssistant("done").appendThink("step-by-step");
        ObjectNode item = (ObjectNode) method.invoke(model, assistantMessage);

        assertFalse(item.has("reasoning_content"));
        assertEquals("output_text", item.get("content").get(0).get("type").asText());
    }

    @Test
    void qwenChatRequestIncludesThinkingSwitchWhenExplicitlySet() throws Exception {
        OpenAIChatModel model = new OpenAIChatModel("https://example.com", "Qwen3-235B-A22B", "test-key");
        model.setThinkingEnabled(false);
        Method method = OpenAIChatModel.class.getDeclaredMethod("buildRequestBody", List.class, List.class);
        method.setAccessible(true);

        ObjectNode body = (ObjectNode) method.invoke(model, List.of(Message.fromUser("hi")), List.of());

        assertFalse(body.get("chat_template_kwargs").get("enable_thinking").asBoolean());
    }

    @Test
    void nonQwenChatRequestDoesNotIncludeThinkingSwitch() throws Exception {
        OpenAIChatModel model = new OpenAIChatModel("https://example.com", "gpt-4.1", "test-key");
        model.setThinkingEnabled(false);
        Method method = OpenAIChatModel.class.getDeclaredMethod("buildRequestBody", List.class, List.class);
        method.setAccessible(true);

        ObjectNode body = (ObjectNode) method.invoke(model, List.of(Message.fromUser("hi")), List.of());

        assertFalse(body.has("chat_template_kwargs"));
    }

    @Test
    void responseRequestMapsThinkingSwitchToReasoningEffort() throws Exception {
        OpenAIResponseModel model = new OpenAIResponseModel("https://example.com", "gpt-5-mini", "test-key");
        model.setThinkingEnabled(false);
        Method method = OpenAIResponseModel.class.getDeclaredMethod("buildRequestBody", List.class, List.class);
        method.setAccessible(true);

        ObjectNode body = (ObjectNode) method.invoke(model, List.of(Message.fromUser("hi")), List.of());

        assertEquals("none", body.get("reasoning").get("effort").asText());
    }

    @Test
    void anthropicRequestIncludesThinkingBudgetWhenEnabled() throws Exception {
        AnthropicModel model = new AnthropicModel("https://example.com", "claude-sonnet-4", "test-key");
        model.setThinkingEnabled(true);
        Method method = AnthropicModel.class.getDeclaredMethod("buildRequestBody", List.class, List.class);
        method.setAccessible(true);

        ObjectNode body = (ObjectNode) method.invoke(model, List.of(Message.fromUser("hi")), List.of());

        assertEquals("enabled", body.get("thinking").get("type").asText());
        assertEquals(1024, body.get("thinking").get("budget_tokens").asInt());
    }

    @Test
    void requestThinkingArgumentOverridesModelSettingForAllProtocols() throws Exception {
        OpenAIChatModel chatModel = new OpenAIChatModel("https://example.com", "Qwen3", "test-key");
        chatModel.setThinkingEnabled(true);
        Method chatMethod = OpenAIChatModel.class.getDeclaredMethod(
                "buildRequestBody", List.class, List.class, Boolean.class);
        chatMethod.setAccessible(true);
        ObjectNode chatBody = (ObjectNode) chatMethod.invoke(
                chatModel, List.of(Message.fromUser("hi")), List.of(), false);
        assertFalse(chatBody.get("chat_template_kwargs").get("enable_thinking").asBoolean());

        OpenAIResponseModel responseModel = new OpenAIResponseModel(
                "https://example.com", "gpt-5-mini", "test-key");
        responseModel.setThinkingEnabled(true);
        Method responseMethod = OpenAIResponseModel.class.getDeclaredMethod(
                "buildRequestBody", List.class, List.class, Boolean.class);
        responseMethod.setAccessible(true);
        ObjectNode responseBody = (ObjectNode) responseMethod.invoke(
                responseModel, List.of(Message.fromUser("hi")), List.of(), false);
        assertEquals("none", responseBody.get("reasoning").get("effort").asText());

        AnthropicModel anthropicModel = new AnthropicModel(
                "https://example.com", "claude-sonnet-4", "test-key");
        anthropicModel.setThinkingEnabled(true);
        Method anthropicMethod = AnthropicModel.class.getDeclaredMethod(
                "buildRequestBody", List.class, List.class, Boolean.class);
        anthropicMethod.setAccessible(true);
        ObjectNode anthropicBody = (ObjectNode) anthropicMethod.invoke(
                anthropicModel, List.of(Message.fromUser("hi")), List.of(), false);
        assertFalse(anthropicBody.has("thinking"));
    }

    @Test
    void requestTemperatureArgumentOverridesModelSettingForAllProtocols() throws Exception {
        OpenAIChatModel chatModel = new OpenAIChatModel("https://example.com", "Qwen3", "test-key");
        chatModel.setTemperature(0.9);
        Method chatMethod = OpenAIChatModel.class.getDeclaredMethod(
                "buildRequestBody", List.class, List.class, Boolean.class, Double.class);
        chatMethod.setAccessible(true);
        ObjectNode chatBody = (ObjectNode) chatMethod.invoke(
                chatModel, List.of(Message.fromUser("hi")), List.of(), null, 0.2);
        assertEquals(0.2, chatBody.get("temperature").asDouble());

        OpenAIResponseModel responseModel = new OpenAIResponseModel(
                "https://example.com", "gpt-5-mini", "test-key");
        responseModel.setTemperature(0.9);
        Method responseMethod = OpenAIResponseModel.class.getDeclaredMethod(
                "buildRequestBody", List.class, List.class, Boolean.class, Double.class);
        responseMethod.setAccessible(true);
        ObjectNode responseBody = (ObjectNode) responseMethod.invoke(
                responseModel, List.of(Message.fromUser("hi")), List.of(), null, 0.3);
        assertEquals(0.3, responseBody.get("temperature").asDouble());

        AnthropicModel anthropicModel = new AnthropicModel(
                "https://example.com", "claude-sonnet-4", "test-key");
        anthropicModel.setTemperature(0.9);
        Method anthropicMethod = AnthropicModel.class.getDeclaredMethod(
                "buildRequestBody", List.class, List.class, Boolean.class, Double.class);
        anthropicMethod.setAccessible(true);
        ObjectNode anthropicBody = (ObjectNode) anthropicMethod.invoke(
                anthropicModel, List.of(Message.fromUser("hi")), List.of(), null, 0.4);
        assertEquals(0.4, anthropicBody.get("temperature").asDouble());
    }

    @Test
    void openAIResponseToolCallCanStartFromOutputItemAdded() throws Exception {
        Class<?> entryClass = Class.forName("ink.icoding.llm.core.model.impl.OpenAIResponseModel$ToolCallEntry");
        Method applyOutputItem = OpenAIResponseModel.class.getDeclaredMethod(
                "applyOutputItemEvent", JsonNode.class, Map.class, entryClass);
        Method applyDelta = OpenAIResponseModel.class.getDeclaredMethod(
                "applyFunctionCallArgumentsDeltaEvent", JsonNode.class, Map.class, entryClass);
        Method applyDone = OpenAIResponseModel.class.getDeclaredMethod(
                "applyFunctionCallArgumentsDoneEvent", JsonNode.class, Map.class, entryClass, List.class);
        Method applyCompleted = OpenAIResponseModel.class.getDeclaredMethod(
                "applyCompletedResponseOutput", JsonNode.class, Map.class, List.class, entryClass);
        applyOutputItem.setAccessible(true);
        applyDelta.setAccessible(true);
        applyDone.setAccessible(true);
        applyCompleted.setAccessible(true);

        Map<String, Object> entries = new LinkedHashMap<>();
        List<Object> toolCalls = new ArrayList<>();
        Object entry = applyOutputItem.invoke(null, MAPPER.readTree("""
                {"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","status":"in_progress","arguments":"","call_id":"call_1","name":"imgreader"},"output_index":0}
                """), entries, null);
        entry = applyDelta.invoke(null, MAPPER.readTree("""
                {"type":"response.function_call_arguments.delta","delta":"{\\"images\\":[","item_id":"fc_1","output_index":0}
                """), entries, entry);
        entry = applyDelta.invoke(null, MAPPER.readTree("""
                {"type":"response.function_call_arguments.delta","delta":"\\"a.png\\"]}","item_id":"fc_1","output_index":0}
                """), entries, entry);
        entry = applyDone.invoke(null, MAPPER.readTree("""
                {"type":"response.function_call_arguments.done","arguments":"{\\"images\\":[\\"a.png\\"]}","item_id":"fc_1","output_index":0}
                """), entries, entry, toolCalls);
        applyCompleted.invoke(null, MAPPER.readTree("""
                {"type":"response.completed","response":{"output":[{"id":"fc_1","type":"function_call","status":"completed","arguments":"{\\"images\\":[\\"a.png\\"]}","call_id":"call_1","name":"imgreader"}]}}
                """), entries, toolCalls, entry);

        assertEquals(1, toolCalls.size());
        Object parsed = toolCalls.get(0);
        assertEquals("fc_1", readField(parsed, "itemId"));
        assertEquals("call_1", readField(parsed, "callId"));
        assertEquals("imgreader", readField(parsed, "toolName"));
        assertEquals("{\"images\":[\"a.png\"]}", readField(parsed, "argsJson"));
    }

    @Test
    void openAIResponseDropsLegacyChatToolMessages() throws Exception {
        OpenAIResponseModel model = new OpenAIResponseModel("https://example.com", "gpt-4.1", "test-key");
        Method method = OpenAIResponseModel.class.getDeclaredMethod("buildRequestBody", List.class, List.class);
        method.setAccessible(true);

        Message assistantToolCall = Message.fromAssistant("""
                {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"search","arguments":"{}"}}]}
                """.trim());
        Message toolResult = Message.fromTool("""
                {"role":"tool","tool_call_id":"call_1","content":"result"}
                """.trim());

        ObjectNode body = (ObjectNode) method.invoke(model, List.of(assistantToolCall, toolResult), List.of());
        JsonNode input = body.get("input");

        assertEquals(0, input.size());
    }

    @Test
    void neutralToolHistoryCanBuildAllProtocols() throws Exception {
        Message assistant = Message.fromAssistant("checking")
                .appendToolCall("call_1", "search", "{\"q\":\"java\"}");
        Message tool = Message.fromTool().withToolResult("call_1", "result");

        Method chatMethod = OpenAIChatModel.class.getDeclaredMethod("buildRequestBody", List.class, List.class);
        chatMethod.setAccessible(true);
        ObjectNode chatBody = (ObjectNode) chatMethod.invoke(
                new OpenAIChatModel("https://example.com", "gpt-4.1", "test-key"),
                List.of(assistant, tool), List.of());
        assertTrue(chatBody.get("messages").get(0).has("tool_calls"));
        assertEquals("tool", chatBody.get("messages").get(1).get("role").asText());

        Method responseMethod = OpenAIResponseModel.class.getDeclaredMethod("buildRequestBody", List.class, List.class);
        responseMethod.setAccessible(true);
        ObjectNode responseBody = (ObjectNode) responseMethod.invoke(
                new OpenAIResponseModel("https://example.com", "gpt-4.1", "test-key"),
                List.of(assistant, tool), List.of());
        assertEquals("output_text", responseBody.get("input").get(0).get("content").get(0).get("type").asText());
        assertEquals("function_call", responseBody.get("input").get(1).get("type").asText());
        assertEquals("function_call_output", responseBody.get("input").get(2).get("type").asText());

        Method anthropicMethod = AnthropicModel.class.getDeclaredMethod("buildRequestBody", List.class, List.class);
        anthropicMethod.setAccessible(true);
        ObjectNode anthropicBody = (ObjectNode) anthropicMethod.invoke(
                new AnthropicModel("https://example.com", "claude-sonnet-4", "test-key"),
                List.of(assistant, tool), List.of());
        assertEquals("tool_use", anthropicBody.get("messages").get(0).get("content").get(1).get("type").asText());
        assertEquals("tool_result", anthropicBody.get("messages").get(1).get("content").get(0).get("type").asText());
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
