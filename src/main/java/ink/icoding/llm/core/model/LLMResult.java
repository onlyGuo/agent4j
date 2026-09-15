package ink.icoding.llm.core.model;

import ink.icoding.llm.core.entity.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * LLM调用结果对象, 支持链式回调.
 * <p>通过 {@link #then(ResultHandler)} 和 {@link #error(java.util.function.Consumer)}
 * 注册回调后, 调用 {@link #execute()} 启动异步SSE流式请求.</p>
 * <p>使用示例:</p>
 * <pre>{@code
 * model.ask(messages, tools)
 *     .then(message -> System.out.println(message))
 *     .error(e -> e.printStackTrace())
 *     .execute();
 * }</pre>
 *
 * @author gsk
 */
public class LLMResult {
    private ResultHandler handler;
    private java.util.function.Consumer<Exception> errorHandler;
    private final java.util.function.Consumer<LLMResult> executor;
    private final CompletableFuture<String> future = new CompletableFuture<>();
    private final List<Message> appendedMessages = Collections.synchronizedList(new ArrayList<>());
    private final List<TokenUsage> usages = Collections.synchronizedList(new ArrayList<>());

    /**
     * 构造结果对象.
     *
     * @param executor 执行器, 接收当前LLMResult实例以读取回调处理器
     */
    public LLMResult(java.util.function.Consumer<LLMResult> executor) {
        this.executor = executor;
    }

    /**
     * 注册结果回调处理器.
     *
     * @param handler 回调处理器
     * @return 当前实例, 支持链式调用
     */
    public LLMResult then(ResultHandler handler) {
        this.handler = handler;
        return this;
    }

    /**
     * 注册错误回调处理器.
     *
     * @param handler 错误处理器
     * @return 当前实例, 支持链式调用
     */
    public LLMResult error(java.util.function.Consumer<Exception> handler) {
        this.errorHandler = handler;
        return this;
    }

    /**
     * 启动执行, 触发异步SSE流式请求.
     */
    public void execute() {
        executor.accept(this);
    }

    /**
     * 阻塞等待执行完成并返回结果.
     *
     * @return LLM响应的完整文本
     * @throws Exception 如果执行失败
     */
    public String get() throws Exception {
        return future.get();
    }

    /**
     * 完成Future, 由模型实现调用.
     *
     * @param result 完成结果
     */
    public void complete(String result) {
        future.complete(result);
    }

    /**
     * 异常完成Future, 由模型实现调用.
     *
     * @param t 异常
     */
    public void completeExceptionally(Throwable t) {
        future.completeExceptionally(t);
    }

    /**
     * Fail and notify only if this failure wins the terminal state transition.
     * Late connection failures must not escape an already completed result.
     * @param t original failure
     */
    public void fail(Throwable t) {
        if (future.completeExceptionally(t) && errorHandler != null) {
            errorHandler.accept(t instanceof Exception ? (Exception) t : new RuntimeException(t));
        }
    }

    /**
     * 获取结果回调处理器.
     *
     * @return 结果处理器
     */
    public ResultHandler getHandler() {
        return handler;
    }

    /**
     * 获取错误回调处理器.
     *
     * @return 错误处理器
     */
    public java.util.function.Consumer<Exception> getErrorHandler() {
        return errorHandler;
    }

    /**
     * 记录模型在Agent循环中追加到上下文的真实消息.
     *
     * @param message 追加消息
     */
    public void addAppendedMessage(Message message) {
        if (message != null) {
            appendedMessages.add(message);
        }
    }

    /**
     * 获取模型在本次调用中追加的真实消息.
     *
     * @return 追加消息副本
     */
    public List<Message> getAppendedMessages() {
        synchronized (appendedMessages) {
            return new ArrayList<>(appendedMessages);
        }
    }

    /**
     * 记录Token用量并触发回调.
     *
     * @param usage Token用量
     */
    public void addUsage(TokenUsage usage) {
        if (usage == null) {
            return;
        }
        usages.add(usage);
        ResultHandler h = handler;
        if (h != null) {
            h.onUsage(usage);
        }
    }

    /**
     * 获取本次调用的所有Token用量记录.
     *
     * @return Token用量副本
     */
    public List<TokenUsage> getUsages() {
        synchronized (usages) {
            return new ArrayList<>(usages);
        }
    }

    /**
     * 获取最近一次Token用量.
     *
     * @return 最近一次Token用量, 不存在时返回null
     */
    public TokenUsage getLastUsage() {
        synchronized (usages) {
            return usages.isEmpty() ? null : usages.get(usages.size() - 1);
        }
    }

    /**
     * 获取本次调用中最大的输入上下文Token数.
     *
     * @return 最大输入Token数
     */
    public int getMaxInputTokens() {
        synchronized (usages) {
            int max = 0;
            for (TokenUsage usage : usages) {
                max = Math.max(max, usage.getInputTokens());
            }
            return max;
        }
    }
}
