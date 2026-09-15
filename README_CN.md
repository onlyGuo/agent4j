<p align="center">
  <br>
  <img src="https://img.shields.io/github/stars/onlyGuo/agent4j?style=flat-square&logo=github" alt="Stars">
  <img src="https://img.shields.io/github/forks/onlyGuo/agent4j?style=flat-square&logo=github" alt="Forks">
  <img src="https://img.shields.io/github/issues/onlyGuo/agent4j?style=flat-square&logo=github" alt="Issues">
  <img src="https://img.shields.io/badge/Java-17+-orange?style=flat-square&logo=openjdk&logoColor=white" alt="Java 17+">
  <img src="https://img.shields.io/badge/License-GPLv3-blue?style=flat-square" alt="License GPLv3">
  <a href="README.md"><img src="https://img.shields.io/badge/Language-English-red?style=flat-square" alt="English"></a>
  <br><br>
</p>

<h1 align="center">Agent4j</h1>

<h1 align="center">
  <img src="https://img.shields.io/badge/🤖-Agent4j-blueviolet?style=for-the-badge" alt="Agent4j">
</h1>

<p align="center">
  <b>Java 智能体框架 — 让 Java 开发者轻松构建 AI Agent</b><br>
  <sub>像 Claude Code、Cursor 一样，用自然语言驱动自动化任务</sub>
</p>

<p align="center">
  <a href="#快速开始">快速开始</a> &bull;
  <a href="#演示ai-驱动的全栈项目生成器">演示</a> &bull;
  <a href="#架构设计">架构设计</a> &bull;
  <a href="#api-参考">API 参考</a> &bull;
  <a href="#内置工具与技能">内置工具</a> &bull;
  <a href="#自定义工具">自定义工具</a> &bull;
  <a href="README.md">English</a>
</p>

---

## Agent4j 是什么？

Agent4j 是一个轻量级 Java 框架，让你只需几行代码就能构建**自主运行的 AI 智能体**。灵感来自 Claude Code、Cursor 等工具，它提供了构建智能体所需的一切：

- **读写本地文件** — 浏览目录、查看、创建、编辑、搜索文件
- **执行系统命令** — 跨平台支持 Windows/macOS/Linux
- **创建执行计划** — 将复杂任务拆分为有序步骤，逐步执行
- **生成子 Agent** — 为独立子任务派生临时子智能体
- **对接任意 LLM** — 支持 OpenAI、Anthropic 及所有 OpenAI 兼容 API
- **实时流式输出** — 基于 SSE 的流式响应，回调式渲染

<p align="center">
  <img src="doc/architecture.png" alt="Agent4j 架构图" width="800">
</p>

### 为什么选择 Agent4j？

| 特性 | 说明 |
|------|------|
| **零 Spring 依赖** | 纯 Java 17，仅依赖 Jackson + OkHttp |
| **内置 Agent 循环** | 自动处理 工具调用 → 执行 → 结果反馈 的完整循环 |
| **多模型提供商** | OpenAI Chat、OpenAI Responses、Anthropic Messages API |
| **计划 & 子 Agent** | 内置任务编排能力，支持复杂任务分解 |
| **流式输出** | SSE 实时流式传输，回调式渲染 |
| **自定义工具** | 注解一个 Java 类即可，框架自动处理 JSON Schema 生成与参数解析 |
| **技能系统** | 将工具分组并附带使用指南，帮助 LLM 更好地理解和使用工具 |

---

## 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>ink.icoding.llm</groupId>
    <artifactId>agent4j</artifactId>
    <version>2.3.8</version>
</dependency>
```

## 演示：AI 驱动的全栈项目生成器

> 本示例 MainTest 默认使用小米 [MiMo-v2.5-pro](https://mimo.mi.com/) 大模型作为 LLM（推荐 BASE_URL=https://token-plan-cn.xiaomimimo.com，MODEL=mimo-v2.5-pro），你也可以通过环境变量切换为任意 OpenAI 兼容模型。

`MainTest` 展示了一个真实场景：用一条自然语言指令，让智能体**自动生成 Chrome 插件 + Spring Boot 后端项目**。

您可以直接在项目中查看 `MainTest` 的完整实现，或者直接运行它，观察智能体如何一步步完成任务, 以下是`MainTest`的核心代码片段：

```java
AgentClient agent = new AgentClient();
agent.setName("CodeAgent");
agent.setDescription("一个专业的全栈开发工程师，擅长创建 Chrome 插件和 Java Spring Boot 后端应用。");
agent.setModel(llm);
agent.getSkills().addAll(BuiltInSkills.all());

