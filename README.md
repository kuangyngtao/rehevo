# Rehevo

> **Practice. Reflect. Evolve.**

Rehevo 是一个面向求职者的 AI 求职训练平台，提供简历分析、文字与语音模拟面试、面试复盘、知识库问答和面试日程管理。它的目标不是替代面试官，而是把练习、反馈与改进沉淀为可重复的训练闭环。

## 核心能力

- **简历训练**：上传 PDF、DOCX、DOC 或 TXT 简历，异步解析并生成结构化分析报告，支持导出。
- **模拟面试**：基于岗位方向、难度、JD 与简历上下文生成文字面试，并支持多轮追问与评估。
- **语音面试**：浏览器麦克风采集、实时 ASR 字幕、LLM 追问和 TTS 语音播报；用户确认后手动提交回答，避免思考停顿被误判。
- **知识库问答**：文档上传、解析、分块、异步向量化，以及带查询改写和阈值控制的 RAG 问答。
- **模型配置**：支持 DashScope、DeepSeek、Kimi、GLM、LM Studio 等 Provider；聊天模型、向量模型和语音模型独立配置。
- **工程保障**：PostgreSQL + pgvector、Redis Stream 异步任务、RustFS/S3 文件存储、统一限流、Actuator 指标与 Docker Compose。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 4.1、Spring AI 2.0、Gradle |
| 前端 | React 18、TypeScript、Vite、Tailwind CSS |
| 数据与异步 | PostgreSQL + pgvector、Redis、Redisson、Redis Stream |
| 文件与文档 | RustFS/S3、Apache Tika、iText |
| AI 与语音 | DashScope/OpenAI 兼容 API、Qwen ASR/TTS、WebSocket |

## 快速启动（Windows）

前置条件：JDK 21、Docker Desktop、Node.js 与 pnpm。DashScope API Key 已配置为 Windows 用户环境变量 `ALI-API-KEY` 或 `AI_BAILIAN_API_KEY`。

```powershell
scripts\start-local.cmd
```

脚本会：

1. 启动或复用 RustFS、PostgreSQL 和 Redis；
2. 创建 `rehevo` 数据库（首次运行）；
3. 创建 `~/.rehevo` 运行时配置目录；如存在旧配置，会复制模型配置作为备份迁移；
4. 启动后端 `http://localhost:8080` 和前端 `http://localhost:5173`。

常用地址：

- 前端：`http://localhost:5173`
- 后端健康检查：`http://localhost:8080/actuator/health`
- OpenAPI：`http://localhost:8080/swagger-ui.html`
- RustFS 控制台：`http://localhost:9001`

首次使用新的品牌默认值时，会启用新的 `rehevo` 数据库和 `rehevo` 对象存储 bucket；旧数据不会被删除。

## 手动启动

```powershell
docker compose -f docker-compose.dev.yml up -d
.\gradlew.bat :app:bootRun
```

```powershell
Set-Location frontend
pnpm run dev
```

## 环境变量

复制 [`.env.example`](.env.example) 为 `.env` 后按需填写。最低要求是 DashScope API Key：

```dotenv
AI_BAILIAN_API_KEY=your_dashscope_api_key
APP_AI_CONFIG_ENCRYPTION_KEY=replace_with_a_stable_random_secret
```

也兼容已有的 Windows 用户环境变量 `ALI-API-KEY`。不要提交 `.env`、API Key 或运行时生成的 Provider 配置。

默认模型配置：

- 聊天：`qwen3.7-flash`
- 向量：`qwen3.7-text-embedding`（1024 维）
- ASR：`qwen3-asr-flash-realtime`
- TTS：`qwen3-tts-flash-realtime`

## 项目结构

```text
rehevo/
├── app/                    # Spring Boot 后端
│   ├── src/main/java/interview/guide/
│   │   ├── common/         # AI、异步、限流、异常与通用配置
│   │   ├── infrastructure/ # 文件、导出、Redis、映射
│   │   └── modules/        # 简历、面试、语音、知识库、模型配置等模块
│   └── src/main/resources/ # 配置、Prompt、Skill 与脚本
├── frontend/               # React 前端
├── scripts/                # 本地启动脚本
├── docker-compose.dev.yml  # PostgreSQL、Redis、RustFS 开发依赖
└── docker-compose.yml      # 完整容器化部署
```

> Java 包名暂时保留为 `interview.guide`，以避免品牌调整阶段引入大规模包迁移风险；它不影响 Rehevo 的产品名、构建产物或对外运行身份。

## 验证命令

```powershell
.\gradlew.bat :app:test --no-daemon
```

```powershell
Set-Location frontend
pnpm run build
```
