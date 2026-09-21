<p align="center">
  <br>
  <img src="https://img.shields.io/github/stars/onlyGuo/agent4j?style=flat-square&logo=github" alt="Stars">
  <img src="https://img.shields.io/github/forks/onlyGuo/agent4j?style=flat-square&logo=github" alt="Forks">
  <img src="https://img.shields.io/github/issues/onlyGuo/agent4j?style=flat-square&logo=github" alt="Issues">
  <img src="https://img.shields.io/badge/Java-17+-orange?style=flat-square&logo=openjdk&logoColor=white" alt="Java 17+">
  <img src="https://img.shields.io/badge/License-GPLv3-blue?style=flat-square" alt="License GPLv3">
  <a href="README_CN.md"><img src="https://img.shields.io/badge/语言-中文-red?style=flat-square" alt="中文文档"></a>
  <br><br>
</p>

<h1 align="center">Agent4j</h1>

<h1 align="center">
  <img src="https://img.shields.io/badge/🤖-Agent4j-blueviolet?style=for-the-badge" alt="Agent4j">
</h1>

<p align="center">
  <b>A Java framework for building LLM-powered intelligent agents</b><br>
  <sub>Build agents like Claude Code, Cursor, and other AI automation tools in Java</sub>
</p>

<p align="center">
  <a href="#quick-start">Quick Start</a> &bull;
  <a href="#demo">Demo</a> &bull;
  <a href="#architecture">Architecture</a> &bull;
  <a href="#api-reference">API Reference</a> &bull;
  <a href="#built-in-tools--skills">Built-in Tools</a> &bull;
  <a href="#custom-tools">Custom Tools</a> &bull;
  <a href="README_CN.md">中文文档</a>
</p>

---

## What is Agent4j?

Agent4j is a lightweight Java framework that lets you build **autonomous AI agents** with just a few lines of code. Inspired by tools like Claude Code and Cursor, it provides the building blocks for creating agents that can:

- **Read and write files** on the local filesystem
- **Execute shell commands** across platforms (Windows/macOS/Linux)
- **Create execution plans** and break down complex tasks into steps
- **Spawn sub-agents** to handle independent sub-tasks
- **Call any LLM** including OpenAI, Anthropic, and OpenAI-compatible APIs
- **Stream responses in real-time** via SSE (Server-Sent Events)

<p align="center">
  <img src="doc/architecture.png" alt="Agent4j Architecture" width="800">
</p>

### Why Agent4j?

| Feature | Description |
|---------|-------------|
| **Zero Dependencies on Spring** | Pure Java 17, only Jackson + OkHttp |
| **Agent Loop Built-in** | Automatic tool call → execute → feedback cycle |
| **Multi-Provider** | OpenAI Chat, OpenAI Responses, Anthropic Messages API |
| **Plan & Sub-Agent** | Built-in orchestration for complex task decomposition |
| **Streaming** | Real-time SSE streaming with callback-based rendering |
| **Custom Tools** | Annotate a Java class — that's it. Framework handles the rest |
| **Skills System** | Group tools with usage guides for better LLM understanding |

---

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>ink.icoding.llm</groupId>
    <artifactId>agent4j</artifactId>
    <version>2.4.0</version>
</dependency>
```

### Minimal Example

```java
// 1. Create an LLM model
LLMModel llm = LLMModel.create(ModelType.OpenAI, "https://api.openai.com", "gpt-4o", "sk-...");

// 2. Create an agent with built-in skills
AgentClient agent = new AgentClient();
agent.setName("MyAgent");
agent.setDescription("A helpful assistant");
agent.setModel(llm);
agent.getSkills().addAll(BuiltInSkills.all());

// 3. Send a command and handle the streaming response
agent.createSession()
    .command("List all Java files in the current directory")
    .then(new AgentResultHandler() {
        public void onMessage(String msg) { System.out.print(msg); }
        public void onTool(ToolDescriptor tool, ToolStatus status, Object result) {
            System.out.println("[" + status + "] " + tool.getName());
        }
    })
    .error(e -> e.printStackTrace());
