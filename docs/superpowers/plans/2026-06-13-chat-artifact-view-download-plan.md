# 聊天产物可访问/下载 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 聊天区域 AI 生成的 HTML/文件产物可通过链接在浏览器打开或下载，代码块可下载，对话绑定工作空间后不可修改。

**Architecture:** 后端新增文件服务端点安全读取工作空间文件并区分 inline/attachment；ChatEvent 扩展 artifact 类型通过 SSE 推送产物信息；前端 MessageBubble 增加代码块下载按钮和产物卡片，InputArea 根据对话状态锁定工作空间选择器。

**Tech Stack:** Java 17 / Spring Boot 3.5.4 / Vue 3 + TS / Pinia

---

### Task 1: 后端 — ChatEvent 扩展 artifact 字段

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/model/ChatEvent.java`

- [ ] **Step 1: 新增 artifact 相关字段**

```java
package com.github.hbq969.ai.zephyr.chat.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatEvent {
    private String type;
    private String content;
    private String toolName;
    private Object toolInput;
    private String toolOutput;
    private Object usage;
    private String error;
    // 新增：artifact 事件字段
    private String artifactName;   // 展示名称，如 "index.html"
    private String artifactPath;   // 相对工作空间路径，如 "outputs/index.html"
    private String artifactType;   // MIME 类型，如 "text/html"
    private Long artifactSize;     // 字节数
}
```

- [ ] **Step 2: 验证编译通过**

```bash
mvn compile -pl . -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/model/ChatEvent.java
git commit -m "feat: ChatEvent 扩展 artifact 字段支持产物事件

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: 后端 — 文件服务端点

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/ctrl/ChatCtrl.java`
- Check: `src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/WorkspaceDao.java` (确认方法签名)
- Check: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java` (确认 chat.upload 配置项)

**Context:** `WorkspaceDao.queryById(id)` 返回 `WorkspaceEntity`，包含 `path` 和 `userName` 字段。

- [ ] **Step 1: 在 ChatCtrl 中新增文件服务方法**

在 `ChatCtrl.java` 末尾（类闭合 `}` 之前）添加：

```java
@Operation(summary = "获取工作空间产物文件")
@RequestMapping(path = "/files/{workspaceId}/**", method = RequestMethod.GET)
@ResponseBody
public org.springframework.http.ResponseEntity<?> serveArtifact(
        @PathVariable String workspaceId,
        jakarta.servlet.http.HttpServletRequest request) {
    try {
        // 1. workspace 归属校验
        com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity ws =
                workspaceDao.queryById(workspaceId);
        if (ws == null) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        String currentUser = userName();
        if (!ws.getUserName().equals(currentUser)) {
            return org.springframework.http.ResponseEntity.status(403).build();
        }

        // 2. 提取 filePath（/files/{workspaceId}/** 中 ** 的部分）
        String fullPath = request.getRequestURI();
        String prefix = "/zephyr-ui/chat/files/" + workspaceId + "/";
        if (!fullPath.startsWith(prefix)) {
            return org.springframework.http.ResponseEntity.badRequest().build();
        }
        String filePath = fullPath.substring(prefix.length());

        // 3. 路径穿越防护
        java.nio.file.Path wsDir = java.nio.file.Paths.get(ws.getPath()).toRealPath();
        java.nio.file.Path target = wsDir.resolve(filePath).toRealPath();
        if (!target.startsWith(wsDir)) {
            return org.springframework.http.ResponseEntity.status(403).build();
        }

        // 4. 读取文件
        byte[] bytes = java.nio.file.Files.readAllBytes(target);

        // 5. Content-Type
        String fileName = target.getFileName().toString();
        String lower = fileName.toLowerCase();
        String contentType = switch (true) {
            case lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html; charset=utf-8";
            case lower.endsWith(".css") -> "text/css; charset=utf-8";
            case lower.endsWith(".js") -> "application/javascript; charset=utf-8";
            case lower.endsWith(".json") -> "application/json; charset=utf-8";
            case lower.endsWith(".png") -> "image/png";
            case lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg";
            case lower.endsWith(".gif") -> "image/gif";
            case lower.endsWith(".svg") -> "image/svg+xml";
            case lower.endsWith(".webp") -> "image/webp";
            case lower.endsWith(".pdf") -> "application/pdf";
            default -> "application/octet-stream";
        };

        // 6. Content-Disposition（?download=1 强制下载）
        boolean forceDownload = "1".equals(request.getParameter("download"));
        boolean inline = !forceDownload && (contentType.startsWith("text/")
                || contentType.startsWith("image/") || contentType.equals("application/pdf"));
        String disposition = inline
                ? "inline; filename=\"" + fileName + "\""
                : "attachment; filename=\"" + fileName + "\"";

        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(bytes);
    } catch (java.nio.file.NoSuchFileException e) {
        return org.springframework.http.ResponseEntity.notFound().build();
    } catch (Exception e) {
        log.error("读取产物文件失败", e);
        return org.springframework.http.ResponseEntity.internalServerError().build();
    }
}
```

