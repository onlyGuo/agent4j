package ink.icoding.llm.core.model;

import ink.icoding.llm.core.entity.Message;
import ink.icoding.llm.core.entity.ModelType;
import ink.icoding.llm.core.model.impl.AnthropicModel;
import ink.icoding.llm.core.model.impl.OpenAIChatModel;
import ink.icoding.llm.core.model.impl.OpenAIResponseModel;
import ink.icoding.llm.core.tool.Tool;

import java.util.List;

/**
 * 大语言模型基础抽象接口.
 * <p>定义了与LLM交互的统一协议, 支持多种模型提供商(OpenAI、Anthropic等).
 * 通过工厂方法 {@link #create(ModelType, String, String, String)} 创建具体实现.
 * </p>
 *
 * @author gsk
 */
public interface LLMModel {

    /**
     * 工厂方法, 根据模型类型创建对应的LLM实例.
     *
     * @param type      模型类型枚举
     * @param baseUrl   API基础地址
     * @param modelName 模型名称
     * @param apiKey    API密钥
     * @return 对应类型的LLMModel实例
     */
    static LLMModel create(ModelType type, String baseUrl, String modelName, String apiKey) {
        return create(type, baseUrl, modelName, apiKey, false);
    }

    /**
     * 工厂方法, 根据模型类型创建对应的LLM实例.
     *
     * @param type                模型类型枚举
     * @param baseUrl             API基础地址
     * @param modelName           模型名称
     * @param apiKey              API密钥
     * @param requestDebugEnabled 是否输出LLM请求DEBUG日志
     * @return 对应类型的LLMModel实例
     */
    static LLMModel create(ModelType type, String baseUrl, String modelName, String apiKey, boolean requestDebugEnabled) {
        return create(type, baseUrl, modelName, apiKey, requestDebugEnabled, null);
    }

    /**
     * 工厂方法, 根据模型类型创建对应的LLM实例.
     *
     * @param type                模型类型枚举
     * @param baseUrl             API基础地址
     * @param modelName           模型名称
     * @param apiKey              API密钥
     * @param requestDebugEnabled 是否输出LLM请求DEBUG日志
     * @param thinkingEnabled     是否开启模型思考; null表示不干预模型默认行为
     * @return 对应类型的LLMModel实例
     */
    static LLMModel create(ModelType type, String baseUrl, String modelName, String apiKey,
                           boolean requestDebugEnabled, Boolean thinkingEnabled) {
        return create(type, baseUrl, modelName, apiKey, requestDebugEnabled, thinkingEnabled, null);
    }

    /**
     * 工厂方法, 根据模型类型创建对应的LLM实例.
     *
     * @param type                模型类型枚举
     * @param baseUrl             API基础地址
     * @param modelName           模型名称
     * @param apiKey              API密钥
     * @param requestDebugEnabled 是否输出LLM请求DEBUG日志
     * @param thinkingEnabled     是否开启模型思考; null表示不干预模型默认行为
     * @param temperature         模型温度; null表示不向服务端传递温度配置
     * @return 对应类型的LLMModel实例
     */
    static LLMModel create(ModelType type, String baseUrl, String modelName, String apiKey,
                           boolean requestDebugEnabled, Boolean thinkingEnabled, Double temperature) {
        return switch (type) {
            case OpenAI -> new OpenAIChatModel(baseUrl, modelName, apiKey, requestDebugEnabled, thinkingEnabled, temperature);
            case Anthropic -> new AnthropicModel(baseUrl, modelName, apiKey, requestDebugEnabled, thinkingEnabled, temperature);
            case OpenAIResponse -> new OpenAIResponseModel(baseUrl, modelName, apiKey, requestDebugEnabled, thinkingEnabled, temperature);
            default -> throw new IllegalArgumentException(type + " is not an LLM model type");
        };
    }

    /**
     * 设置是否输出LLM请求DEBUG日志.
     *
     * @param enabled 是否开启
     */
    default void setRequestDebugEnabled(boolean enabled) {}

    /**
     * 是否输出LLM请求DEBUG日志.
     *
     * @return 是否开启
     */
    default boolean isRequestDebugEnabled() { return false; }

    /**
     * 设置是否开启模型思考.
     *
     * @param enabled 是否开启; null表示不干预模型默认行为
     */
    default void setThinkingEnabled(Boolean enabled) {}

    /**
     * 是否开启模型思考.
     *
     * @return 是否开启; null表示不干预模型默认行为
     */
    default Boolean getThinkingEnabled() { return null; }

    /** 设置模型默认温度; null表示不向服务端传递温度配置. */
    default void setTemperature(Double temperature) {}

    /** 获取模型默认温度; null表示不向服务端传递温度配置. */
    default Double getTemperature() { return null; }

    /**
     * 发送单条消息并返回结果.
     *
     * @param message 消息对象
     * @return 结果对象, 支持链式回调
     */
    LLMResult ask(Message message);

    /**
     * 发送消息列表并返回结果.
     *
     * @param messages 消息列表
     * @return 结果对象, 支持链式回调
     */
    LLMResult ask(List<Message> messages);

    /**
     * 发送消息列表并携带可用工具列表, 返回结果.
     *
     * @param messages 消息列表
     * @param tools    可用工具列表
     * @return 结果对象, 支持链式回调
     */
    LLMResult ask(List<Message> messages, List<Tool> tools);

    /**
     * 发送消息列表并携带可用工具列表和自定义工具执行器, 返回结果.
     * <p>当会话需要拦截特殊工具(如计划、子Agent)的执行时使用此方法.
     * 模型会使用提供的执行器来执行工具调用, 而不是使用默认的直接执行方式.</p>
     *
     * @param messages     消息列表
     * @param tools        可用工具列表
     * @param toolExecutor 自定义工具执行器
     * @return 结果对象, 支持链式回调
     */
    LLMResult ask(List<Message> messages, List<Tool> tools, ink.icoding.llm.core.tool.ToolExecutor toolExecutor);

    /**
     * 使用调用级思考开关发送消息.
     * <p>实现类应直接使用此参数构建本次请求, 不读取或修改模型实例上的思考开关.</p>
     *
     * @param messages        消息列表
     * @param tools           可用工具列表
     * @param toolExecutor    自定义工具执行器
     * @param thinkingEnabled 本次调用是否开启思考; null表示不向服务端传递思考配置
     * @return 结果对象, 支持链式回调
     */
    default LLMResult ask(List<Message> messages, List<Tool> tools,
                          ink.icoding.llm.core.tool.ToolExecutor toolExecutor,
                          Boolean thinkingEnabled) {
        return ask(messages, tools, toolExecutor);
    }

    /**
     * 使用调用级思考开关和温度发送消息.
     * <p>实现类应直接使用参数构建本次请求, 不读取或修改模型实例上的对应配置.</p>
     */
    default LLMResult ask(List<Message> messages, List<Tool> tools,
                          ink.icoding.llm.core.tool.ToolExecutor toolExecutor,
                          Boolean thinkingEnabled, Double temperature) {
        return ask(messages, tools, toolExecutor, thinkingEnabled);
    }
}