```

---

<a name="demo"></a>

## Demo: AI-Powered Full-Stack Project Generator

`MainTest` demonstrates a real-world scenario: using an agent to **automatically generate a Chrome extension and Spring Boot backend** from a single natural language prompt.

> This demo uses [**MiMo-v2.5-pro**](https://mimo.mi.com/) by Xiaomi as the underlying LLM — a model with strong reasoning and tool-calling capabilities. You can swap in any OpenAI-compatible model by changing the environment variables.

```java
// Read model config from environment variables
String baseURL = System.getenv("BASE_URL");   // e.g. https://token-plan-cn.xiaomimimo.com
String apiKey  = System.getenv("API_KEY");
String model   = System.getenv("MODEL");       // e.g. mimo-v2.5-pro

LLMModel llm = LLMModel.create(ModelType.OpenAI, baseURL, model, apiKey);

AgentClient agent = new AgentClient();
agent.setName("CodeAgent");
agent.setDescription("A professional full-stack engineer, skilled in Chrome extensions and Spring Boot.");
agent.setModel(llm);
agent.getSkills().addAll(BuiltInSkills.all());

session.command("""
    Create a Chrome extension and Spring Boot backend in /Users/dev/plugin.
    The extension should send selected text to the backend's /answer endpoint.
    The backend should use da.md as reference data and call an LLM to generate answers.
    """)
    .then(new AgentResultHandler() { ... })
    .error(e -> e.printStackTrace());
```

### What the agent does automatically:

```
[1] Reads and analyzes da.md — understands the reference data structure

[2] Creates a Plan with 5 steps:
      1. Verify da.md exists and is readable
      2. Create Chrome extension (manifest.json, content.js, content.css)
      3. Create Spring Boot backend (pom.xml, controller, service)
      4. Create configuration files
      5. Verify project completeness

[3] Executes each step sequentially:
      - Step 1: Reads da.md, confirms 860 lines of pharmaceutical data
      - Step 2: Creates all Chrome extension files
      - Step 3: Creates all backend files
      - Step 4: Generates application.properties
      - Step 5: Validates the directory tree

[4] Auto-fixes issues:
      - Detects missing dependencies → adds them to pom.xml
      - Finds duplicate package structures → cleans up
      - Validates file paths and configuration

[5] Delivers a complete, runnable project
```

### Use Cases

This demo is just one example. Agent4j can be used for:

| Use Case | Description |
|----------|-------------|
| **AI Coding Assistant** | Like Claude Code — read, write, debug, and refactor code |
| **Automated Operations** | Server monitoring, log analysis, incident response |
| **File Organization** | Batch rename, sort, deduplicate, and transform files |
| **Data Processing** | Parse CSV/JSON, generate reports, transform datasets |
| **Automated Office Work** | Document generation, email drafting, spreadsheet automation |
| **DevOps Automation** | CI/CD pipeline setup, container management, deployment |

> The key is giving the agent the **right tools and skills** for your domain.

---

## Architecture

```
AgentClient                         # Agent definition (name, model, tools, skills)
  |
  +-- AgentClientSession            # Conversation session (history, context)
        |
        +-- command("task")         # Send a command
        |     |
        |     +-- LLMModel.ask()    # Call LLM with tools
        |           |
        |           +-- [Agent Loop]  LLM → tool_calls → execute → feed back → LLM
        |           |                  (repeats until LLM returns plain text)
        |           |
        |           +-- ResultHandler callbacks:
        |                 - onMessage(text)    # Streaming text
        |                 - onThink(text)      # Reasoning/thinking
        |                 - onTool(tool, status, result) # Tool lifecycle and result
        |
        +-- Plan (built-in tool)    # Break task into steps, execute sequentially
        +-- Sub-Agent (built-in)    # Spawn child agent for independent sub-tasks