注入 `WorkspaceDao` 依赖（在现有 `@Resource` 块旁边添加）：

```java
@Resource
private com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao workspaceDao;
```

- [ ] **Step 2: 验证编译通过**

```bash
mvn compile -pl . -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: curl 验证端点**

启动后端后执行：

```bash
# 测试不存在的 workspace
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/chat/files/nonexistent/test.html"
# Expected: 404

# 先用正常对话生成一个 html 文件到工作空间，然后用 workspaceId 和文件路径测试
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/ctrl/ChatCtrl.java
git commit -m "feat: 聊天产物文件服务端点 — 安全读取工作空间文件

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: 后端 — 工具执行后发送 artifact SSE 事件

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java`
- Check: `src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/entity/WorkspaceEntity.java` (确认类名和字段)

**Context:** `dispatchTools()` 方法第 359 行。工具调用结果在 for 循环中处理，`emitter` 已可用。需要在工具执行后检查工具参数中是否包含工作空间内文件路径，如有则发送 artifact 事件。

- [ ] **Step 1: 在 dispatchTools 中新增产物检测**

在 `dispatchTools()` 的 for 循环内，工具执行成功后（第 373 行 `content = sanitizeToolOutput(content);` 之后），新增产物检测逻辑：

```java
// 检测工具调用是否在工作空间目录内生成了文件
try {
    detectArtifacts(tc, emitter, userName, enabledKbIds);
} catch (Exception ignored) {}
```

在 `ChatServiceImpl` 类中新增私有方法（放在 `dispatchTools` 方法之后）：

```java
/**
 * 检测工具调用产生的文件产物，通过 SSE 推送 artifact 事件。
 * 仅处理在工作空间目录内的文件写入。
 */
private void detectArtifacts(LlmResult.ToolCall tc, SseEmitter emitter,
                              String userName, List<String> enabledKbIds) {
    // 只处理写文件类工具
    String toolName = tc.getName();
    Set<String> writeTools = Set.of("Write", "write_to_file", "create_file", "edit_file");
    if (writeTools.contains(toolName)) {
        Map<String, Object> args = tc.getArguments();
        if (args == null) return;
        // 尝试从 arguments 中提取 filePath
        Object filePathObj = args.get("filePath");
        if (filePathObj == null) filePathObj = args.get("file_path");
        if (filePathObj == null) filePathObj = args.get("path");
        if (filePathObj == null) return;
        String filePath = filePathObj.toString();
        if (filePath.isBlank()) return;

        // 检查路径是否在工作空间内
        java.nio.file.Path target = java.nio.file.Paths.get(filePath);
        if (!java.nio.file.Files.isRegularFile(target)) return;

        // 获取当前工作空间（从对话或上下文获取）
        // 如果路径是绝对路径且在某个工作空间目录下，提取相对路径
        try {
            java.nio.file.Path abs = target.toRealPath();
            // 遍历用户的工作空间，看文件是否在其中
            List<com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity> wss =
                    workspaceDao.queryByUserName(userName);
            for (com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity ws : wss) {
                java.nio.file.Path wsDir = java.nio.file.Paths.get(ws.getPath()).toRealPath();
                if (abs.startsWith(wsDir)) {
                    java.nio.file.Path rel = wsDir.relativize(abs);
                    String relPath = rel.toString().replace('\\', '/');
                    String fileName = abs.getFileName().toString();
                    String mimeType = java.nio.file.Files.probeContentType(abs);
                    if (mimeType == null) {
                        mimeType = fileName.toLowerCase().endsWith(".html") ? "text/html"
                                : fileName.toLowerCase().endsWith(".pdf") ? "application/pdf"
                                : "application/octet-stream";
                    }
                    long size = java.nio.file.Files.size(abs);

                    emitter.send(SseEmitter.event().name("message")
                            .data(ChatEvent.builder()
                                    .type("artifact")
                                    .artifactName(fileName)
                                    .artifactPath(relPath)
                                    .artifactType(mimeType)
                                    .artifactSize(size)
                                    .build()));
                    break;
                }
            }
        } catch (Exception ignored) {}
    }
}
```

- [ ] **Step 2: 验证编译通过**

```bash
mvn compile -pl . -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 功能验证**