session.command("""
    请在 /Users/xiatian/Desktop/plugin 目录下创建一个完整的 Chrome 插件项目和配套的 Java Spring Boot 后端。
    Chrome 插件选中文本后发送到后端 /answer 接口，后端读取 da.md 作为参考资料，调用 LLM 生成答案。
    """)
    .then(new AgentResultHandler() { ... })
    .error(e -> e.printStackTrace());
```

### 智能体自动完成的事情：

```
[1] 读取并分析 da.md
      └─ 确认文件存在，包含 860 行药品说明书数据

[2] 自动创建执行计划（5 个步骤）：
      ┌─ Step 1: 检查 da.md 文件内容，确认参考数据存在
      ├─ Step 2: 创建 Chrome 插件目录结构 (manifest.json, content.js, content.css)
      ├─ Step 3: 创建 Spring Boot 后端项目 (pom.xml, Controller, Service)
      ├─ Step 4: 创建配置文件 (application.properties)
      └─ Step 5: 验证项目完整性

[3] 逐步执行计划：
      ├─ Step 1: 读取 da.md，确认数据格式正确
      ├─ Step 2: 创建全部 Chrome 插件文件
      ├─ Step 3: 创建全部后端文件
      ├─ Step 4: 生成配置文件
      └─ Step 5: 验证目录结构完整性

[4] 自动排查错误：
      ├─ 发现缺少依赖 → 自动添加到 pom.xml
      ├─ 发现重复包结构 → 自动清理
      └─ 验证文件路径和配置正确性

[5] 产出完整可运行的项目
```

### 更多应用场景

这个示例只是冰山一角。Agent4j 可以应用于：

| 场景 | 说明 |
|------|------|
| **AI 编程助手** | 类似 Claude Code — 读代码、写代码、调试、重构 |
| **自动化运维** | 服务器监控、日志分析、故障排查 |
| **文件整理** | 批量重命名、分类、去重、格式转换 |
| **数据处理** | 解析 CSV/JSON、生成报表、数据清洗 |
| **自动化办公** | 文档生成、邮件撰写、表格处理 |
| **DevOps 自动化** | CI/CD 流水线、容器管理、部署自动化 |

> 关键在于：为智能体配备**正确的工具和技能**。

---

## 架构设计

```
AgentClient                         # 智能体定义（名称、模型、工具、技能）
  │
  +-- AgentClientSession            # 会话（对话历史、上下文）
        │
        +-- command("task")         # 发送指令
        │     │
        │     +-- LLMModel.ask()    # 调用 LLM（携带工具定义）
        │           │
        │           +-- [Agent 循环]  LLM → 工具调用 → 执行 → 反馈 → LLM
        │           │                  （重复直到 LLM 返回纯文本）
        │           │
        │           +-- ResultHandler 回调：
        │                 - onMessage(text)      # 流式文本
        │                 - onThink(text)        # 思考/推理过程
        │                 - onTool(tool, status, result) # 工具调用生命周期及结果
        │
        +-- Plan（内置工具）          # 将任务拆分为步骤，逐步执行
        +-- Sub-Agent（内置工具）     # 派生子智能体处理独立子任务
```

### 核心概念

| 概念 | 类 | 说明 |
|------|-----|------|
| **智能体** | `AgentClient` | 定义智能体：名称、描述、模型、工具集、技能集 |
| **会话** | `AgentClientSession` | 管理一次交互的对话历史和上下文 |
| **模型** | `LLMModel` | LLM 提供商抽象（OpenAI、Anthropic 等） |
| **工具** | `Tool<T>` | 可被 LLM 调用的能力，带类型化参数 |
| **技能** | `Skill` | 工具分组 + 使用指南，帮助 LLM 理解如何使用 |
| **计划** | `Plan` | 有序步骤列表，由智能体逐步执行 |
| **子 Agent** | `AgentClient` | 临时子智能体，用于处理独立子任务 |

### 关系图

```
AgentClient ──拥有──> LLMModel          （大脑）
    │
    ├──拥有──> List<Tool>               （独立工具）
    ├──拥有──> List<Skill>              （技能 = 工具组 + 使用指南）
    │
    └──创建──> AgentClientSession       （会话）
                   │
                   ├── 维护──> List<Message>         （对话历史）
                   ├── 拦截──> create_plan           （内置工具 → Plan）
                   └── 拦截──> create_sub_agent      （内置工具 → Sub-Agent）
