# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 开发规范

项目遵循 `~/.claude/rules/wxaq-fullstack.md` 中的全套前端+后端开发规范，包括 Controller 注解、Mapper XML 方言、DDL 幂等性等。此处只补充 zephyr 项目特有信息。

## 启动与调试

### 后端

```bash
# 用 Java 17（系统默认是 Java 8）
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home

# 构建
mvn clean package -DskipTests
# 复制资源
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
# 启动（me 环境）
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- 端口：`30733`，context-path：`/zephyr`
- 数据库：H2 file（`~/.h2/zephyr/db`），表在启动时自动创建
- 启动后 Swagger：`http://localhost:30733/zephyr/doc.html`
- 验证码绕过：请求带 `X-SM-Test: 1` 头（me 环境已启用）

### 前端

```bash
cd src/main/resources/static
npm run dev          # 前端调试（Vite HMR，端口 3000，代理 /dev → localhost:30733）
npm run build        # 前端构建（类型检查 + 打包，输出到 zephyr-ui/）
npm run type-check   # 仅类型检查
```

`vite.config.ts` 中 `build.outDir = 'zephyr-ui'`。axios baseURL 由 `.env.*` 的 `VITE_API_URL` 提供，前端 URL 不包含 outDir 前缀。

### 前端设计

配色和组件风格必须使用 `src/main/resources/static/DESIGN.md` 中的设计系统——warm canvas（`#faf9f5`）+ coral primary（`#cc785c`）+ dark code surfaces，不自行发明颜色。

### 测试验证

测试账号密码：`admin:1`

```bash
# 通用 curl 模板
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/{path}"

# 带 JSON body 的 POST
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/{path}" \
  -d '{"key":"value"}'
```

### 端到端测试

端到端测试需要 **后端启动 + 前端构建产物在 target 目录**：

```bash
# 1. 启动后端（参考上方"后端"一节，确保服务运行在 30733）

# 2. 构建前端并复制到 target
cd src/main/resources/static
npm run build
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/

# 3. curl 验证接口
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/mcp/server/list"

# 4. 浏览器打开
open http://localhost:30733/zephyr/zephyr-ui/index.html
```

## Git 操作限制

- **禁止自主 push**：`git push` 必须经人工确认后才能执行
- **禁止自主 tag**：`git tag` 创建或推送标签必须经人工确认后才能执行
- **禁止自主操作远程**：任何影响远程仓库的操作（push、tag push、force push）都需要先征求确认

## 架构

### 包结构

```
com.github.hbq969.ai.zephyr
├── config/     # 模型配置（CRUD + AES 加密存储）
├── mcp/        # MCP 服务器+工具管理（CRUD + 连接/发现）
├── chat/       # LLM 对话（SSE 流式）
└── service/impl/InitialServiceImpl.java  # 表创建注册
```

每个功能模块按 Controller → Service → DAO → Entity → Mapper XML 五层组织。

### 关键约定

- Controller 基路径：`/zephyr-ui/{module}`（如 `/zephyr-ui/mcp`）
- 获取当前用户：使用 `UserContext.get().getUserName()`，禁止直接从 session 取
- AES 加解密：key 和 iv 使用 `application.yml` 中 `encrypt.restful.aes.key` 和 `encrypt.restful.aes.iv`，通过 `AESUtil.encrypt(plain, key, iv, StandardCharsets.UTF_8)` 加密
- 表创建：Mapper XML 三方言 DDL + `InitialServiceImpl.tableCreate0()` 注册，不在 `${module}-*.sql` 里写 `CREATE TABLE`
- 新建表只需要 Mapper XML + InitialServiceImpl 两个位置

### 已实现功能

| 模块 | Controller 路径 | 状态 |
|------|----------------|------|
| 模型配置 | `/zephyr-ui/model-config` | 已完成 |
| MCP 管理 | `/zephyr-ui/mcp` | 已完成 |
| LLM 对话 | `/zephyr-ui/chat` | 待实现 |
| 会话持久化 | — | 待实现 |