启动后端，通过 curl 发送消息触发 MCP Write 工具写入工作空间文件：
```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -d '{"message":"写一个 hello world html 文件到 output/test.html","workspaceId":"<ws-id>"}'
```
Expected: SSE 事件流中包含 `type: "artifact"` 的事件。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java
git commit -m "feat: 工具执行后检测工作空间产物并推送 artifact SSE 事件

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: 前端 — 类型定义和 Store 更新

**Files:**
- Modify: `src/main/resources/static/src/types/chat.ts`
- Modify: `src/main/resources/static/src/store/chat.ts`

- [ ] **Step 1: 类型定义新增 Artifact 接口，扩展现有接口**

在 `chat.ts` 类型文件中新增 `Artifact` 接口并扩展现有定义：

```typescript
// 在 ChatEvent 接口中添加 artifact 字段（新增 type 值 + 可选字段）
export interface ChatEvent {
  type: 'token' | 'thinking' | 'tool_call' | 'tool_result' | 'usage' | 'compaction' | 'done' | 'error' | 'artifact' | 'clear'
  content?: string
  toolName?: string
  toolInput?: Record<string, unknown>
  toolOutput?: string
  usage?: { inputTokens: number; outputTokens: number }
  error?: string
  // artifact 事件字段
  artifactName?: string
  artifactPath?: string
  artifactType?: string
  artifactSize?: number
}

// 新增 Artifact 接口
export interface Artifact {
  name: string
  path: string
  contentType: string
  size: number
}

// Message 接口新增 artifacts 可选字段
export interface Message {
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool'
  content: string
  thinking?: string
  toolCalls?: ToolCall[]
  artifacts?: Artifact[]
  timestamp: number
}
```

- [ ] **Step 2: chat store 新增 addArtifact action**

在 `useChatStore` 的 `return { ... }` 对象中添加 `addArtifact` 函数，并确保导出：

```typescript
function addArtifact(artifactId: Artifact) {
  flushTokens()
  const msgs = messages.value
  if (msgs.length === 0) return
  const last = msgs[msgs.length - 1]
  if (last.role !== 'assistant') return
  if (!last.artifacts) last.artifacts = []
  last.artifacts.push(artifactId)
}
```

在 `return { ... }` 中添加 `addArtifact`。

- [ ] **Step 3: 类型检查通过**

```bash
cd src/main/resources/static && npm run type-check
```

Expected: No errors.

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/static/src/types/chat.ts src/main/resources/static/src/store/chat.ts
git commit -m "feat: 前端类型定义和 store 新增 Artifact 支持

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: 前端 — SSE artifact 事件解析

**Files:**
- Modify: `src/main/resources/static/src/views/chat/ChatView.vue` (第 129-166 行 SSE 事件解析区域)

- [ ] **Step 1: 在 SSE 解析中新增 artifact 分支**

在 `ChatView.vue` 的 `for (const line of lines)` 循环内的 `else if` 链中，在 `tool_result` 和 `meta` 之间添加：

```typescript
} else if (event.type === 'artifact') {
  if (event.artifactName && event.artifactPath) {
    chatStore.addArtifact({
      name: event.artifactName,
      path: event.artifactPath,
      contentType: event.artifactType || 'application/octet-stream',
      size: event.artifactSize || 0
    })
  }
}
```

- [ ] **Step 2: restoreConversation 中重建 artifacts**

在 `restoreConversation()` 函数（第 187 行）中，加载历史消息后，为 assistant 消息从 tool 消息中解析产物路径：

在 `msgs.forEach((m: any) => chatStore.addMessage(m))` 之前（第 245 行），添加：

