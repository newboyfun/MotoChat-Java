# MotoChat

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk\&logoColor=white)



![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F?logo=springboot\&logoColor=white)

![MyBatis-Plus](https://img.shields.io/badge/MyBatis--Plus-3.5.17-orange)



![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql\&logoColor=white)



![Frontend](https://img.shields.io/badge/前端-原生%20JS%20单页应用-F7DF1E?logo=javascript\&logoColor=black)

**本地优先的 AI 角色聊天平台**：给自己或几个朋友搭一个能长期聊下去的角色——  
有稳定的人设、记得住聊过什么、会主动找你说话，还能定时提醒、发语音、看图片。  
数据全部落在你自己的机器上。

---

## 它能做什么

**角色与人设**

- 系统角色 + 用户自建角色，每个角色一套独立设定（Markdown 文件，直接改文件就生效）
- 角色可以叠加"人设覆盖"：同一角色对不同用户呈现不同性格，支持提交与审批流程
- 每个角色有独立头像（自动居中裁剪 + 缩放），也有内置头像兜底
- 管理员可维护"角色模板库"，用户一键把模板应用到自己的角色上

**对话**

- 同步对话 + **SSE 流式逐字输出**，长回复不卡顿、不超时
- **WebSocket 双向通道**（`/ws/chat`），同一连接的消息串行处理，配合定时推送
- 输出自动清洗：`<think>` 推理块、`[happy]` 类表情标签、连续空行，跨 chunk 切割也能挡住
- 内置指令：`/help` `/diary` `/state` `/list` `/mem` `/clear` `/gen_core_mem`
- 多会话管理，历史消息分页加载

**记忆**

- **双层记忆**：短期上下文（最近 N 轮）+ 核心记忆（由模型压缩出的长期摘要）
- 记忆按「角色 + 用户」隔离，A 用户和 B 用户各自独立
- 支持手动触发核心记忆更新、一键清空

**主动交互**

- **定时提醒**：到点让角色主动找你，提醒内容由模型生成，失败自动延后重试
- **主动消息**：空闲一段时间后角色主动发起对话，可设静默时段（如 23:00–07:00 不打扰）

**多用户与权限**

- 管理员 / 普通用户两级，会话级数据隔离（普通用户看不到别人的会话、消息、记忆）
- 角色白名单：可限制某个用户只能访问指定角色
- 登录支持 Bearer Token 与 HttpOnly Cookie 双通道，失败次数限流
- 密码 PBKDF2（310000 轮）哈希存储，改密后自动踢掉其他设备会话

**多模态与扩展**

- **语音合成**（Fish Audio 协议）：把回复读出来
- **图片识别**：上传图片让角色描述内容（校验真实文件头，防伪装扩展名）
- **AI 生成人设**：选几个性格特征，让模型帮你写一个角色设定

**管理端**

- 用户增删改查、查看任意用户的会话与记忆
- Token 用量统计（按用户 / 汇总）、系统日志在线查看
- 在线改 LLM / 视觉 / TTS 配置并热生效，API Key 回显时自动掩码

---

## 技术栈

| 层    | 选型                              | 说明                               |
| ---- | ------------------------------- | -------------------------------- |
| JDK  | 21                              | **开启虚拟线程**，阻塞式模型调用不占平台线程         |
| 框架   | Spring Boot 3.5.16 / Spring MVC |                                  |
| 持久层  | MyBatis-Plus 3.5.17             | 条件构造器 + 分页插件                     |
| 数据库  | MySQL 8.0                       | 6 张表                             |
| 实时   | SSE + 原生 WebSocket              | SSE 用手写帧，配合 24 字符 / 50ms 合并下发    |
| 认证   | 内存 token（Caffeine）+ Cookie      | 自定义 Filter + 拦截器，不走 Security 默认链 |
| 模型接入 | JDK 原生 `HttpClient`             | 直连任意 OpenAI 兼容接口，未引入 SDK         |
| 前端   | 原生 JavaScript 单页应用              | 登录页 / 用户端 / 管理端，**无构建链**，改完刷新即生效 |
| 接口文档 | springdoc-openapi               | `/swagger-ui.html`               |

---

## 快速开始

```bat
init-db.bat     :: 首次执行：建库建表
start.bat       :: 启动：自动拉起 MySQL、检测端口、启动应用
```

打开 <http://127.0.0.1:8081>，账号 `admin` / `admin123`（登录后请先改密码）。  
接口文档：<http://127.0.0.1:8081/swagger-ui.html>

> 默认端口 **8081**。要换端口，同步改 `application.yml` 的 `server.port` 与 `start.bat` 里的  
> `set PORT=`。端口被占用时脚本只在占用者是 `java.exe`（上次没关的 MotoChat）时自动结束它，  
> 占用者是别的程序会停下来提示，不会误杀。

**接上模型才能真正聊天**：在「系统设置」填入 `base_url` / `api_key` / `model`  
（任何 OpenAI 兼容服务都行，本地 Ollama、vLLM 也可以），或直接改 `config/config.json` 的  
`llm` 节。未配置时聊天会返回"AI 服务不可用"，其余功能不受影响。

---

## 架构

```
浏览器（三个原生 JS 单页应用）
   │  HTTP / SSE / WebSocket
   ▼
Spring MVC Controller  ←── AuthFilter（解析 token）+ AuthInterceptor（@RequireLogin/@RequireAdmin）
   │
   ├── Service 层   Chat / Memory / Persona / Reminder / AutoSend / Admin / Vision / Tts …
   │                  └── 流式经 ChunkCoalescer 合并后下发；输出经 OutputFilter 清洗
   ├── LlmGateway  JDK HttpClient 直连模型服务（支持按用户覆盖 api_config）
   └── Mapper       MyBatis-Plus → MySQL
```

**数据表**

| 表               | 作用                            |
| --------------- | ----------------------------- |
| `users`         | 账号、角色、人设、API 配置、人设覆盖（JSON 字段） |
| `conversations` | 会话（id / 标题 / 当前角色）            |
| `messages`      | 消息（按会话 + id 联合索引，分页取历史）       |
| `notifications` | 主动消息与提醒通知（未读标记）               |
| `reminders`     | 定时提醒（按目标时间索引）                 |
| `token_usage`   | 每次调用的 Token 用量                |

记忆不入库，按 `userdata/avatars/<角色>/memory/<用户>/{short,core}_memory.json` 落盘，  
写入用临时文件 + 原子替换，崩溃不会留半截文件。

---

## 接口一览

| 模块                                       | 接口数 |
| ---------------------------------------- | --- |
| 认证（登录 / 登出 / me / 改密 / 验密 / 查看 key）      | 6   |
| 聊天（同步 / SSE 流式 / 指令 / TTS / 图片识别 / 生成人设） | 6   |
| 会话（列表 / 创建 / 历史分页 / 提醒 / 通知）             | 7   |
| 系统角色（CRUD / 切换 / 头像）                     | 11  |
| 用户角色（自建 / 重命名 / 覆盖 / 审批 / 模板库）           | 12  |
| 管理端（用户管理 / 数据查看 / 角色库）                   | 16  |
| 配置（读写 / 统计 / 日志 / 用量 / 用户 API 配置）        | 9   |
| WebSocket `/ws/chat`                     | 1   |
| 页面路由（`/` `/login` `/admin` `/user`）      | 5   |

---

## 工程结构

```
src/main/java/com/motochat/
├── common/        ApiException、FileHelper（路径校验 / 图片嗅探 / 密钥掩码）、JsonHelper
├── config/        Security / WebMvc / MyBatisPlus / WebSocket / 全局异常 / 启动初始化
├── controller/    Auth / Chat / Conversation / Persona / UserPersona / Admin / Config / Page
├── dto/           请求体 record
├── entity/        6 张表实体
├── llm/           LlmGateway、OutputFilter（think / 表情标签）、EmotionTags
├── mapper/        6 个 MyBatis-Plus Mapper
├── security/      AuthFilter、SessionStore、AuthInterceptor、PBKDF2 编码器、限流
├── service/       Chat / Memory / Persona / UserPersona / Admin / Reminder / AutoSend
│                  / Vision / Tts / Conversation / Config / Log / PersonaState
├── stream/        ChunkCoalescer（24 字符 / 50ms 合并，千帧压到几十帧）
└── ws/            ChatWebSocketHandler、WsSessionRegistry

src/main/resources/static/   前端单页应用 + 8 个提示词模板（日记 / 状态 / 信件 / 购物 …）
tools/                       测试工具：ws_test.py、mock_openai.py、端到端冒烟脚本
```
