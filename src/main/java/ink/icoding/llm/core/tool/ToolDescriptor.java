package ink.icoding.llm.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ink.icoding.llm.core.entity.ModelType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具描述实体.
 * <p>通过反射读取 {@link ink.icoding.llm.core.tool.annotations.ToolInfo} 和
 * {@link ink.icoding.llm.core.tool.annotations.Param} 注解, 构建LLM所需的工具定义.
 * 支持转换为OpenAI和Anthropic两种格式的工具Schema.</p>
 *
 * @author gsk
 */
public class ToolDescriptor {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String name;
    private String description;
    private String callId;
    private String inputParams;
    private Tool tool;
    private Class<? extends ToolParam> paramClass;
    private List<ParamInfo> params = new ArrayList<>();

    /**
     * 从Tool实例创建工具描述.
     * <p>通过反射读取Tool类上的注解信息, 构建完整的工具描述.</p>
     *
     * @param tool 工具实例
     * @return 工具描述对象
     */
    public static ToolDescriptor fromTool(Tool tool) {
        ToolDescriptor descriptor = new ToolDescriptor();
        descriptor.tool = tool;

        ink.icoding.llm.core.tool.annotations.ToolInfo toolAnnotation = tool.getClass()
                .getAnnotation(ink.icoding.llm.core.tool.annotations.ToolInfo.class);
        if (toolAnnotation != null) {
            descriptor.name = toolAnnotation.name();
            descriptor.description = toolAnnotation.description();
        } else {
            descriptor.name = tool.getClass().getSimpleName();
            descriptor.description = "";
        }

        // 从execute方法参数中查找ToolParam子类
        // 用getDeclaredMethods()排除桥方法和继承方法, 只看当前类声明的方法
        for (java.lang.reflect.Method method : tool.getClass().getDeclaredMethods()) {
            if ("execute".equals(method.getName()) && method.getParameterCount() == 1 && !method.isBridge()) {
                Class<?> paramType = method.getParameterTypes()[0];
                if (ToolParam.class.isAssignableFrom(paramType) && paramType != ToolParam.class) {
                    descriptor.paramClass = (Class<? extends ToolParam>) paramType;
                    break;
                }
            }
        }

        // 读取参数字段上的注解
        if (descriptor.paramClass != null) {
            for (Field field : descriptor.paramClass.getDeclaredFields()) {
                ink.icoding.llm.core.tool.annotations.Param paramAnnotation = field.getAnnotation(ink.icoding.llm.core.tool.annotations.Param.class);
                if (paramAnnotation != null) {
                    ParamInfo info = new ParamInfo();
                    info.name = field.getName();
                    info.type = mapType(field.getType());
                    if (field.getType().isArray()) {
                        info.itemType = mapType(field.getType().getComponentType());
                    }
                    info.description = paramAnnotation.description();
                    info.required = paramAnnotation.required();
                    info.enums = paramAnnotation.enums().length > 0 ? paramAnnotation.enums() : null;
                    descriptor.params.add(info);
                }
            }
        }

        return descriptor;
    }

    public Tool getTool() { return tool; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCallId() { return callId; }

    /**
     * 设置工具调用ID.
     *
     * @param callId LLM返回的调用ID
     */
    public void setCallId(String callId) { this.callId = callId; }

    public String getInputParams() { return inputParams; }
    public void setInputParams(String inputParams) { this.inputParams = inputParams; }

    public Class<? extends ToolParam> getParamClass() { return paramClass; }
    public List<ParamInfo> getParams() { return params; }

    /**
     * 将工具描述转换为LLM协议中的工具定义.
     *
     * @param modelType 模型类型, 决定输出格式
     * @return LLM协议格式的工具定义对象
     */
    public Object toLLMContent(ModelType modelType) {
        return switch (modelType) {
            case OpenAI, OpenAIResponse -> toOpenAISchema();
            case Anthropic -> toAnthropicSchema();
            default -> throw new IllegalArgumentException(modelType + " does not support tool schemas");
        };
    }

    /**
     * 转换为OpenAI格式的工具Schema.
     */
    private ObjectNode toOpenAISchema() {
        ObjectNode toolNode = MAPPER.createObjectNode();
        toolNode.put("type", "function");

        ObjectNode function = MAPPER.createObjectNode();
        function.put("name", name);
        function.put("description", description);

        ObjectNode parameters = MAPPER.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();
        ArrayNode requiredArray = MAPPER.createArrayNode();

        for (ParamInfo param : params) {
            ObjectNode paramNode = MAPPER.createObjectNode();
            paramNode.put("type", param.type);
            paramNode.put("description", param.description);
            if ("array".equals(param.type) && param.itemType != null) {
                ObjectNode items = MAPPER.createObjectNode();
                items.put("type", param.itemType);
                paramNode.set("items", items);
            }
            if (param.enums != null) {
                ArrayNode enumArray = MAPPER.createArrayNode();
                for (String e : param.enums) {
                    enumArray.add(e);
                }
                paramNode.set("enum", enumArray);
            }
            properties.set(param.name, paramNode);
            if (param.required) {
                requiredArray.add(param.name);
            }
        }

        parameters.set("properties", properties);
        parameters.set("required", requiredArray);
        function.set("parameters", parameters);
        toolNode.set("function", function);

        return toolNode;
    }

    /**
     * 转换为Anthropic格式的工具Schema.
     */
    private ObjectNode toAnthropicSchema() {
        ObjectNode toolNode = MAPPER.createObjectNode();
        toolNode.put("name", name);
        toolNode.put("description", description);

        ObjectNode inputSchema = MAPPER.createObjectNode();
        inputSchema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();
        ArrayNode requiredArray = MAPPER.createArrayNode();

        for (ParamInfo param : params) {
            ObjectNode paramNode = MAPPER.createObjectNode();
            paramNode.put("type", param.type);
            paramNode.put("description", param.description);
            if ("array".equals(param.type) && param.itemType != null) {
                ObjectNode items = MAPPER.createObjectNode();
                items.put("type", param.itemType);
                paramNode.set("items", items);
            }
            if (param.enums != null) {
                ArrayNode enumArray = MAPPER.createArrayNode();
                for (String e : param.enums) {
                    enumArray.add(e);
                }
                paramNode.set("enum", enumArray);
            }
            properties.set(param.name, paramNode);
            if (param.required) {
                requiredArray.add(param.name);
            }
        }

        inputSchema.set("properties", properties);
        inputSchema.set("required", requiredArray);
        toolNode.set("input_schema", inputSchema);

        return toolNode;
    }

    /**
     * 将Java类型映射为JSON Schema类型.
     */
    private static String mapType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class) return "integer";
        if (type == long.class || type == Long.class) return "integer";
        if (type == float.class || type == Float.class) return "number";
        if (type == double.class || type == Double.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type.isArray()) return "array";
        return "object";
    }

    /**
     * 参数信息内部类.
     */
    public static class ParamInfo {
        private String name;
        private String type;
        private String itemType;
        private String description;
        private boolean required;
        private String[] enums;

        public String getName() { return name; }
        public String getType() { return type; }
        public String getItemType() { return itemType; }
        public String getDescription() { return description; }
        public boolean isRequired() { return required; }
        public String[] getEnums() { return enums; }
    }
}