```typescript
// 为 assistant 消息重建 artifacts（从关联的 tool 消息内容中解析路径）
const artifactRe = /(?:output|saved to|写入|created?|生成)[:\s]+[`"']?([^\s`"']+\.(?:html|css|js|png|jpg|jpeg|gif|svg|webp|pdf))[`"']?/gi
const workspaceId = body.workspaceId
for (let i = 1; i < msgs.length; i++) {
  if (msgs[i].role === 'assistant') {
    const prevMsgs: any[] = []
    for (let j = i - 1; j >= 0 && msgs[j].role === 'tool'; j--) {
      prevMsgs.unshift(msgs[j])
    }
    const artifacts: { name: string; path: string; contentType: string; size: number }[] = []
    for (const pm of prevMsgs) {
      const content = pm.content || ''
      let match: RegExpExecArray | null
      artifactRe.lastIndex = 0
      while ((match = artifactRe.exec(content)) !== null) {
        const fileName = match[1].split('/').pop() || match[1]
        const ext = fileName.substring(fileName.lastIndexOf('.'))
        const mimeMap: Record<string, string> = {
          '.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript',
          '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
          '.gif': 'image/gif', '.svg': 'image/svg+xml', '.webp': 'image/webp',
          '.pdf': 'application/pdf'
        }
        artifacts.push({
          name: fileName,
          path: match[1],
          contentType: mimeMap[ext] || 'application/octet-stream',
          size: 0
        })
      }
    }
    if (artifacts.length > 0) {
      msgs[i].artifacts = artifacts
    }
  }
}
```

- [ ] **Step 3: 类型检查通过**

```bash
cd src/main/resources/static && npm run type-check
```

Expected: No errors.

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/static/src/views/chat/ChatView.vue
git commit -m "feat: SSE 解析新增 artifact 事件 + 历史消息产物重建

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: 前端 — 代码块下载按钮

**Files:**
- Modify: `src/main/resources/static/src/views/chat/MessageBubble.vue`

**Context:** `setupCodeBlocks()` 函数在第 66 行，`.code-actions` 模板在第 73-81 行。需要在复制按钮**左侧**增加下载按钮。不改动复制/折叠按钮的 DOM 和事件逻辑。

- [ ] **Step 1: 修改 code-actions 模板，增加下载按钮**

将 `wrapper.innerHTML` 改为在复制按钮前添加下载图标：

```javascript
wrapper.innerHTML = `
  <div class="code-actions">
    <span class="code-icon code-download" title="${langData.msgBubble_download || '下载'}">
      <svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/></svg>
    </span>
    <span class="code-icon code-copy" title="${langData.msgBubble_copy}">
      <svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>
    </span>
    <span class="code-icon code-toggle" title="${langData.msgBubble_collapse}">
      <svg xmlns="http://www.w3.org/2000/svg" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m18 15-6-6-6 6"/></svg>
    </span>
  </div>
`
```

- [ ] **Step 2: 注册下载按钮点击事件**

在 `setupCodeBlocks()` 中，`copyBtn` 和 `toggleBtn` 事件注册之后（第 98 行 toggleBtn.addEventListener 之后），添加：

```javascript
const downloadBtn = wrapper.querySelector('.code-download')!
downloadBtn.addEventListener('click', () => {
  const text = pre.textContent || ''
  const lang = pre.className.replace('language-', '').replace('lang-', '').trim()
  const extMap: Record<string, string> = {
    html: '.html', htm: '.html', css: '.css', js: '.js', ts: '.ts', json: '.json',
    xml: '.xml', yaml: '.yml', yml: '.yml', md: '.md', txt: '.txt',
    py: '.py', java: '.java', go: '.go', rs: '.rs', c: '.c', cpp: '.cpp',
    sh: '.sh', bash: '.sh', sql: '.sql', svg: '.svg'
  }
  const ext = extMap[lang] || (lang ? '.' + lang : '.txt')
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'code' + ext
  a.click()
  URL.revokeObjectURL(url)
})
```

- [ ] **Step 3: 国际化字符串补充**

在 `static/src/i18n/` 的国际化文件中添加 `msgBubble_download` 键：
- `zh-CN`：`"下载"`
- `en-US`：`"Download"`
- `ja-JP`：`"ダウンロード"`

- [ ] **Step 4: 暗黑模式样式** — 无需额外改动，`.code-icon` 已有暗黑样式规则，下载按钮沿用。

- [ ] **Step 5: 前端构建验证**

```bash
cd src/main/resources/static && npm run type-check
```

Expected: No errors.

- [ ] **Step 6: 提交**

```bash
git add src/main/resources/static/src/views/chat/MessageBubble.vue src/main/resources/static/src/i18n/
git commit -m "feat: 代码块增加下载按钮 — 将代码块内容保存为本地文件

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: 前端 — 产物链接卡片渲染

**Files:**
- Modify: `src/main/resources/static/src/views/chat/MessageBubble.vue`

