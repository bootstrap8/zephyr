# 聊天输入框文件上传功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 聊天输入框支持上传文件到工作空间，模型通过 tool call 自主读取/解析

**Architecture:** 前端 paperclip 按钮接 `<input type="file">`，选中文件后 POST 到后端 `/zephyr-ui/chat/upload`，后端校验大小(≤10MB)后存入 `{workspace.path}/.zephyr-uploads/`，返回路径。输入框展示 file chip，发送时携带 filePaths，后端拼入上下文，模型通过 Read 等 tool 解析

**Tech Stack:** Vue3 + TS (frontend), Spring Boot + MyBatis (backend), contenteditable div (chip UI)

---

### Task 1: ChatRequest.java — 增加 filePaths 字段

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/model/ChatRequest.java:1-10`

- [ ] **Step 1: 添加 filePaths 字段**

```java
package com.github.hbq969.ai.zephyr.chat.model;

import lombok.Data;
import java.util.List;

@Data
public class ChatRequest {
    private String conversationId;
    private String workspaceId;
    private String message;
    private List<String> filePaths;
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/model/ChatRequest.java
git commit -m "feat: ChatRequest 增加 filePaths 字段"
```

---

### Task 2: ChatService.java — 增加 upload 方法签名

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ChatService.java:1-11`

- [ ] **Step 1: 添加 upload 方法签名**

```java
package com.github.hbq969.ai.zephyr.chat.service;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

public interface ChatService {
    SseEmitter send(String userName, String conversationId, String workspaceId, String message, java.util.List<String> filePaths);
    void cancel(String userName);
    Map<String, Object> contextUsage(String userName, String conversationId);
    Map<String, Object> upload(MultipartFile file, String workspaceId);
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl . -q`
Expected: 编译错误（ChatServiceImpl 未实现新方法、ChatCtrl 调用 send 签名不匹配）— 这是预期的，后续任务修复

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/ChatService.java
git commit -m "feat: ChatService 增加 upload 方法，send 增加 filePaths 参数"
```

---

### Task 3: ChatServiceImpl.java — 实现 upload 方法和上下文拼合

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java:1-416`

- [ ] **Step 1: 注入 WorkspaceDao，添加 upload 方法实现**

在类顶部添加 `@Resource` 注入（在现有 `@Resource` 块中追加）：

```java
@Resource
private com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao workspaceDao;
```

在 `cancel` 方法后添加 `upload` 方法：

```java
private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

@Override
public Map<String, Object> upload(MultipartFile file, String workspaceId) {
    if (file.getSize() > MAX_FILE_SIZE) {
        throw new IllegalArgumentException("文件大小不能超过 10MB");
    }
    if (workspaceId == null || workspaceId.isBlank()) {
        throw new IllegalArgumentException("workspaceId 不能为空");
    }
    com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity ws =
            workspaceDao.queryById(workspaceId);
    if (ws == null) {
        throw new IllegalArgumentException("工作空间不存在");
    }
    String originalName = file.getOriginalFilename();
    if (originalName == null || originalName.isBlank()) {
        originalName = "untitled";
    }
    long ts = System.currentTimeMillis() / 1000;
    String filename = ts + "_" + originalName;
    Path uploadsDir = Paths.get(ws.getPath(), ".zephyr-uploads");
    try {
        Files.createDirectories(uploadsDir);
        Path dest = uploadsDir.resolve(filename);
        file.transferTo(dest.toFile());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", ".zephyr-uploads/" + filename);
        result.put("name", originalName);
        result.put("size", file.getSize());
        return result;
    } catch (IOException e) {
        throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
    }
}
```

- [ ] **Step 2: 修改 send 方法签名和上下文拼合**

将 `send` 方法签名改为：

```java
@Override
public SseEmitter send(String userName, String conversationId, String workspaceId,
                       String originalMessage, List<String> filePaths) {
```

在 `// 3. 组装上下文` 之前（约第 106 行），拼接文件路径到用户消息前：

修改 `messages.add(Map.of("role", "user", "content", message));` 为：

```java
// 3. 组装上下文
ContextBuilder.Context ctx = contextBuilder.build(userName, cid);
List<Map<String, Object>> messages = ctx.getMessages();

// 如果有上传文件，拼入用户消息
String userContent = message;
if (filePaths != null && !filePaths.isEmpty()) {
    StringBuilder sb = new StringBuilder("[用户上传的文件:]\n");
    for (String p : filePaths) {
        sb.append("- ").append(p).append("\n");
    }
    sb.append("\n用户消息: ").append(message);
    userContent = sb.toString();
}
messages.add(Map.of("role", "user", "content", userContent));
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl . -q`
Expected: 编译错误（ChatCtrl 调用 send 签名不匹配）

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java
git commit -m "feat: 实现文件上传和上下文拼合"
```

---

### Task 4: ChatCtrl.java — 增加上传接口，更新 send 调用

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/ctrl/ChatCtrl.java:1-81`

- [ ] **Step 1: 添加 upload 接口，更新 sendMessage 调用**

添加 import：

```java
import org.springframework.web.multipart.MultipartFile;
```

在 `sendMessage` 方法中更新对 `chatService.send` 的调用：

```java
@Operation(summary = "发送消息（SSE 流式）")
@RequestMapping(path = "/send", method = RequestMethod.POST)
@ResponseBody
@SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "chat_sendMessage", apiDesc = "聊天接口_发送消息（SSE流式）")
public SseEmitter sendMessage(@RequestBody ChatRequest body) {
    return chatService.send(
            userName(),
            body.getConversationId(),
            body.getWorkspaceId(),
            body.getMessage(),
            body.getFilePaths()
    );
}
```

在 `whoami` 方法后添加 upload 接口：

```java
@Operation(summary = "上传聊天附件")
@RequestMapping(path = "/upload", method = RequestMethod.POST)
@ResponseBody
@SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "chat_upload", apiDesc = "聊天接口_上传附件")
public ReturnMessage<?> upload(@RequestParam("file") MultipartFile file,
                               @RequestParam("workspaceId") String workspaceId) {
    try {
        return ReturnMessage.success(chatService.upload(file, workspaceId));
    } catch (IllegalArgumentException e) {
        return ReturnMessage.fail(e.getMessage());
    } catch (Exception e) {
        log.error("文件上传失败", e);
        return ReturnMessage.fail("上传失败: " + e.getMessage());
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -pl . -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/ctrl/ChatCtrl.java
git commit -m "feat: ChatCtrl 增加文件上传接口"
```

---

### Task 5: types/chat.ts — 增加 FileAttachment 类型

**Files:**
- Modify: `src/main/resources/static/src/types/chat.ts:1-125`

- [ ] **Step 1: 添加 FileAttachment 类型**

在文件末尾追加：

```typescript
// === File Attachment ===
export interface FileAttachment {
  path: string       // workspace 相对路径
  name: string       // 原始文件名（展示用）
  size: number       // 字节
  status: 'uploading' | 'done' | 'error'
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/static/src/types/chat.ts
git commit -m "feat: 增加 FileAttachment 类型"
```

---

### Task 6: locale.ts — 增加上传相关 i18n key

**Files:**
- Modify: `src/main/resources/static/src/i18n/locale.ts`

- [ ] **Step 1: 在三个语言的 input area 区域添加新 key**

`zh-CN` 块，在 `inputArea_thinking` 后添加：

```typescript
"inputArea_fileTooLarge": "文件大小不能超过 10MB",
"inputArea_uploadFailed": "上传失败",
```

`en-US` 块，在 `inputArea_thinking` 后添加：

```typescript
"inputArea_fileTooLarge": "File size cannot exceed 10MB",
"inputArea_uploadFailed": "Upload failed",
```

`ja-JP` 块，在 `inputArea_thinking` 后添加：

```typescript
"inputArea_fileTooLarge": "ファイルサイズは10MBを超えることはできません",
"inputArea_uploadFailed": "アップロード失敗",
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/static/src/i18n/locale.ts
git commit -m "feat: 增加文件上传 i18n key"
```

---

### Task 7: InputArea.vue — 文件上传 UI 逻辑

**Files:**
- Modify: `src/main/resources/static/src/views/chat/InputArea.vue`

- [ ] **Step 1: 修改 send emit 签名和添加文件相关响应式数据**

在 `<script lang="ts" setup>` 块中，修改 emit 定义：

```typescript
const emit = defineEmits<{ send: [text: string, filePaths?: string[]]; stop: [] }>()
```

在现有响应式数据（约第 26 行后）添加：

```typescript
const fileList = ref<{ path: string; name: string; size: number; status: 'uploading' | 'done' | 'error' }[]>([])
const fileInputRef = ref<HTMLInputElement>()
```

- [ ] **Step 2: 修改 doSend，收集 filePaths**

将 `emit('send', msg)` 改为：

```typescript
const doneFiles = fileList.value.filter(f => f.status === 'done')
const filePaths = doneFiles.length > 0 ? doneFiles.map(f => f.path) : undefined
emit('send', msg, filePaths)
```

发送后清空 fileList：

在 `el.innerHTML = ''` 之后添加：

```typescript
fileList.value = []
```

- [ ] **Step 3: 添加文件选择和上传函数**

在 `closeAll` 函数前添加：

```typescript
function triggerFileInput() {
  fileInputRef.value?.click()
}

async function onFilesSelected(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files || files.length === 0) return

  for (let i = 0; i < files.length; i++) {
    const f = files[i]
    const item = { path: '', name: f.name, size: f.size, status: 'uploading' as const }
    fileList.value.push(item)
    const idx = fileList.value.indexOf(item)

    const formData = new FormData()
    formData.append('file', f)
    const wsId = workspaceStore.currentId
    if (!wsId) {
      item.status = 'error'
      continue
    }
    formData.append('workspaceId', wsId)

    try {
      const res = await axios({ url: '/chat/upload', method: 'post', data: formData, headers: { 'Content-Type': 'multipart/form-data' } })
      if (res.data.state === 'OK') {
        item.path = res.data.body.path
        item.status = 'done'
      } else {
        item.status = 'error'
      }
    } catch (err: any) {
      item.status = 'error'
    }
  }
  input.value = ''
}

function removeFile(idx: number) {
  fileList.value.splice(idx, 1)
}
```

- [ ] **Step 4: 修改模板 — paperclip 按钮接文件选择**

将现有的 paperclip 按钮（约第 526 行）：

```html
<button class="action-btn" :title="langData.inputArea_uploadTooltip">
  <Icon icon="lucide:paperclip" />
</button>
```

改为：

```html
<input ref="fileInputRef" type="file" multiple style="display:none" @change="onFilesSelected" />
<button class="action-btn" :title="langData.inputArea_uploadTooltip" @click="triggerFileInput">
  <Icon icon="lucide:paperclip" />
</button>
```

- [ ] **Step 5: 修改模板 — 在 input-container 内展示 file chips**

在 `input-textarea` div 和 `input-toolbar` div 之间插入 file chips 区域：

```html
<div v-if="fileList.length > 0" class="file-chips">
  <span v-for="(f, idx) in fileList" :key="idx"
        class="file-chip"
        :class="{ 'file-chip--error': f.status === 'error', 'file-chip--uploading': f.status === 'uploading' }"
        :title="f.status === 'done' ? f.path : ''">
    <span class="file-chip__icon">
      <Icon v-if="f.status === 'uploading'" icon="lucide:loader-2" class="spin-icon" />
      <Icon v-else-if="f.status === 'error'" icon="lucide:alert-circle" />
      <Icon v-else icon="lucide:file" />
    </span>
    <span class="file-chip__name">{{ f.name }}</span>
    <button class="file-chip__remove" @click="removeFile(idx)" :disabled="f.status === 'uploading'">
      <Icon icon="lucide:x" />
    </button>
  </span>
</div>
```

- [ ] **Step 6: 添加 file chips 样式**

在 `<style scoped>` 块末尾（`</style>` 前）添加：

```css
.file-chips { display: flex; flex-wrap: wrap; gap: 6px; padding: 4px 0 8px 0; }
.file-chip {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 8px; border-radius: 6px;
  background: var(--el-fill-color-light); color: var(--el-text-color-primary);
  font-size: 12px; max-width: 220px;
}
.file-chip--error { background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
.file-chip--uploading { opacity: 0.7; }
.file-chip__icon { font-size: 14px; display: flex; align-items: center; flex-shrink: 0; }
.file-chip__name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-chip__remove {
  display: flex; align-items: center; justify-content: center;
  width: 16px; height: 16px; border-radius: 50%; border: none;
  background: transparent; color: var(--el-text-color-secondary);
  cursor: pointer; font-size: 12px; flex-shrink: 0; padding: 0;
}
.file-chip__remove:hover { background: var(--el-fill-color); color: var(--el-text-color-primary); }
.spin-icon { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
```

- [ ] **Step 7: 提交**

```bash
git add src/main/resources/static/src/views/chat/InputArea.vue
git commit -m "feat: InputArea 实现文件上传 chip UI 和上传逻辑"
```

---

### Task 8: ChatView.vue — onSend 适配 filePaths

**Files:**
- Modify: `src/main/resources/static/src/views/chat/ChatView.vue:88-116`

- [ ] **Step 1: 修改 onSend 签名和 data 传参**

将 `onSend(text: string)` 改为：

```typescript
function onSend(text: string, filePaths?: string[]) {
```

将 `data: { conversationId: convStore.currentId, message: text, workspaceId: workspaceStore.currentId }` 改为：

```typescript
data: { conversationId: convStore.currentId, message: text, workspaceId: workspaceStore.currentId, filePaths: filePaths || [] },
```

- [ ] **Step 2: 类型检查**

Run: `cd src/main/resources/static && npm run type-check`
Expected: PASS（0 errors）

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/static/src/views/chat/ChatView.vue
git commit -m "feat: ChatView 适配 filePaths 参数传递"
```

---

### Task 9: 端到端验证

- [ ] **Step 1: 构建前端**

```bash
cd src/main/resources/static && npm run build
```

- [ ] **Step 2: 复制产物到 target**

```bash
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

- [ ] **Step 3: 复制后端资源**

```bash
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
```

- [ ] **Step 4: 启动后端**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 5: curl 测试上传接口**

```bash
# 创建测试工作空间（如需要）
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/workspace/create" \
  -d '{"name":"test-ws","path":"/tmp/zephyr-test-ws"}'

# 上传文件（获取 workspaceId 从上一步返回）
curl -u admin:1 -H "X-SM-Test: 1" \
  -F "file=@/path/to/test.txt" \
  -F "workspaceId=<ws-id>" \
  "http://localhost:30733/zephyr/zephyr-ui/chat/upload"
```

预期响应：
```json
{"state":"OK","body":{"path":".zephyr-uploads/1718000000_test.txt","name":"test.txt","size":...}}
```

- [ ] **Step 6: curl 测试超限文件**

```bash
# 创建一个大于 10MB 的文件
dd if=/dev/zero of=/tmp/bigfile.bin bs=1m count=11

curl -u admin:1 -H "X-SM-Test: 1" \
  -F "file=@/tmp/bigfile.bin" \
  -F "workspaceId=<ws-id>" \
  "http://localhost:30733/zephyr/zephyr-ui/chat/upload"
```

预期响应：返回 400 错误，提示文件大小不能超过 10MB

- [ ] **Step 7: curl 测试带文件路径的 send**

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -d '{"message":"帮我看看这个文件","workspaceId":"<ws-id>","filePaths":[".zephyr-uploads/1718000000_test.txt"]}'
```

预期：SSE 流式返回，用户消息前拼有 `[用户上传的文件:]` 块

- [ ] **Step 8: 浏览器验证**

```bash
open http://localhost:30733/zephyr/zephyr-ui/index.html
```

- 点击 paperclip 按钮，选择文件
- 确认 file chip 出现在输入框中
- 点击 × 删除 chip
- 输入消息并发送
- 确认模型能读取文件内容

- [ ] **Step 9: 提交（如有修正）**

如有修正，提交验证过程中发现的问题修复。
