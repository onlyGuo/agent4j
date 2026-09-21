package ink.icoding.llm.core.entity;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Embedding 输入项.
 * <p>一个输入项可以包含文本、图片和视频。对于支持融合向量的多模态模型，
 * 同一输入项中的内容会作为一个语义单元提交给模型。</p>
 */
public class EmbeddingInput {
    private final List<Part> parts = new ArrayList<>();

    /** 创建仅包含文本的输入项。 */
    public static EmbeddingInput fromText(String text) {
        return new EmbeddingInput().appendText(text);
    }

    /** 创建空输入项，便于通过链式调用构建多模态内容。 */
    public static EmbeddingInput create() {
        return new EmbeddingInput();
    }

    /** 追加文本。 */
    public EmbeddingInput appendText(String text) {
        parts.add(new Part(PartType.TEXT, requireValue(text, "text")));
        return this;
    }

    /**
     * 追加图片 URL 或 data URI。
     *
     * @param imageUrl 可公开访问的 URL 或 {@code data:image/...;base64,...}
     */
    public EmbeddingInput appendImage(String imageUrl) {
        parts.add(new Part(PartType.IMAGE, requireValue(imageUrl, "imageUrl")));
        return this;
    }

    /** 追加本地图片二进制内容。 */
    public EmbeddingInput appendImage(byte[] data, String contentType) {
        return appendData(PartType.IMAGE, data, contentType);
    }

    /** 追加视频 URL 或 data URI。 */
    public EmbeddingInput appendVideo(String videoUrl) {
        parts.add(new Part(PartType.VIDEO, requireValue(videoUrl, "videoUrl")));
        return this;
    }

    /** 追加本地视频二进制内容。 */
    public EmbeddingInput appendVideo(byte[] data, String contentType) {
        return appendData(PartType.VIDEO, data, contentType);
    }

    /** 返回输入内容，按添加顺序排列。 */
    public List<Part> getParts() {
        return Collections.unmodifiableList(parts);
    }

    /** 是否包含非文本内容。 */
    public boolean hasMultimedia() {
        return parts.stream().anyMatch(part -> part.type != PartType.TEXT);
    }

    /** 是否为空。 */
    public boolean isEmpty() {
        return parts.isEmpty();
    }

    private EmbeddingInput appendData(PartType type, byte[] data, String contentType) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be empty");
        }
        String mimeType = requireValue(contentType, "contentType");
        String dataUri = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(data);
        parts.add(new Part(type, dataUri));
        return this;
    }

    private static String requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** 多模态内容类型。 */
    public enum PartType { TEXT, IMAGE, VIDEO }

    /** 一个 Embedding 内容片段。 */
    public static final class Part {
        private final PartType type;
        private final String value;

        private Part(PartType type, String value) {
            this.type = Objects.requireNonNull(type, "type");
            this.value = Objects.requireNonNull(value, "value");
        }

        public PartType getType() { return type; }
        public String getValue() { return value; }
    }
}