```

### Core Concepts

| Concept | Class | Description |
|---------|-------|-------------|
| **Agent** | `AgentClient` | Defines an agent: name, description, model, tools, skills |
| **Session** | `AgentClientSession` | Manages conversation history and context for one interaction |
| **Model** | `LLMModel` | Abstraction over LLM providers (OpenAI, Anthropic, etc.) |
| **Tool** | `Tool<T>` | A callable capability with typed parameters |
| **Skill** | `Skill` | A group of tools + usage guide for the LLM |
| **Plan** | `Plan` | A list of steps executed sequentially by the agent |
| **Sub-Agent** | `AgentClient` | A temporary child agent spawned for a sub-task |

### Relationship Diagram

```
AgentClient ──has──> LLMModel          (the brain)
    │
    ├──has──> List<Tool>               (individual tools)
    ├──has──> List<Skill>              (tool groups + guides)
    │
    └──creates──> AgentClientSession   (per-conversation)
                      │
                      ├── maintains──> List<Message>      (conversation history)
                      ├── intercepts──> create_plan       (built-in tool → Plan)
                      └── intercepts──> create_sub_agent  (built-in tool → Sub-Agent)
```

---

## API Reference

### Creating an LLM Model

```java
// OpenAI / OpenAI-compatible (DeepSeek, Qwen, etc.)
LLMModel llm = LLMModel.create(ModelType.OpenAI, baseUrl, modelName, apiKey);

// Anthropic Claude
LLMModel llm = LLMModel.create(ModelType.Anthropic, baseUrl, modelName, apiKey);

// OpenAI Responses API
LLMModel llm = LLMModel.create(ModelType.OpenAIResponse, baseUrl, modelName, apiKey);
```

### Creating an Embedding Model

```java
// OpenAI / OpenAI-compatible text embeddings, using /v1/embeddings
EmbeddingModel textEmbedding = EmbeddingModel.create(
        ModelType.OpenAIEmbedding, "https://api.openai.com", "text-embedding-3-small", apiKey);
EmbeddingResult texts = textEmbedding.embedTexts(List.of("first text", "second text"));
List<Double> vector = texts.getEmbedding().getVector();

// DashScope native multimodal embeddings: text, image URL/data URI, and video URL/data URI
EmbeddingModel multimodalEmbedding = EmbeddingModel.create(
        ModelType.DashScopeMultimodalEmbedding,
        "https://dashscope.aliyuncs.com", "qwen3-vl-embedding", dashScopeApiKey);
multimodalEmbedding.setFusionEnabled(true); // required for fused vectors with models such as qwen3-vl-embedding
EmbeddingResult result = multimodalEmbedding.embed(EmbeddingInput.create()
        .appendText("red running shoe")
        .appendImage("https://example.com/shoe.png"));

// Local image bytes are converted to a data URI automatically.
EmbeddingInput imageOnly = EmbeddingInput.create()
        .appendImage(Files.readAllBytes(Path.of("shoe.png")), "image/png");
```

`EmbeddingResult` contains `Embedding` values in input order, their type when returned by a multimodal API
(`text`, `image`, or `fused`), and token usage when available. Use `setDimensions(...)` to request a supported vector size.

### Building an Agent

```java
AgentClient agent = new AgentClient();
agent.setName("CodeAgent");
agent.setDescription("A senior software engineer");
agent.setModel(llm);

// Add individual tools
agent.getTools().add(new MyCustomTool());

// Add skill groups
agent.getSkills().add(BuiltInSkills.fileSystem());
agent.getSkills().add(BuiltInSkills.commandExecution());
agent.getSkills().add(BuiltInSkills.orchestration());

// Or add all built-in skills at once
agent.getSkills().addAll(BuiltInSkills.all());
```

### Sending Commands

```java
AgentClientSession session = agent.createSession();

// Simple command
session.command("Hello!")
    .then(new AgentResultHandler() {
        public void onMessage(String msg) { System.out.print(msg); }
    })
    .error(e -> e.printStackTrace());

// With file attachment
Message msg = Message.fromUser("Analyze this screenshot");
msg.appendAttachment(new File("screenshot.png"));
session.command(msg)
    .then(handler)
    .error(errHandler);
