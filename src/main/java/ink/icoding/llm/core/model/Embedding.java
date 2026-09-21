package ink.icoding.llm.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单个 Embedding 向量。 */
public class Embedding {
    private final int index;
    private final String type;
    private final List<Double> vector;

    public Embedding(int index, String type, List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            throw new IllegalArgumentException("embedding vector must not be empty");
        }
        this.index = index;
        this.type = type;
        this.vector = Collections.unmodifiableList(new ArrayList<>(vector));
    }

    /** 输入或服务端返回的向量序号。 */
    public int getIndex() { return index; }

    /** 多模态模型返回的向量类型，例如 text、image 或 fused；文本 API 可能为 null。 */
    public String getType() { return type; }

    /** 向量值。 */
    public List<Double> getVector() { return vector; }

    @Override
    public String toString() {
        return "Embedding{" +
                "index=" + index +
                ", type='" + type + '\'' +
                ", vector(Size)=" + vector.size() +
                '}';
    }
}
