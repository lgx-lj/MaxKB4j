# MaxKB4j 技术架构文档

> 智能知识库问答系统 (Java 版)
> 版本: 1.0.0 | JDK 21 | Spring Boot 3.5.1

---

## 一、项目全景架构图

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              用户入口层                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│  │  管理端前端   │  │  聊天端前端   │  │ OpenAI 兼容   │  │  Webhook     │        │
│  │  Vue.js SPA  │  │  Vue.js SPA  │  │  Chat API    │  │  触发器       │        │
│  │  /admin/**   │  │  /chat/**    │  │  /chat/api/  │  │  /webhook/*  │        │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘        │
└─────────┼─────────────────┼─────────────────┼─────────────────┼────────────────┘
          ▼                 ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              接入层 (Controller)                                 │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐   │
│  │ Auth       │ │ Application│ │ Knowledge  │ │ Document   │ │ Chat       │   │
│  │ Controller │ │ Controller │ │ Controller │ │ Controller │ │ Controller │   │
│  │ 登录/验证码 │ │ 应用管理    │ │ 知识库管理  │ │ 文档管理    │ │ 对话接口    │   │
│  └────────────┘ └────────────┘ └────────────┘ └────────────┘ └────────────┘   │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐   │
│  │ User       │ │ Model      │ │ Tool       │ │ Trigger    │ │ File       │   │
│  │ Controller │ │ Controller │ │ Controller │ │ Controller │ │ Controller │   │
│  │ 用户管理    │ │ 模型管理    │ │ 工具管理    │ │ 触发器管理  │ │ 文件管理    │   │
│  └────────────┘ └────────────┘ └────────────┘ └────────────┘ └────────────┘   │
│                                                                                 │
│  认证拦截: Sa-Token (JWT无状态) ─── @SaCheckPerm 注解鉴权                        │
└─────────────────────────────────────────────────────────────────────────────────┘
          ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              业务服务层 (Service)                                │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │                         maxkb4j-application                             │    │
│  │                                                                         │    │
│  │  Pipeline 模式 (简单对话)                                                │    │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐               │    │
│  │  │ Search   │→ │ Generate │→ │ Reset    │→ │ Chat     │               │    │
│  │  │ Dataset  │  │ Human    │  │ Problem  │  │ Step     │               │    │
│  │  │ Step     │  │ Message  │  │ Step     │  │ (LLM)    │               │    │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘               │    │
│  │  Workflow 模式 (复杂工作流)                                              │    │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐                              │    │
│  │  │ Simple   │  │ Flow     │  │ OpenAI   │                              │    │
│  │  │ Chat     │  │ Chat     │  │ Compat   │                              │    │
│  │  │ Service  │  │ Service  │  │ Service  │                              │    │
│  │  └──────────┘  └──────────┘  └──────────┘                              │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
│                                                                                 │
│  ┌─────────────────────┐  ┌─────────────────────────────────────────────────┐  │
│  │ maxkb4j-knowledge   │  │            maxkb4j-workflow                     │  │
│  │                     │  │                                                 │  │
│  │ DocumentService     │  │  WorkFlowActuator (策略委派)                     │  │
│  │ DocumentParseService│  │    ├─ KnowledgeWorkflowHandler                  │  │
│  │ DocumentSplitService│  │    └─ ChatWorkflowHandler                       │  │
│  │ DocumentWriteService│  │        │                                        │  │
│  │ ParagraphService    │  │  AbsWorkflowHandler (递归调度)                   │  │
│  │ ProblemService      │  │    └─ runChainNodes() → 逐节点执行               │  │
│  │ RetrieveService     │  │        │                                        │  │
│  │ KnowledgeModelService│ │  NodeCenter + NodeHandlerRegistry               │  │
│  └─────────────────────┘  │  38 种节点处理器 (INodeHandler)                   │  │
│                            └─────────────────────────────────────────────────┘  │
│  ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐    │
│  │ maxkb4j-model       │  │ maxkb4j-tool        │  │ maxkb4j-system      │    │
│  │                     │  │                     │  │                     │    │
│  │ 17+ 模型提供商       │  │ Groovy脚本工具       │  │ UserService         │    │
│  │ ModelService缓存    │  │ HTTP请求工具         │  │ FolderService       │    │
│  │ Chat/Embedding/     │  │ MCP协议工具          │  │ SystemSettingService│    │
│  │ Image/STT/TTS/      │  │ Shell Skills        │  │ EmailService        │    │
│  │ Scoring/Reranker    │  │ 搜索引擎集成         │  │ ResourceMapping     │    │
│  └─────────────────────┘  └─────────────────────┘  └─────────────────────┘    │
│  ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐    │
│  │ maxkb4j-oss         │  │ maxkb4j-chat        │  │ maxkb4j-trigger     │    │
│  │                     │  │                     │  │                     │    │
│  │ MongoFileService    │  │ ChatApiService      │  │ TriggerScheduler    │    │
│  │ (GridFS文件存储)     │  │ ChatOpenAiService   │  │ TaskExecutor        │    │
│  └─────────────────────┘  └─────────────────────┘  └─────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────┘
          ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              AI 核心层 (LangChain4j)                             │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │                        maxkb4j-core                                     │    │
│  │                                                                         │    │
│  │  Assistant 接口 (LangChain4j @AiService)                                │    │
│  │  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐              │    │
│  │  │ Assistant │ │ Compress  │ │ Expanding │ │ Intent    │              │    │
│  │  │ 对话助手   │ │ 压缩查询   │ │ 扩展查询   │ │ 意图分类   │              │    │
│  │  └───────────┘ └───────────┘ └───────────┘ └───────────┘              │    │
│  │  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐              │    │
│  │  │ Dual      │ │ NL2Sql    │ │ Parameter │ │ Problem   │              │    │
│  │  │ Keyword   │ │ SQL生成    │ │ 参数提取   │ │ 问题生成   │              │    │
│  │  └───────────┘ └───────────┘ └───────────┘ └───────────┘              │    │
│  │                                                                         │    │
│  │  AppChatMemory (自定义聊天记忆)                                         │    │
│  │  AssistantServices (AiServices 构建器)                                  │    │
│  │  AI 事件监听器 (Started/Completed/Error/ToolExecuted)                    │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────┘
          ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              存储层                                              │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │                  maxkb4j-knowledge 存储架构 (双写)                       │    │
│  │                                                                         │    │
│  │  CompositeStoreImpl.upsert()     CompositeStoreImpl.search()            │    │
│  │         │                                  │                            │    │
│  │    ┌────┴────┐                       ┌────┴────┐                        │    │
│  │    ▼         ▼                       ▼         ▼                        │    │
│  │ ┌────────┐ ┌────────┐          ┌────────┐ ┌────────┐                   │    │
│  │ │PGVector│ │MongoDB │          │PGVector│ │MongoDB │                   │    │
│  │ │向量存储 │ │全文存储 │          │向量检索 │ │全文检索 │                   │    │
│  │ └────────┘ └────────┘          └────────┘ └────────┘                   │    │
│  │                                   │         │                            │    │
│  │                                   └────┬────┘                            │    │
│  │                                        ▼                                 │    │
│  │                                    RRF 融合                               │    │
│  │                               (倒数排名融合排序)                           │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
│                                                                                 │
│  ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐    │
│  │    PostgreSQL       │  │     MongoDB         │  │      Caffeine       │    │
│  │  (主数据库+向量)     │  │  (文件存储+全文索引)  │  │      (本地缓存)      │    │
│  │                     │  │                     │  │                     │    │
│  │  Flyway 迁移管理     │  │  GridFS 大文件存储   │  │  模型缓存 1min/10K   │    │
│  │  PGVector 向量扩展   │  │  embedding 全文索引  │  │  聊天缓存            │    │
│  │  JSONB 类型字段      │  │  @TextIndexed 分词  │  │  系统配置缓存         │    │
│  └─────────────────────┘  └─────────────────────┘  └─────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────┘
          ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              事件驱动层                                          │
│                                                                                 │
│  Spring ApplicationEvent 异步监听                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │                                                                         │    │
│  │  DocumentIndexEvent ──→ @Async DataIndexListener ──→ 向量化入库         │    │
│  │  ParagraphIndexEvent──→ @Async DataIndexListener ──→ 段落向量化          │    │
│  │  GenerateProblemEvent──→ @Async GenerateProblemListener ──→ AI生成问题   │    │
│  │  GraphExtractionEvent──→ @Async GraphExtractionListener ──→ 图谱抽取     │    │
│  │  CreateWebDocsEvent ──→ @Async WebDocsListener ──→ 网页文档创建          │    │
│  │                                                                         │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────┘
          ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              基础设施层                                          │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐       │
│  │ JDK 21    │ │ Undertow  │ │ MyBatis   │ │ Sa-Token  │ │ Flyway    │       │
│  │ 虚拟线程   │ │ Web容器    │ │ Plus      │ │ 认证授权   │ │ DB迁移     │       │
│  └───────────┘ └───────────┘ └───────────┘ └───────────┘ └───────────┘       │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐       │
│  │ Groovy    │ │ EasyExcel │ │ RapidOCR  │ │ jieba     │ │ Knife4j   │       │
│  │ 脚本引擎   │ │ Excel处理  │ │ 图片OCR   │ │ 中文分词   │ │ API文档    │       │
│  └───────────┘ └───────────┘ └───────────┘ └───────────┘ └───────────┘       │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 二、Maven 模块结构

```
MaxKB4j (root pom, 聚合模块)
├── maxkb4j-common           -- 公共基础模块
├── maxkb4j-core             -- 核心抽象/AI接口/事件/工具类
├── maxkb4j-service-api      -- 服务接口层 (聚合子模块)
│   ├── maxkb4j-folder-api        -- 文件夹 API
│   ├── maxkb4j-system-api        -- 系统设置 API
│   ├── maxkb4j-model-api         -- 模型管理 API
│   ├── maxkb4j-oss-api           -- 文件存储 API
│   ├── maxkb4j-tool-api          -- 工具插件 API
│   ├── maxkb4j-user-api          -- 用户/权限 API
│   ├── maxkb4j-knowledge-api     -- 知识库 API
│   ├── maxkb4j-application-api   -- 应用管理 API
│   ├── maxkb4j-workflow-api      -- 工作流 API
│   └── maxkb4j-trigger-api       -- 触发器 API
├── maxkb4j-service          -- 服务实现层 (聚合子模块)
│   ├── maxkb4j-system            -- 系统管理实现
│   ├── maxkb4j-model             -- AI 模型实现
│   ├── maxkb4j-tool              -- 工具插件实现
│   ├── maxkb4j-chat              -- 聊天对话实现
│   ├── maxkb4j-knowledge         -- 知识库实现
│   ├── maxkb4j-oss               -- 文件存储实现
│   ├── maxkb4j-application       -- 应用管理实现
│   ├── maxkb4j-workflow          -- 工作流实现
│   └── maxkb4j-trigger           -- 触发器实现
└── maxkb4j-start            -- 启动引导模块 (Spring Boot Application)
```

---

## 三、技术栈

### 核心框架

| 类别 | 技术 | 版本 |
|------|------|------|
| Web 框架 | Spring Boot | 3.5.1 |
| Web 容器 | Undertow | 随 Spring Boot |
| ORM | MyBatis-Plus | 3.5.9 |
| 数据库 | PostgreSQL | 42.7.7 |
| 向量数据库 | PGVector | 0.1.6 |
| 文件存储 | MongoDB (GridFS) | Spring Data MongoDB |
| 缓存 | Caffeine | 2.9.0 |
| 数据库迁移 | Flyway | 随 Spring Boot |
| 虚拟线程 | JDK 21 Virtual Threads | 内置于 JDK |

### AI / LLM 框架

| 类别 | 技术 | 版本 |
|------|------|------|
| AI 框架 | LangChain4j | 1.14.0 |
| OpenAI | langchain4j-open-ai + openai-java | 1.14.0 / 4.9.0 |
| Azure OpenAI | langchain4j-azure-open-ai | 1.14.0 |
| Ollama | langchain4j-ollama | 1.14.0 |
| 通义千问(百炼) | langchain4j-community-dashscope | beta24 |
| 百度千帆 | langchain4j-community-qianfan | beta24 |
| Google Gemini | langchain4j-google-ai-gemini | 1.14.0 |
| Anthropic | langchain4j-anthropic | 1.14.0 |
| MCP 协议 | langchain4j-mcp | beta24 |

### 搜索引擎集成

| 技术 | 用途 |
|------|------|
| Google Custom Search | 网页搜索工具 |
| SearchAPI | 网页搜索工具 |
| SearXNG | 网页搜索工具 |
| Tavily | 网页搜索工具 |

### 安全认证

| 技术 | 版本 | 用途 |
|------|------|------|
| Sa-Token | 1.39.0 | 认证授权框架 |
| Sa-Token JWT | 1.39.0 | 无状态 JWT 模式 |
| Sa-Token AOP | 1.39.0 | 注解鉴权 |

### 文档处理

| 技术 | 用途 |
|------|------|
| Apache Tika 3.2.3 | 文档解析 (PDF/DOC/PPT/CSV/TXT) |
| Flexmark 0.64.8 | HTML 到 Markdown 转换 |
| RapidOCR 0.0.7 | OCR 图片文字识别 |
| EasyExcel 4.0.3 | Excel 导入导出 |
| jieba-analysis 1.0.2 | 中文分词 (知识库检索) |
| Jsoup 1.9.1 | HTML 解析/网页爬取 |

### 其他工具

| 技术 | 用途 |
|------|------|
| Hutool 5.8.25 | 工具库 (JSON/HTTP) |
| FastJSON 2.0.47 | JSON 序列化 |
| Knife4j 4.5.0 + SpringDoc 2.8.6 | OpenAPI 3 文档 |
| Groovy 4.0.26 | 脚本引擎 (自定义工具执行) |
| easy-captcha 1.6.2 | 验证码生成 |
| Thymeleaf | 邮件模板 |
| Spring Mail | 邮件发送 |
| Reactor (WebFlux) | SSE 流式响应 |

---

## 四、知识库导入链路

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                    知识库文档导入完整链路                                       │
└──────────────────────────────────────────────────────────────────────────────┘

用户上传文件
    │
    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ ① 管理端入口                                                                 │
│                                                                              │
│  方式一: 预览+确认 (DocumentController)                                       │
│  ┌─────────────┐    ┌─────────────┐                                          │
│  │ POST split  │───→│ PUT batch_  │                                          │
│  │ 切片预览     │    │ create      │                                          │
│  │ (不入库)     │    │ 确认写入     │                                          │
│  └─────────────┘    └─────────────┘                                          │
│                                                                              │
│  方式二: 工作流 (KnowledgeController)                                         │
│  ┌──────────────────────────────────────────────────────┐                    │
│  │ POST upload_document                                 │                    │
│  │ └─ 异步执行 KnowledgeWorkflow 工作流                   │                    │
│  └──────────────────────────────────────────────────────┘                    │
└──────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ ② 文档解析层                                                                  │
│                                                                              │
│  DocumentParseService.extractText(fileName, inputStream)                     │
│       │                                                                      │
│       │  按文件扩展名匹配解析器 (DocumentParser.support())                     │
│       ▼                                                                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
│  │PdfParser │  │DocParser │  │ExcelParser│ │PptParser │  │MDParser  │      │
│  │.pdf      │  │.doc/.docx│  │.xls/.xlsx│  │.ppt/.pptx│  │.md       │      │
│  │文字型:    │  │          │  │          │  │          │  │          │      │
│  │PDFText   │  │          │  │          │  │          │  │          │      │
│  │Stripper  │  │          │  │          │  │          │  │          │      │
│  │扫描件:    │  │          │  │          │  │          │  │          │      │
│  │PaddleOCR │  │          │  │          │  │          │  │          │      │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                                  │
│  │HtmlParser│  │TxtParser │  │CsvParser │                                  │
│  │.html/.htm│  │.txt      │  │.csv      │                                  │
│  └──────────┘  └──────────┘  └──────────┘                                  │
└──────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ ③ 文档拆分层                                                                  │
│                                                                              │
│  DocumentSplitService.split(text, patterns, limit, withFilter)               │
│       │                                                                      │
│       ├── patterns == null → 智能模式 (smartSplit)                           │
│       │   ┌────────────────────────────────────────────────┐                 │
│       │   │ 阶段1: 标题切分 (Markdown #~######)             │                 │
│       │   │ 阶段2: 超长切分 (SentenceSplitter + 表格保护)    │                 │
│       │   │ 阶段3: 清洗过滤 (去空格/空行/标题符号)            │                 │
│       │   └────────────────────────────────────────────────┘                 │
│       │                                                                      │
│       └── patterns != null → 自定义模式 (recursive)                          │
│           ┌────────────────────────────────────────────────┐                 │
│           │ 按 patterns 正则从粗到细递归切分                  │                 │
│           │ 超长段落用 SentenceSplitter 进一步切分            │                 │
│           └────────────────────────────────────────────────┘                 │
│                                                                              │
│  输出: List<ParagraphSimple> (title + content)                               │
└──────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ ④ 事务写入层 (同步)                                                          │
│                                                                              │
│  DocumentWriteService.batchCreateDocs()  @Transactional                      │
│       │                                                                      │
│       │  按依赖顺序批量 INSERT                                                │
│       ▼                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐            │
│  │                   PostgreSQL 同一事务                          │            │
│  │                                                              │            │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐             │            │
│  │  │ document   │  │ paragraph  │  │ problem    │             │            │
│  │  │ ───────────│  │ ───────────│  │ ───────────│             │            │
│  │  │ id         │  │ id         │  │ id         │             │            │
│  │  │ name       │←─│ document_id│  │ content    │             │            │
│  │  │ char_length│  │ title      │  │ hit_num    │             │            │
│  │  │ status=nn0 │  │ content    │  │ knowledge_id│            │            │
│  │  │ type       │  │ status=nn0 │  └────────────┘             │            │
│  │  │ meta(JSONB)│  │ position   │                              │            │
│  │  │ knowledge_ │  │ hit_num    │  ┌─────────────────────┐    │            │
│  │  │ id         │  │ is_active  │  │problem_paragraph_   │    │            │
│  │  │ status_    │  │ knowledge_ │  │    mapping          │    │            │
│  │  │ meta(JSONB)│  │ id         │  │ ───────────────────│    │            │
│  │  └────────────┘  └────────────┘  │ paragraph_id       │    │            │
│  │                                   │ problem_id         │    │            │
│  │                                   │ document_id        │    │            │
│  │                                   │ knowledge_id       │    │            │
│  │                                   └─────────────────────┘    │            │
│  └──────────────────────────────────────────────────────────────┘            │
│                                                                              │
│  事务提交后 → publish DocumentIndexEvent                                      │
└──────────────────────────────────────────────────────────────────────────────┘
    │
    │  Spring Event (异步 @Async)
    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ ⑤ 异步向量化层                                                                │
│                                                                              │
│  DataIndexListener.handleEvent(DocumentIndexEvent)                           │
│       │                                                                      │
│       │  1. 获取 EmbeddingModel (从 knowledge 表查 embedding_model_id)        │
│       │  2. 查询 status[1]='0' 的段落                                         │
│       │  3. 批量调用 model.embedAll() 生成向量                                 │
│       │  4. 构建 EmbeddingEntity (段落自身 + 关联问题)                         │
│       │                                                                      │
│       ▼                                                                      │
│  CompositeStoreImpl.upsert()  双写存储                                        │
│       │                                                                      │
│       ├──→ VectorStoreImpl.upsert()                                         │
│       │    │  按 batchSize=100 分批                                            │
│       │    │  支持重试 (3次, 间隔1秒)                                          │
│       │    │  内容分词: jieba                                                  │
│       │    ▼                                                                  │
│       │    ┌──────────────────────────────────────┐                          │
│       │    │  PostgreSQL embedding 表              │                          │
│       │    │  id, source_id, source_type           │                          │
│       │    │  knowledge_id, document_id             │                          │
│       │    │  paragraph_id, content                 │                          │
│       │    │  embedding (pgvector 向量)              │                          │
│       │    │  检索: 1 - (embedding <=> query_vec)   │                          │
│       │    └──────────────────────────────────────┘                          │
│       │                                                                      │
│       └──→ FullTextStoreImpl.upsert()                                       │
│            │  内容分词: jieba                                                  │
│            ▼                                                                  │
│            ┌──────────────────────────────────────┐                          │
│            │  MongoDB embedding 集合               │                          │
│            │  @Document(collection="embedding")    │                          │
│            │  @TextIndexed content                 │                          │
│            │  检索: $text 全文索引                   │                          │
│            └──────────────────────────────────────┘                          │
│                                                                              │
│  状态流转: nn0 → nn1(处理中) → nn2(完成)                                      │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 五、聊天对话链路

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                    聊天对话完整链路                                             │
└──────────────────────────────────────────────────────────────────────────────┘

用户提问
    │
    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ 接入层                                                                        │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐           │
│  │ ApplicationChat  │  │ ChatApiController│  │ ChatOpenAi       │           │
│  │ Controller       │  │ 自有聊天API      │  │ Controller       │           │
│  │ 管理端聊天        │  │                  │  │ OpenAI兼容API    │           │
│  └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘           │
└───────────┼─────────────────────┼─────────────────────┼──────────────────────┘
            ▼                     ▼                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ 对话处理层                                                                    │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────┐            │
│  │ ApplicationChatService                                       │            │
│  │    │                                                         │            │
│  │    ├── 简单模式 ──→ ChatSimpleServiceImpl                     │            │
│  │    │   └─ Pipeline: SearchDataset → GenerateMsg → Chat       │            │
│  │    │                                                         │            │
│  │    └── 工作流模式 ──→ ChatFlowServiceImpl                     │            │
│  │        └─ WorkFlowActuator → ChatWorkflowHandler             │            │
│  │           └─ 从 Start 节点递归执行到 Reply/End 节点            │            │
│  └──────────────────────────────────────────────────────────────┘            │
│                                                                              │
│  流式响应: SSE (Server-Sent Events) via Reactor WebFlux                      │
│  聊天记忆: AppChatMemory (自定义 LangChain4j ChatMemory)                      │
└──────────────────────────────────────────────────────────────────────────────┘
    │
    │  检索知识库 (RAG)
    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ 检索层                                                                        │
│                                                                              │
│  RetrieveService                                                             │
│       │                                                                      │
│       ▼                                                                      │
│  CompositeStoreImpl.search()                                                 │
│       │                                                                      │
│       ├──→ VectorStoreImpl.search()  (PG 向量相似度)                          │
│       │         CompletableFuture.supplyAsync() 并行                          │
│       │                                                                      │
│       └──→ FullTextStoreImpl.search()  (MongoDB 全文关键词)                   │
│                 CompletableFuture.supplyAsync() 并行                          │
│       │                                                                      │
│       ▼                                                                      │
│  结果按 paragraphId 合并 → RRF 融合排序 → topK                               │
│                                                                              │
│  可选: RerankerNodeHandler  (重排序优化)                                      │
│  可选: ExpandingQueryAssistant (查询扩展)                                     │
│  可选: CompressingQueryAssistant (查询压缩)                                   │
│  可选: DualKeywordExtractionAssistant (双关键词提取)                           │
└──────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ LLM 层                                                                        │
│                                                                              │
│  LangChain4j @AiService                                                      │
│       │                                                                      │
│       ├──→ Assistant (基础对话)                                               │
│       │    │  RAG 内容注入: RagContentInjector                                │
│       │    │  聊天记忆: AppChatMemory (滑动窗口)                                │
│       │    │  工具调用: ToolProviderService (Groovy/HTTP/MCP/Skills)           │
│       │    ▼                                                                  │
│       │    17+ 模型提供商 → chatStream / chat                                 │
│       │                                                                      │
│       └──→ SSE 流式返回给前端                                                 │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 六、工作流引擎架构

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                    工作流引擎架构                                              │
└──────────────────────────────────────────────────────────────────────────────┘

前端拖拽画布 (LogicFlow.js)
    │  保存为 JSON
    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ LogicFlow 数据模型                                                            │
│                                                                              │
│  ┌─────────────────────┐    ┌─────────────────────┐                         │
│  │   LfNode (节点)     │    │   LfEdge (边)       │                         │
│  │  id, type, x, y     │    │  sourceNodeId       │                         │
│  │  properties         │    │  targetNodeId       │                         │
│  └─────────────────────┘    │  sourceAnchorId (条件分支) │                   │
│                             └─────────────────────┘                         │
└──────────────────────────────────────────────────────────────────────────────┘
    │
    │  LogicFlow.newInstance(json) 反序列化
    │  NodeBuilder → NodeCenter 节点构建
    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ KnowledgeWorkflow / Workflow                                                  │
│                                                                              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐           │
│  │ WorkflowConfig   │  │ WorkflowContext  │  │ EdgeNavigator    │           │
│  │ 节点+边配置       │  │ 全局上下文       │  │ 边导航器          │           │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘           │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐           │
│  │ WorkflowExecu    │  │ HistoryManager  │  │ OutputManager   │           │
│  │ tionAccessor     │  │ 执行历史管理     │  │ 输出管理器        │           │
│  │ 执行调度控制器    │  │                  │  │                  │           │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘           │
└──────────────────────────────────────────────────────────────────────────────┘
    │
    │  WorkFlowActuator.execute()
    │  └─ 策略路由: KnowledgeWorkflowHandler / ChatWorkflowHandler
    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ AbsWorkflowHandler (递归调度引擎)                                              │
│                                                                              │
│  runChainNodes(List<AbsNode> nodeList)                                       │
│       │                                                                      │
│       │  for each node:                                                      │
│       │    if READY:                                                         │
│       │      handler = NodeCenter.getHandler(node.type)                      │
│       │      result  = handler.execute(workflow, node)   ← INodeHandler      │
│       │      next    = EdgeNavigator.nextNodes(node)                        │
│       │      runChainNodes(next)  ← 递归                                    │
│       │    if SKIP:                                                          │
│       │      直接跳过, 继续下游                                               │
└──────────────────────────────────────────────────────────────────────────────┘
    │
    │  38 种节点处理器
    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ 节点处理器注册表 (NodeCenter + NodeHandlerRegistry)                            │
│                                                                              │
│  ┌─ 数据源节点 ─────────────────────────────────────────────────────┐         │
│  │  DataSourceLocalHandler      本地文件数据源                       │         │
│  │  DataSourceWebHandler        WEB页面数据源                       │         │
│  └─────────────────────────────────────────────────────────────────┘         │
│  ┌─ 文档处理节点 ───────────────────────────────────────────────────┐         │
│  │  DocumentExtractNodeHandler  文档内容提取                         │         │
│  │  DocumentSpiltHandler        文档分段切片                         │         │
│  │  KnowledgeWriteHandler       知识库写入                           │         │
│  └─────────────────────────────────────────────────────────────────┘         │
│  ┌─ AI 节点 ───────────────────────────────────────────────────────┐         │
│  │  LLMNodeHandler              LLM对话                             │         │
│  │  SearchKnowledgeNodeHandler  知识库检索                           │         │
│  │  RerankerNodeHandler         重排序                               │         │
│  │  ImageGenerateNodeHandler    图片生成                             │         │
│  │  SpeechToTextNodeHandler     语音转文字                           │         │
│  │  TextToSpeechNodeHandler     文字转语音                           │         │
│  └─────────────────────────────────────────────────────────────────┘         │
│  ┌─ 流程控制节点 ───────────────────────────────────────────────────┐         │
│  │  ConditionNodeHandler        条件分支 (20+ 比较器)                │         │
│  │  IntentClassifyNodeHandler   意图分类                             │         │
│  │  ParameterExtractionNode     参数提取                             │         │
│  │  LoopNodeHandler             循环                                 │         │
│  │  LoopStart/Break/Continue    循环控制                             │         │
│  │  FormNodeHandler             表单节点                             │         │
│  └─────────────────────────────────────────────────────────────────┘         │
│  ┌─ 工具节点 ───────────────────────────────────────────────────────┐         │
│  │  ToolNodeHandler             自定义工具                           │         │
│  │  HttpNodeHandler             HTTP请求                             │         │
│  │  McpNodeHandler              MCP协议工具                          │         │
│  │  NL2SqlNodeHandler           自然语言转SQL                        │         │
│  └─────────────────────────────────────────────────────────────────┘         │
│  ┌─ 变量节点 ───────────────────────────────────────────────────────┐         │
│  │  VariableAssignNodeHandler   变量赋值                             │         │
│  │  VariableAggregationNode     变量聚合                             │         │
│  │  VariableSplittingNode       变量拆分                             │         │
│  └─────────────────────────────────────────────────────────────────┘         │
│  ┌─ 应用节点 ───────────────────────────────────────────────────────┐         │
│  │  ApplicationNodeHandler      调用其他应用                         │         │
│  │  UserSelectNodeHandler       用户选择                             │         │
│  │  QuestionNodeHandler         提问节点                             │         │
│  │  DirectReplyNodeHandler      直接回复                             │         │
│  └─────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  自动注册: @NodeHandlerType 注解 + BeanPostProcessor                          │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 七、模块依赖关系图

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                    Maven 模块依赖关系                                          │
└──────────────────────────────────────────────────────────────────────────────┘

                              ┌──────────────┐
                              │ maxkb4j-start│  (Spring Boot 启动入口)
                              │ Flyway迁移   │  监听器, 配置类
                              │ 全包扫描      │
                              └──────┬───────┘
                                     │ 聚合所有 service 模块
                    ┌────────────────┼────────────────┐
                    ▼                ▼                ▼
            ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
            │  maxkb4j-    │ │  maxkb4j-    │ │  maxkb4j-    │
            │  application │ │  knowledge   │ │  workflow    │
            │  应用管理实现  │ │  知识库实现   │ │  工作流实现   │
            └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
                   │                │                │
            ┌──────┴───────┐ ┌──────┴───────┐ ┌──────┴───────┐
            │  maxkb4j-    │ │  maxkb4j-    │ │  maxkb4j-    │
            │  application │ │  knowledge   │ │  workflow    │
            │  -api        │ │  -api        │ │  -api        │
            └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
                   │                │                │
                   └────────────────┼────────────────┘
                                    ▼
                           ┌────────────────┐
                           │  maxkb4j-core  │
                           │  AI接口/事件    │
                           │  工具类         │
                           └───────┬────────┘
                                   ▼
                           ┌────────────────┐
                           │ maxkb4j-common │
                           │ Sa-Token认证   │
                           │ MyBatis-Plus   │
                           │ LangChain4j    │
                           │ Hutool/FastJSON│
                           └────────────────┘

  ┌────────────────────────────────────────────────────────────────────────┐
  │  独立依赖模块 (被多个 service 模块引用)                                  │
  │                                                                        │
  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐             │
  │  │maxkb4j-  │  │maxkb4j-  │  │maxkb4j-  │  │maxkb4j-  │             │
  │  │system    │  │model     │  │tool      │  │oss       │             │
  │  │系统管理   │  │模型管理   │  │工具管理   │  │文件存储   │             │
  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘             │
  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐             │
  │  │maxkb4j-  │  │maxkb4j-  │  │maxkb4j-  │  │maxkb4j-  │             │
  │  │chat      │  │trigger   │  │app       │  │prompt    │             │
  │  │聊天API    │  │触发器     │  │用户端API  │  │提示词     │             │
  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘             │
  └────────────────────────────────────────────────────────────────────────┘
```

---

## 八、核心设计模式

```
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│ API/实现分离      │  │ 策略模式          │  │ 观察者模式        │
│                  │  │                  │  │                  │
│ service-api 模块  │  │ WorkFlowActuator │  │ Spring Event     │
│ 定义接口/实体     │  │ 按类型路由到      │  │ DocumentIndex    │
│                  │  │ 不同 Handler     │  │ GenerateProblem  │
│ service 模块      │  │                  │  │ ParagraphIndex   │
│ 提供实现          │  │ AbsModelProvider │  │ GraphExtraction  │
│                  │  │ 17+ 模型提供商    │  │ CreateWebDocs    │
└──────────────────┘  └──────────────────┘  └──────────────────┘

┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│ 模板方法模式      │  │ 注册表模式        │  │ Pipeline 模式     │
│                  │  │                  │  │                  │
│ AbsNodeHandler   │  │ NodeCenter       │  │ AbsStep          │
│ .execute() final │  │ .getHandler()    │  │ SearchDataset    │
│                  │  │                  │  │ GenerateHumanMsg │
│ AbsModelProvider │  │ NodeHandlerAuto  │  │ ResetProblem     │
│ .getModel() 抽象 │  │ Registrar        │  │ ChatStep         │
│                  │  │ @Component 注解   │  │                  │
│ AbsChatStep      │  │ 自动扫描注册      │  │ 管线链式执行       │
└──────────────────┘  └──────────────────┘  └──────────────────┘

┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│ 组合存储模式      │  │ SPI 扩展模式      │  │ 虚拟线程模式      │
│                  │  │                  │  │                  │
│ CompositeStore   │  │ DocumentParser   │  │ JDK 21           │
│ 双写 PG+MongoDB  │  │ 接口 + 8个实现    │  │ Virtual Threads  │
│ 并行检索 + RRF   │  │ 按扩展名匹配      │  │                  │
│                  │  │                  │  │ taskExecutor     │
│ 混合检索融合      │  │ 新增格式只需      │  │ chatTaskExecutor │
│ 向量+全文        │  │ 加一个实现类       │  │ workflowExecutor │
└──────────────────┘  └──────────────────┘  └──────────────────┘
```

---

## 九、数据流全景图

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                    数据流全景                                                  │
└──────────────────────────────────────────────────────────────────────────────┘

              写入流向                                              读取流向
    ┌─────────────────────┐                          ┌─────────────────────┐
    │                     │                          │                     │
    │  用户上传文件         │                          │  用户提问             │
    │  ┌─────────┐        │                          │  ┌─────────┐        │
    │  │ PDF/Word │        │                          │  │ 问题文本 │        │
    │  │ Excel    │        │                          │  └────┬────┘        │
    │  └────┬────┘        │                          │       │             │
    │       │             │                          │       ▼             │
    │       ▼             │                          │  EmbeddingModel     │
    │  解析提取文本         │                          │  生成查询向量        │
    │       │             │                          │       │             │
    │       ▼             │                          │       ├──→ PG 向量   │
    │  切片拆分             │                          │       │   相似度检索  │
    │       │             │                          │       │             │
    │       ├──→ document │                          │       ├──→ MongoDB  │
    │       │    表(PG)   │                          │       │   全文检索   │
    │       │             │                          │       │             │
    │       ├──→ paragraph│                          │       ▼             │
    │       │    表(PG)   │                          │  RRF 融合排序       │
    │       │             │                          │       │             │
    │       ├──→ problem  │                          │       ▼             │
    │       │    表(PG)   │                          │  RAG 内容注入       │
    │       │             │                          │  (RagContentInjector)│
    │       ├──→ embedding│                          │       │             │
    │       │    表(PG)   │                          │       ▼             │
    │       │    (向量)    │                          │  LLM 生成回答       │
    │       │             │                          │  (Assistant)        │
    │       └──→ embedding│                          │       │             │
    │            集合(MDB) │                          │       ▼             │
    │            (全文)    │                          │  SSE 流式返回       │
    │                     │                          │  给用户             │
    └─────────────────────┘                          └─────────────────────┘
```

---

## 十、数据库表结构

| 表名 | 实体 | 用途 |
|------|------|------|
| `knowledge` | KnowledgeEntity | 知识库 |
| `document` | DocumentEntity | 文档 |
| `paragraph` | ParagraphEntity | 段落/切片 |
| `problem` | ProblemEntity | 问题 (QA对) |
| `problem_paragraph_mapping` | ProblemParagraphEntity | 问题↔段落映射 |
| `embedding` (PG) | EmbeddingEntity | 向量数据 |
| `embedding` (MongoDB) | EmbeddingEntity | 全文索引 |
| `application` | ApplicationEntity | AI应用 |
| `application_chat` | ApplicationChatEntity | 聊天会话 |
| `application_chat_record` | ApplicationChatRecordEntity | 聊天记录 |
| `model` | ModelEntity | AI模型配置 |
| `tool` | ToolEntity | 自定义工具 |
| `user` | UserEntity | 用户 |
| `user_resource_permission` | UserResourcePermissionEntity | 用户资源权限 |
| `system_setting` | SystemSettingEntity | 系统设置 |
| `folder` | FolderEntity | 文件夹 |
| `event_trigger` | EventTriggerEntity | 事件触发器 |
| `knowledge_action` | KnowledgeActionEntity | 知识库操作追踪 |
| `application_access_token` | ApplicationAccessTokenEntity | 应用访问令牌 |
| `application_api_key` | ApplicationApiKeyEntity | API Key |
| `application_version` | ApplicationVersionEntity | 应用版本 |

---

## 十一、外部接入点

| 端点前缀                                 | 用途             |
| ------------------------------------ | -------------- |
| `/chat/api/application/profile`      | 应用信息           |
| `/chat/api/open`                     | 聊天开放接口         |
| `/chat/api/chat_message/*`           | 聊天消息           |
| `/chat/api/{appId}/chat/completions` | OpenAI 兼容 API  |
| `/admin/**`                          | 管理端前端 SPA      |
| `/chat/**`                           | 聊天端前端 SPA      |
| `/chat-api-doc`                      | Knife4j API 文档 |

---

## 十二、异步事件处理器

| 事件 | 监听器 | 用途 |
|------|--------|------|
| DocumentIndexEvent | DataIndexListener | 文档向量化入库 |
| ParagraphIndexEvent | DataIndexListener | 段落向量化 |
| GenerateProblemEvent | GenerateProblemListener | AI自动生成相关问题 |
| GraphExtractionEvent | GraphExtractionListener | 知识图谱抽取 |
| CreateWebDocsEvent | WebDocsListener | 网页文档创建 |

---

## 十三、配置类清单

| 配置类 | 职责 |
|--------|------|
| WebConfig | CORS, 虚拟线程异步执行器, Sa-Token 拦截器, SPA 路由转发 |
| MybatisPlusConfig | Mapper 扫描, PostgreSQL 分页插件 |
| SaTokenConfigure | Sa-Token JWT 无状态模式 |
| ThreadPoolConfig | 三个虚拟线程执行器: taskExecutor / chatTaskExecutor / workflowExecutor |
| Knife4jConfiguration | OpenAPI 3 文档配置 |
| FlywayRepairConfig | Flyway 迁移修复 |
| ThymeleafConfig | Thymeleaf 模板配置 |
| SchedulerConfig | 定时任务调度器配置 |

---

> 文档生成时间: 2026-06-02