**Context:** AI 气泡在模板第 124-138 行。需要在 markdown 内容下方添加产物卡片区域。

- [ ] **Step 1: 添加产物卡片模板**

在 `MessageBubble.vue` 模板中，`markdown-body` div（第 137 行）之后、ai-bubble 闭合之前，添加：

```html
<div v-if="message.artifacts?.length" class="artifacts-row">
  <div v-for="a in message.artifacts" :key="a.path" class="artifact-card">
    <Icon :icon="previewable(a.contentType) ? 'lucide:eye' : 'lucide:download'" class="artifact-icon" />
    <span class="artifact-name">{{ a.name }}</span>
    <span class="artifact-size">{{ formatFileSize(a.size) }}</span>
    <a v-if="previewable(a.contentType)" :href="artifactUrl(a.path)" target="_blank" class="artifact-btn artifact-preview">
      <Icon icon="lucide:external-link" /> 打开预览
    </a>
    <a :href="artifactUrl(a.path) + '?download=1'" class="artifact-btn artifact-download">
      <Icon icon="lucide:download" /> 下载
    </a>
  </div>
</div>
```

- [ ] **Step 2: 添加 script 中的辅助函数**

在 `<script setup>` 中添加（放在 `const workspaceStore = useWorkspaceStore()` 附近）：

```typescript
import { useWorkspaceStore } from '@/store/workspace'

const workspaceStore = useWorkspaceStore()

function artifactUrl(filePath: string) {
  const wsId = workspaceStore.currentId
  if (!wsId) return '#'
  // 路径分段编码，保留 / 分隔符
  const encodedPath = filePath.split('/').map(encodeURIComponent).join('/')
  return `./chat/files/${wsId}/${encodedPath}`
}

function previewable(contentType: string) {
  return contentType.startsWith('text/html')
    || contentType.startsWith('image/')
    || contentType === 'application/pdf'
}

function formatFileSize(bytes: number) {
  if (bytes === 0) return ''
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}
```

- [ ] **Step 3: 添加产物卡片样式**

在 `<style scoped>` 块末尾（第 193 行 `</style>` 之前）添加：

```css
.artifacts-row { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px; }
.artifact-card { display: flex; align-items: center; gap: 6px; padding: 6px 10px; background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-light); border-radius: 6px; font-size: 12px; }
.artifact-icon { color: var(--el-color-primary); font-size: 14px; flex-shrink: 0; }
.artifact-name { font-weight: 500; color: var(--el-text-color-primary); max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.artifact-size { color: var(--el-text-color-placeholder); font-size: 11px; flex-shrink: 0; }
.artifact-btn { display: inline-flex; align-items: center; gap: 3px; color: var(--el-color-primary); text-decoration: none; font-size: 11px; padding: 2px 6px; border-radius: 4px; transition: background 0.15s; white-space: nowrap; }
.artifact-btn:hover { background: var(--el-color-primary-light-9); }
```

- [ ] **Step 4: 暗黑模式适配** — 在独立的 `<style>` 块（非 scoped，已在第 195 行之后）中添加：

```css
html.dark .artifact-card { background: var(--el-fill-color); border-color: var(--el-border-color); }
```

- [ ] **Step 5: 前端构建验证**

```bash
cd src/main/resources/static && npm run type-check
```

Expected: No errors.

- [ ] **Step 6: 提交**

