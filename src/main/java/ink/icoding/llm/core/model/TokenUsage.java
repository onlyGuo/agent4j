package ink.icoding.llm.core.model;

/**
 * Token用量信息.
 * <p>不同模型供应商返回的字段名不完全一致, 统一归一化为输入、输出和总量.</p>
 */
public class TokenUsage {
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;
    private int cachedTokens;

    public TokenUsage() {}

    public TokenUsage(int inputTokens, int outputTokens, int totalTokens) {
        this(inputTokens, outputTokens, totalTokens, 0);
    }

    public TokenUsage(int inputTokens, int outputTokens, int totalTokens, int cachedTokens) {
        this.outputTokens = Math.max(outputTokens, 0);
        this.totalTokens = totalTokens > 0
                ? totalTokens
                : Math.max(Math.max(inputTokens, 0) + this.outputTokens, 0);
        this.inputTokens = inputTokens > 0 ? inputTokens : this.totalTokens;
        this.cachedTokens = Math.max(cachedTokens, 0);
    }

    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = Math.max(inputTokens, 0); }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = Math.max(outputTokens, 0); }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = Math.max(totalTokens, 0); }
    public int getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(int cachedTokens) { this.cachedTokens = Math.max(cachedTokens, 0); }

    @Override
    public String toString() {
        return "TokenUsage{" +
                "inputTokens=" + inputTokens +
                ", outputTokens=" + outputTokens +
                ", totalTokens=" + totalTokens +
                ", cachedTokens=" + cachedTokens +
                '}';
    }
}