```

---

## API 参考

### 创建 LLM 模型

```java
// OpenAI / OpenAI 兼容（DeepSeek、通义千问、智谱等）
LLMModel llm = LLMModel.create(ModelType.OpenAI, baseUrl, modelName, apiKey);

// Anthropic Claude
LLMModel llm = LLMModel.create(ModelType.Anthropic, baseUrl, modelName, apiKey);

// OpenAI Responses API
LLMModel llm = LLMModel.create(ModelType.OpenAIResponse, baseUrl, modelName, apiKey);
```

### 构建智能体

```java
AgentClient agent = new AgentClient();
agent.setName("CodeAgent");
agent.setDescription("一个资深软件工程师");
agent.setModel(llm);

// 添加单个工具
agent.getTools().add(new MyCustomTool());

// 添加技能组
agent.getSkills().add(BuiltInSkills.fileSystem());
agent.getSkills().add(BuiltInSkills.commandExecution());
agent.getSkills().add(BuiltInSkills.orchestration());

// 或者一次性添加所有内置技能
agent.getSkills().addAll(BuiltInSkills.all());
```

### 发送指令

```java
AgentClientSession session = agent.createSession();

// 简单指令
session.command("你好！")
    .then(new AgentResultHandler() {
        public void onMessage(String msg) { System.out.print(msg); }
    })
    .error(e -> e.printStackTrace());

// 携带文件附件
Message msg = Message.fromUser("分析这张截图");
msg.appendAttachment(new File("screenshot.png"));
session.command(msg)
    .then(handler)
    .error(errHandler);
```

### AgentResultHandler 回调

```java
session.command(task).then(new AgentResultHandler() {

    // LLM 流式输出文本
    public void onMessage(String message) { }

    // LLM 流式输出思考/推理过程
    public void onThink(String think) { }

    // 工具生命周期：PREPARING → CALLING → COMPLETED；仅 COMPLETED 时 result 非 null
    public void onTool(ToolDescriptor tool, ToolStatus status, Object result) { }

    // LLM 创建了一个计划
    public void onPlanCreated(Plan plan) { }

    // 计划开始执行
    public void onPlanExecuted(Plan plan) { }

    // 计划的某个步骤开始执行
    public void onPlanStepStart(Plan plan, int current, int total, String step) { }

    // 计划的某个步骤执行完成
    public void onPlanStepComplete(Plan plan, int current, int total, String step, String result) { }

    // 计划步骤执行过程中的工具调用
    public void onPlanStepTool(Plan plan, ToolDescriptor tool, ToolStatus status, Object result) { }

    // 计划的某个步骤执行失败
    public void onPlanStepError(Plan plan, int current, int total, String step, Exception error) { }

    // 派生了一个子 Agent
    public void onSubAgent(AgentClient agent, String message) { }

    // 子 Agent 开始执行
    public void onSubAgentStart(AgentClient agent, String task) { }

    // 子 Agent 返回结果
    public void onSubAgentResult(AgentClient agent, String result) { }
});
```

### 直接使用 LLM（不通过 Agent）

```java
LLMModel llm = LLMModel.create(ModelType.OpenAI, baseUrl, model, apiKey);

// 简单问答
llm.ask(Message.fromUser("什么是 Java？"))
    .then(new ResultHandler() {
        public void onMessage(String msg) { System.out.print(msg); }
    })
    .error(e -> e.printStackTrace());

// 携带工具
llm.ask(messages, tools)
    .then(handler)
    .error(errHandler);
```

---

## 内置工具与技能

### 文件系统操作技能 (FileSystemSkill)

| 工具 | 说明 |
|------|------|
| `list_directory_tree` | 浏览目录结构，支持配置深度 |
| `view_file` | 查看文件内容，大文件自动截断 |
| `create_file` | 创建新文件，自动创建父目录 |
| `edit_file` | 替换指定行范围的内容 |
| `delete_file` | 删除文件 |
| `move_file` | 移动或重命名文件 |
| `search_in_file` | 在单个文件中搜索关键字 |
| `search_in_directory` | 递归搜索目录中所有文件内容 |
| `search_files` | 按文件名搜索文件 |

### 命令执行技能 (CommandExecutionSkill)

| 工具 | 说明 |
|------|------|
| `execute_command` | 执行系统命令（Windows 自动用 PowerShell，macOS/Linux 用 bash）。超时 60 秒。 |

### 编排技能 (OrchestrationSkill)

| 工具 | 说明 |
|------|------|
| `create_plan` | 将复杂任务拆分为有序步骤，每步独立调用 LLM 执行 |
| `create_sub_agent` | 派生临时子智能体处理独立子任务，继承父级工具和技能 |

---

## 自定义工具

创建自定义工具只需两步：定义参数类 + 实现 Tool 接口。

### 第一步：定义参数

```java
public class SearchParam extends ToolParam {

