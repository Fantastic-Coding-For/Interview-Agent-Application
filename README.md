# Interview-Agent-Application

AI 面试辅助平台：上传简历即可获得 AI 评分与分析。支持文字模拟面试、实时语音面试(实时语音识别 + 语音合成)、基于向量检索的知识库问答与 AI 出题，以及面试日程管理。支持多家大模型供应商一键切换。

## 核心功能

| 功能模块 | 说明 |
|---|---|
| 📄 简历智能分析 | 上传 PDF / DOCX / TXT 简历,AI 解析并给出评分与改进建议,支持导出与重新分析 |
| 💬 AI 文字模拟面试 | 基于简历生成面试问题,SSE 流式对话、智能追问,结束后生成面试报告 |
| 🎙️ 实时语音面试 | WebSocket 实时语音对话,Qwen3 实时语音识别(ASR)+ 语音合成(TTS),包含自我介绍 / 技术 / 项目 / HR 四个阶段,支持边合成边播放 |
| 📚 知识库 RAG | 上传资料构建向量知识库(pgvector),支持 AI 检索问答、基于知识库自动出题、AI 面试 |
| 📅 面试日程管理 | 粘贴 JD 或简历自动解析生成面试日程,日历视图管理 |

## 技术栈

|  | 技术 |
|---|---|
| 后端 | Java 25 · Spring Boot 4.1 · Spring AI 2.0 · Gradle 9.6 · WebSocket · Flyway · Swagger |
| 前端 | React 18 · TypeScript · Vite 5 · Tailwind CSS 4 · pnpm |
| 数据库 | PostgreSQL 16 + pgvector(关系存储 + 向量检索) |
| 缓存/异步 | Redis 7(Redis Streams 异步任务、Redisson 分布式锁/限流) |
| 对象存储 | RustFS(S3 兼容,开发环境) |
| AI 能力 | 文字对话(SSE 流式)· 实时语音(Qwen3 ASR/TTS)· 向量嵌入(text-embedding-v3) |

## 快速开始

### 前置条件

- **JDK 25**:无需手动安装,Gradle 通过 foojay 插件自动下载
- **Node.js 20+** 与 **pnpm 10+**
- **Docker + Docker Compose**(用于启动中间件)

### 1. 配置环境变量

```bash
# Windows:  copy .env.example .env
cp .env.example .env
```

编辑 `.env`,至少填入两项:

| 变量 | 说明 |
|---|---|
| `AI_BAILIAN_API_KEY` | 阿里云百炼 API Key(驱动 LLM、实时 ASR、TTS),[申请入口](https://bailian.console.aliyun.com/) |
| `APP_AI_CONFIG_ENCRYPTION_KEY` | AES-256 主密钥,用于加密存储各 Provider 的 API Key。**一旦设定不可更换**,生成方法见 `.env.example` 内注释 |

其他 Provider(DeepSeek / Kimi / GLM)按需填入对应 `PROVIDER_*_API_KEY` 即可,留空自动禁用。

### 2. 启动中间件(PostgreSQL + Redis + RustFS)

```bash
cd docker
docker compose -f docker-compose.dev.yml --env-file ../.env up -d
```

> 首次启动后访问 <http://localhost:9001>(RustFS 控制台),用 `rustfsadmin / rustfsadmin` 登录,手动创建名为 `interview-guide` 的 bucket(应用会尽力自动创建,但建议手动确保)。
>
> 数据库表结构由后端启动时 Flyway 自动创建,无需手动建表。

### 3. 启动后端

回到项目根目录:

```bash
# Windows:  gradlew.bat :app:bootRun
./gradlew :app:bootRun
```

`bootRun` 会自动读取根目录的 `.env` 并注入环境变量。启动成功后:

- API 文档(Swagger UI):<http://localhost:8080/swagger-ui.html>
- 健康检查 / 指标:<http://localhost:8080/actuator/health>

### 4. 启动前端

```bash
cd Interview-Agent-Application-Frontend
pnpm install
pnpm dev
```

访问 <http://localhost:5173>(开发服务器已代理 `/api` → `http://localhost:8080`,如需改指向可用环境变量 `VITE_API_PROXY_TARGET`)。

## 端口一览

| 服务 | 端口 |
|---|---|
| 后端 API | 8080 |
| 前端开发服务器(Vite) | 5173 |
| PostgreSQL | 5432 |
| Redis | 6379 |
| RustFS S3 API | 9000 |
| RustFS 控制台 | 9001 |

## 安全提示

- `APP_AI_CONFIG_ENCRYPTION_KEY` 是加密 Provider API Key 的主密钥,设定后永久保持不变,否则已加密的 Key 将无法解密
