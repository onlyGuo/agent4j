package ink.icoding.llm;

import ink.icoding.llm.agent.AgentClient;
import ink.icoding.llm.agent.AgentClientSession;
import ink.icoding.llm.agent.AgentResultHandler;
import ink.icoding.llm.agent.Plan;
import ink.icoding.llm.core.entity.EmbeddingInput;
import ink.icoding.llm.core.entity.ModelType;
import ink.icoding.llm.core.model.EmbeddingModel;
import ink.icoding.llm.core.model.EmbeddingResult;
import ink.icoding.llm.core.model.LLMModel;
import ink.icoding.llm.core.tool.ToolDescriptor;
import ink.icoding.llm.core.tool.ToolStatus;
import ink.icoding.llm.core.tool.builtin.skill.BuiltInSkills;
import ink.icoding.llm.core.tool.builtin.skill.FileSystemSkill;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

public class MainTest {

    public static void main(String[] args) {
        enbeddingTest();
    }


    public static void enbeddingTest() {
        String baseURL = System.getenv("ENBEDDING_BASE_URL");
        String apiKey = System.getenv("ENBMEDDING_API_KEY");
        String model = System.getenv("ENBEDDING_MODEL");
        byte[] imageBytes = null;

        try (InputStream inputStream = new FileInputStream("/Users/xiatian/Documents/1.png")){
            imageBytes = inputStream.readAllBytes();
        }catch (Exception e){
            throw new RuntimeException(e);
        }

        EmbeddingModel embeddingModel = EmbeddingModel.create(ModelType.DashScopeMultimodalEmbedding, baseURL, model, apiKey);
        EmbeddingResult embed = embeddingModel.embed(EmbeddingInput.create().appendImage(imageBytes, "image/png"));

        System.out.println("Embedding Result: " + embed);


        System.out.println("=====================> 结束 <============================");
    }

    public static void agentTest(String[] args) {
        String baseURL = System.getenv("BASE_URL");
        String apiKey = System.getenv("API_KEY");
        String model = System.getenv("MODEL");

        LLMModel llm = LLMModel.create(ModelType.OpenAI, baseURL, model, apiKey);

        AgentClient agent = new AgentClient();
        agent.setName("CodeAgent");
        agent.setDescription("一个专业的全栈开发工程师, 擅长创建Chrome插件和Java Spring Boot后端应用.");
        agent.setModel(llm);
        agent.getSkills().addAll(BuiltInSkills.all());

        AgentClientSession session = agent.createSession();

        String task = """
                请在 /Users/xiatian/Desktop/plugin 目录下创建一个完整的Chrome插件项目和配套的Java Spring Boot后端.

                ## Chrome插件需求
                1. 在网页最前方右上角悬浮显示一个半透明浮层
                2. 鼠标选中网页中的任意文本时, 弹出一个悬浮按钮(标注为"回答")
                3. 点击"回答"按钮后, 将选中的文本内容通过POST请求发送到后端接口 http://localhost:8080/answer
                4. 请求体为JSON格式: {"text": "选中的文本内容"}
                5. 后端返回的答案文本显示在右上角的浮层中

                ## 后端需求
                1. Java Spring Boot应用, 监听 /answer 接口
                2. 接收POST请求, 请求体包含 text 字段
                3. 读取项目目录下 da.md 文件的内容作为参考资料
                4. 将参考资料和选中文本一起发送给LLM, 生成答案
                5. 将LLM返回的答案文本作为响应返回给前端

                ## 项目结构
                /Users/xiatian/Desktop/plugin/
                ├── chrome-extension/          # Chrome插件
                │   ├── manifest.json
                │   ├── content.js
                │   ├── content.css
                │   └── popup.html (如需要)
                ├── backend/                   # Spring Boot后端
                │   ├── pom.xml
                │   ├── src/main/java/...
                │   └── src/main/resources/
                └── da.md                      # 参考资料文件(我已经准备好了放在了/Users/xiatian/Desktop/plugin目录下, 你直接拿去用就行)

                请逐步创建所有文件, 确保代码完整可运行.
                """;

        session.command(task).then(new AgentResultHandler() {
            @Override
            public void onMessage(String message) {
                System.out.print(message);
            }

            @Override
            public void onThink(String think) {
                System.out.print(think);
            }

            @Override
            public void onTool(ToolDescriptor tool, ToolStatus status, Object result) {
                switch (status) {
                    case PREPARING -> {
                        System.out.println("\n[工具准备] " + tool.getName());
                        if (tool.getInputParams() != null && !tool.getInputParams().isEmpty()) {
                            System.out.println("  入参: " + tool.getInputParams());
                        }
                    }
                    case CALLING -> System.out.println("[工具调用中] " + tool.getName());
                    case COMPLETED -> System.out.println("[工具完成] " + tool.getName() + ": " + result);
                }
            }

            @Override
            public void onPlanCreated(Plan plan) {
                System.out.println("\n[计划创建] " + plan.getName());
                System.out.println("  描述: " + plan.getDescription());
                System.out.println("  步骤数: " + plan.getSteps().size());
                for (int i = 0; i < plan.getSteps().size(); i++) {
                    System.out.println("    " + (i + 1) + ". " + plan.getSteps().get(i));
                }
            }

            @Override
            public void onPlanExecuted(Plan plan) {
                System.out.println("\n[计划执行开始] " + plan.getName());
            }

            @Override
            public void onPlanStepStart(Plan plan, int current, int total, String step) {
                System.out.println("\n  [步骤 " + current + "/" + total + " 开始] " + step);
            }

            @Override
            public void onPlanStepComplete(Plan plan, int current, int total, String step, String result) {
                System.out.println("  [步骤 " + current + "/" + total + " 完成] " + step);
            }

            @Override
            public void onPlanStepTool(Plan plan, ToolDescriptor tool, ToolStatus status, Object result) {
                switch (status) {
                    case PREPARING -> System.out.println("    [计划工具准备] " + tool.getName());
                    case CALLING -> System.out.println("    [计划工具调用中] " + tool.getName());
                    case COMPLETED -> System.out.println("    [计划工具完成] " + tool.getName() + ": " + result);
                }
            }

            @Override
            public void onPlanStepError(Plan plan, int current, int total, String step, Exception error) {
                System.out.println("  [步骤 " + current + "/" + total + " 失败] " + step + " - " + error.getMessage());
            }

            @Override
            public void onSubAgent(AgentClient subAgent, String message) {
                System.out.println("\n[子Agent创建] " + subAgent.getName());
                System.out.println("  描述: " + subAgent.getDescription());
                System.out.println("  任务: " + message);
            }

            @Override
            public void onSubAgentStart(AgentClient subAgent, String task) {
                System.out.println("\n[子Agent执行开始] " + subAgent.getName());
            }

            @Override
            public void onSubAgentResult(AgentClient subAgent, String result) {
                System.out.println("\n[子Agent完成] " + subAgent.getName());
                System.out.println("  结果: " + result);
            }
        }).error(e -> {
            System.err.println("\n[错误] " + e.getMessage());
            e.printStackTrace();
        }).execute();

        System.out.println("=====================> 结束 <============================");
    }
}