```

### AgentResultHandler Callbacks

```java
session.command(task).then(new AgentResultHandler() {

    // LLM streams text
    public void onMessage(String message) { }

    // LLM streams reasoning/thinking
    public void onThink(String think) { }

    // Tool lifecycle: PREPARING → CALLING → COMPLETED; result is non-null only for COMPLETED
    public void onTool(ToolDescriptor tool, ToolStatus status, Object result) { }

    // A plan was created by the LLM
    public void onPlanCreated(Plan plan) { }

    // A plan starts executing
    public void onPlanExecuted(Plan plan) { }

    // A plan step starts
    public void onPlanStepStart(Plan plan, int current, int total, String step) { }

    // A plan step completes
    public void onPlanStepComplete(Plan plan, int current, int total, String step, String result) { }

    // Tool call during a plan step
    public void onPlanStepTool(Plan plan, ToolDescriptor tool, ToolStatus status, Object result) { }

    // A plan step failed
    public void onPlanStepError(Plan plan, int current, int total, String step, Exception error) { }

    // A sub-agent was spawned
    public void onSubAgent(AgentClient agent, String message) { }

    // A sub-agent starts executing
    public void onSubAgentStart(AgentClient agent, String task) { }

    // A sub-agent returns its result
    public void onSubAgentResult(AgentClient agent, String result) { }
});
```

### Direct LLM Usage (Without Agent)

```java
LLMModel llm = LLMModel.create(ModelType.OpenAI, baseUrl, model, apiKey);

// Simple ask
llm.ask(Message.fromUser("What is Java?"))
    .then(new ResultHandler() {
        public void onMessage(String msg) { System.out.print(msg); }
    })
    .error(e -> e.printStackTrace());

// With tools
llm.ask(messages, tools)
    .then(handler)
    .error(errHandler);
```

---

## Built-in Tools & Skills

### FileSystem Skill

| Tool | Description |
|------|-------------|
| `list_directory_tree` | Browse directory structure with configurable depth |
| `view_file` | Read file content, auto-truncates large files |
| `create_file` | Create a new file, auto-creates parent directories |
| `edit_file` | Replace content at specified line ranges |
| `delete_file` | Delete a file |
| `move_file` | Move or rename a file |
| `search_in_file` | Search for keywords within a single file |
| `search_in_directory` | Recursively search content across all files |
| `search_files` | Search files by name pattern |

### CommandExecution Skill

| Tool | Description |
|------|-------------|
| `execute_command` | Run shell commands (auto-detects PowerShell on Windows, bash on Unix). 60s timeout. |

### Orchestration Skill

| Tool | Description |
|------|-------------|
| `create_plan` | Break a complex task into sequential steps. Each step runs as an independent LLM call. |
| `create_sub_agent` | Spawn a temporary child agent for an independent sub-task. Inherits parent's tools & skills. |

---

## Custom Tools

Creating a custom tool is simple — define a parameter class and implement the `Tool` interface:

### Step 1: Define Parameters

```java
public class SearchParam extends ToolParam {

    @Param(description = "Search query")
    private String query;

    @Param(required = false, description = "Max results", enums = {"5", "10", "20"})
    private String limit;

    // getters and setters
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getLimit() { return limit; }
    public void setLimit(String limit) { this.limit = limit; }
}
```

### Step 2: Implement the Tool

```java
@ToolInfo(name = "web_search", description = "Search the web for information")
public class WebSearchTool implements Tool<SearchParam> {

    @Override
    public String execute(SearchParam param) {
        // Your implementation here
        String results = searchWeb(param.getQuery(), param.getLimit());
        return results;
    }
}
```

### Step 3: Register with Agent

```java
agent.getTools().add(new WebSearchTool());
```

The framework automatically:
- Generates the JSON Schema for the LLM from `@ToolInfo` and `@Param` annotations
- Deserializes LLM tool call arguments into your `ToolParam` subclass
- Executes the tool and feeds the result back to the LLM
- Reports tool status via `ResultHandler.onTool()` callbacks

### Custom Skill

Group multiple tools into a skill with a usage guide:

```java
Skill webSkill = new Skill(
    "Web Research",                                          // title
    "Search and retrieve information from the web",          // description
    List.of(new WebSearchTool(), new WebFetchTool()),        // tools
    """
    ## Web Research Guide
    - Use `web_search` to find relevant pages
    - Use `web_fetch` to retrieve full page content
    - Always cite sources in your response
    """);                                                    // content (usage guide for LLM)