```bash
git add src/main/resources/static/src/views/chat/MessageBubble.vue
git commit -m "feat: AI 消息气泡产物链接卡片 — 点击预览/下载工作空间文件

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: 前端 — 对话绑定工作空间锁定

**Files:**
- Modify: `src/main/resources/static/src/views/chat/InputArea.vue`

**Context:** 第 517-540 行为工作空间选择器区域。`convStore.currentId` 存在表示已有对话。

- [ ] **Step 1: 新增锁定状态计算属性**

在 InputArea.vue `<script setup>` 中（第 99 行 workspaceStore 之后）添加：

```typescript
// 对话绑定工作空间后不可修改
const workspaceLocked = computed(() => !!convStore.currentId)
const workspaceLockedTooltip = computed(() =>
  workspaceLocked.value ? langData.inputArea_workspaceLocked : ''
)
```

- [ ] **Step 2: 修改工作空间选择器模板**

将第 517-540 行的工作空间选择器改为支持只读模式：

```html
<!-- 工作空间选择 -->
<div class="tool-pick" :class="{ 'ws-locked': workspaceLocked }"
     @click.stop="!workspaceLocked && toggleWorkspaceList()"
     :title="workspaceLockedTooltip">
  <template v-if="workspaceStore.current">
    <Icon icon="lucide:folder" class="ws-icon" />
    <span>{{ workspaceStore.current.name }}</span>
  </template>
  <template v-else>
    <Icon icon="lucide:folder" class="ws-icon dim" />
    <span class="ws-placeholder">{{ workspaceLocked ? langData.inputArea_noWorkspace : '' }}</span>
  </template>
  <Icon v-if="!workspaceLocked" icon="lucide:chevron-down" class="pick-arrow" />
  <Icon v-else icon="lucide:lock" class="ws-lock-icon" />
  <div v-if="showWorkspaceList && !workspaceLocked" class="pick-dropdown ws-dropdown" @click.stop>
    <div v-for="ws in workspaceStore.workspaces" :key="ws.id"
         class="pick-option ws-option"
         :class="{ current: workspaceStore.currentId === ws.id }"
         @click="selectWorkspace(ws.id)">
      <span class="ws-name">{{ ws.name }}</span>
      <span class="ws-path">{{ ws.path }}</span>
    </div>
    <div v-if="workspaceStore.workspaces.length > 0" class="pick-divider"></div>
    <div class="pick-option" @click="showWorkspaceList = false; showNewWorkspace = true">
      <Icon icon="lucide:plus" />{{ langData.inputArea_newWorkspace }}
    </div>
  </div>
</div>
```

- [ ] **Step 3: 添加锁定样式**

在 InputArea.vue `<style scoped>` 中添加：

```css
.ws-locked { opacity: 0.75; cursor: default; }
.ws-placeholder { color: var(--el-text-color-placeholder); font-size: 12px; }
.ws-lock-icon { font-size: 12px; color: var(--el-text-color-placeholder); margin-left: 4px; flex-shrink: 0; }
```

- [ ] **Step 4: 国际化字符串补充**

在 `src/main/resources/static/src/i18n/` 中添加 `inputArea_workspaceLocked` 和 `inputArea_noWorkspace`：
- `zh-CN`：`"对话已绑定工作空间，新建对话可切换"` / `"无工作空间"`
- `en-US`：`"Workspace is locked to this conversation. Start a new chat to switch."` / `"No workspace"`
- `ja-JP`：`"会話にワークスペースが固定されています。新しい会話で切り替え可能です。"` / `"ワークスペースなし"`

- [ ] **Step 5: 前端构建验证**

```bash
cd src/main/resources/static && npm run type-check
```

Expected: No errors.

- [ ] **Step 6: 提交**

```bash
git add src/main/resources/static/src/views/chat/InputArea.vue src/main/resources/static/src/i18n/
git commit -m "feat: 对话绑定工作空间后锁定选择器，仅新建对话可切换

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 9: 端到端验证

**Files:** 无，验证步骤。

**前置条件：** 后端启动 + 前端构建产物在 target 目录。

- [ ] **Step 1: 前端构建 + 复制到 target**

```bash
cd src/main/resources/static && npm run build && mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

Expected: BUILD SUCCESS，无类型错误。

- [ ] **Step 2: curl 验证文件服务端点**

```bash
# 1. 获取工作空间列表，记下 workspaceId
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/workspace/list"

# 2. 在工作空间目录下手动创建一个测试 html
# echo '<h1>test</h1>' > /path/to/workspace/outputs/test.html

# 3. 通过端点访问
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/chat/files/{workspaceId}/outputs/test.html"
# Expected: <h1>test</h1>

# 4. 路径穿越测试
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/chat/files/{workspaceId}/../../../etc/passwd"
# Expected: 403
```

- [ ] **Step 3: Playwright 验证前端交互**

使用 Playwright MCP 打开 `http://localhost:30733/zephyr/zephyr-ui/index.html`，登录后：
1. 创建新对话，确认工作空间选择器可操作
2. 绑定工作空间后，确认选择器变为只读（锁图标）
3. 新建对话，确认选择器恢复可操作
4. 发送包含代码块的 AI 请求，确认代码块出现下载按钮
5. 点击下载按钮，确认文件下载成功
6. 触发 Write 工具产生 HTML 文件，确认产物卡片显示
7. 点击"打开预览"，确认新标签页打开 HTML 文件
8. 点击"下载"，确认文件下载

- [ ] **Step 4: 提交 final commit（如有调整）**

```bash
git status
# 如有修改则提交
```