    @Param(description = "搜索关键词")
    private String query;

    @Param(required = false, description = "返回结果数量", enums = {"5", "10", "20"})
    private String limit;

    // getter 和 setter
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getLimit() { return limit; }
    public void setLimit(String limit) { this.limit = limit; }
}
```

### 第二步：实现工具

```java
@ToolInfo(name = "web_search", description = "搜索互联网内容")
public class WebSearchTool implements Tool<SearchParam> {

    @Override
    public String execute(SearchParam param) {
        // 你的实现逻辑
        String results = searchWeb(param.getQuery(), param.getLimit());
        return results;
    }
}
```

### 第三步：注册到智能体

```java
agent.getTools().add(new WebSearchTool());
```

框架会自动完成：
- 从 `@ToolInfo` 和 `@Param` 注解生成 JSON Schema
- 将 LLM 返回的工具调用参数反序列化为你的 `ToolParam` 子类
- 执行工具并将结果反馈给 LLM
- 通过 `ResultHandler.onTool()` 回调报告工具状态

### 自定义技能

将多个工具组合为技能，并附带使用指南：

```java
Skill webSkill = new Skill(
    "网络调研",                                                  // 标题
    "搜索和获取互联网信息",                                       // 描述
    List.of(new WebSearchTool(), new WebFetchTool()),            // 工具列表
    """
    ## 网络调研指南
    - 使用 `web_search` 搜索相关页面
    - 使用 `web_fetch` 获取完整页面内容
    - 回答时请注明信息来源
    """);                                                        // 内容（使用指南）

agent.getSkills().add(webSkill);
```

---

## 会话序列化

保存和恢复对话状态：

```java
// 保存
String json = session.serialization();

// 恢复
AgentClientSession restored = agent.getSessionFromSerialization(json);
```

---

## 支持的模型

| 提供商 | ModelType | API 端点 | 模型示例 |
|--------|-----------|---------|---------|
| **OpenAI** | `ModelType.OpenAI` | `/v1/chat/completions` | gpt-4o, gpt-4o-mini, o1 等 |
| **Anthropic** | `ModelType.Anthropic` | `/v1/messages` | claude-sonnet-4, claude-opus-4 等 |
| **OpenAI Responses** | `ModelType.OpenAIResponse` | `/v1/responses` | gpt-4o, o1 等 |
| **OpenAI 兼容** | `ModelType.OpenAI` | 自定义 baseUrl | DeepSeek、通义千问、智谱 等 |

---

## 项目结构

```
ink.icoding.llm
├── agent/                          # 智能体层
│   ├── AgentClient                 # 智能体定义
│   ├── AgentClientSession          # 会话管理
│   ├── AgentSessionResult          # 链式结果对象 (.then/.error)
│   ├── AgentResultHandler          # 回调接口（智能体级）
│   ├── Plan                        # 执行计划模型
│   └── Skill                       # 技能（工具组 + 使用指南）
│
├── core/
│   ├── entity/                     # 数据模型
│   │   ├── Message                 # 对话消息
│   │   ├── MessageAttachment       # 文件/图片附件
│   │   ├── ModelType               # LLM 提供商枚举
│   │   └── MemoryMultipartFile     # 内存文件
│   │
│   ├── model/                      # LLM 抽象层
│   │   ├── LLMModel                # LLM 接口 + 工厂方法
│   │   ├── LLMResult               # 链式结果对象
│   │   ├── ResultHandler           # 回调接口（LLM 级）
│   │   └── impl/
│   │       ├── OpenAIChatModel     # OpenAI Chat Completions
│   │       ├── AnthropicModel      # Anthropic Messages
│   │       └── OpenAIResponseModel # OpenAI Responses
│   │
│   └── tool/                       # 工具系统
│       ├── Tool                    # 工具接口
│       ├── ToolParam               # 参数基类
│       ├── ToolDescriptor          # 反射 introspection + JSON Schema 生成
│       ├── ToolExecutor            # 工具执行策略
│       ├── ToolStatus              |