agent.getSkills().add(webSkill);
```

---

## Session Serialization

Save and restore conversation state:

```java
// Save
String json = session.serialization();

// Restore
AgentClientSession restored = agent.getSessionFromSerialization(json);
```

---

## Supported Models

| Provider | ModelType | API Endpoint | Models |
|----------|-----------|-------------|--------|
| **OpenAI** | `ModelType.OpenAI` | `/v1/chat/completions` | gpt-4o, gpt-4o-mini, o1, etc. |
| **Anthropic** | `ModelType.Anthropic` | `/v1/messages` | claude-sonnet-4, claude-opus-4, etc. |
| **OpenAI Responses** | `ModelType.OpenAIResponse` | `/v1/responses` | gpt-4o, o1, etc. |
| **OpenAI-compatible** | `ModelType.OpenAI` | Custom baseUrl | DeepSeek, Qwen, GLM, etc. |
| **OpenAI-compatible Embeddings** | `ModelType.OpenAIEmbedding` | `/v1/embeddings` | text-embedding-3-small, text-embedding-v4, etc. |
| **DashScope multimodal Embeddings** | `ModelType.DashScopeMultimodalEmbedding` | Native multimodal-embedding API | qwen3-vl-embedding, tongyi-embedding-vision-plus, etc. |

---

## Project Structure

```
ink.icoding.llm
├── agent/                          # Agent layer
│   ├── AgentClient                 # Agent definition
│   ├── AgentClientSession          # Conversation session
│   ├── AgentSessionResult          # Fluent result chain (.then/.error)
│   ├── AgentResultHandler          # Callback interface (agent-level)
│   ├── Plan                        # Execution plan model
│   └── Skill                       # Skill (tool group + guide)
│
├── core/
│   ├── entity/                     # Data models
│   │   ├── Message                 # Conversation message
│   │   ├── MessageAttachment       # File/image attachment
│   │   ├── ModelType               # LLM provider enum
│   │   └── MemoryMultipartFile     # In-memory file
│   │
│   ├── model/                      # LLM abstraction layer
│   │   ├── LLMModel                # LLM interface + factory
│   │   ├── LLMResult               # Fluent result chain
│   │   ├── ResultHandler           # Callback interface (LLM-level)
│   │   └── impl/
│   │       ├── OpenAIChatModel     # OpenAI Chat Completions
│   │       ├── AnthropicModel      # Anthropic Messages
│   │       └── OpenAIResponseModel # OpenAI Responses
│   │
│   └── tool/                       # Tool system
│       ├── Tool                    # Tool interface
│       ├── ToolParam               # Parameter base class
│       ├── ToolDescriptor          # Introspection + JSON Schema generation
│       ├── ToolExecutor            # Tool execution strategy
│       ├── ToolStatus              # PREPARING / CALLING / COMPLETED
│       ├── annotations/
│       │   ├── @ToolInfo           # Tool metadata
│       │   └── @Param              # Parameter metadata
│       └── builtin/                # Built-in tools & skills
│           ├── skill/
│           │   ├── BuiltInSkills   # Factory for all built-in skills
│           │   ├── FileSystemSkill
│           │   ├── CommandExecutionSkill
│           │   └── OrchestrationSkill
│           ├── param/              # Tool parameter classes
│           ├── *Tool.java          # Tool implementations
│           └── CreatePlanTool / CreateSubAgentTool
│
└── MainTest                        # Demo application
```

---

## License

[GNU General Public License v3.0](https://raw.githubusercontent.com/onlyGuo/agent4j/main/LICENSE)

---

<p align="center">
  <sub>Built with Java 17 | Jackson | OkHttp</sub><br>
  <a href="https://github.com/onlyGuo/agent4j">GitHub</a> &bull;
  <a href="README_CN.md">中文文档</a>
</p>
---
此处编辑一下本仓库的 README 证明我（郭胜凯）是本项目的作者，用于认证公众号“一个会聊天的树洞”的个人主体。
