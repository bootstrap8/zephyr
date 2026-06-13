# 聊天产物可访问设计

## 目标

聊天区域中 AI 生成的 HTML 页面、文件等产物，提供：
- HTML/图片/PDF 等可直接在浏览器打开的链接
- 任意文件提供下载
- 代码块增加下载按钮
- 来源：MCP 工具输出 + AI 直接输出的 markdown 代码块

## 产品范围

- 产物绑定到**工作空间**，不随对话删除
- HTML/图片/PDF 支持内联预览，其他类型仅下载
- 改动必须向后兼容，不破坏现有聊天逻辑

---

## 1. 后端：文件服务端点

在 `ChatCtrl` 中新增：

```
GET /zephyr-ui/chat/files/{workspaceId}/**{filePath}
```

### 安全校验

1. `workspaceId` 归属校验：`workspaceDao.queryById(workspaceId)` 确认 workspace 存在且属于当前用户
2. 路径穿越防护：规范化后 `Files.isSameFile()` 检查文件确实在工作空间目录下
3. 禁止列出目录，仅支持单文件读取

### 响应头策略

| 扩展名 | Content-Type | Content-Disposition |
|--------|-------------|---------------------|
| `.html` | `text/html; charset=utf-8` | `inline` |
| `.png/.jpg/.jpeg/.gif/.svg/.webp` | 对应 MIME | `inline` |
| `.pdf` | `application/pdf` | `inline` |
| `?download=1` 强制参数 | — | `attachment` |
| 其他 | `application/octet-stream` | `attachment` |

### 不新增 Controller 类

直接在 `ChatCtrl` 中添加一个 `@RequestMapping` 方法，复用已有依赖注入，不创建新文件。

---

## 2. 后端：SSE artifact 事件

### ChatEvent 扩展

```java
// 现有字段不变，新增：
private String artifactName;   // 展示名称，如 "index.html"
private String artifactPath;   // 相对路径，如 "outputs/index.html"
private String artifactType;   // MIME 类型，如 "text/html"
private Long artifactSize;     // 字节数
```

当前 `ChatEvent.type` 支持的值：`token | thinking | tool_call | tool_result | usage | compaction | done | error`
**新增**：`artifact`

### artifact 事件发送时机

在 `ChatServiceImpl.dispatchTools()` 中，工具执行完成后：

1. 检查工具输出是否为工作空间文件路径
2. 如 `Write` 工具的 `filePath` 参数指向工作空间内路径，验证文件存在后发送 `artifact` 事件
3. 对于明确写入工作空间目录的 MCP 工具调用，提取路径信息

### 向后兼容

- `ChatEvent` 新增字段有默认值 `null`，旧客户端忽略不识别的事件类型
- 不影响现有 SSE 流逻辑

---

## 3. 前端：代码块下载按钮

在 `MessageBubble.vue` 的 `.code-actions` 区域，复制按钮**左侧**增加下载按钮：

### 改动位置

`setupCodeBlocks()` 函数中 `.code-actions` 的 HTML 模板，在复制按钮前添加：

```html
<span class="code-icon code-download" title="下载">
  <svg><!-- lucide:download --></svg>
</span>
```

### 行为

- 点击提取 `<pre>` 的 `textContent`
- 根据代码块语言推断后缀：`html` → `.html`，`css` → `.css`，无语言 → `.txt`
- 通过 `URL.createObjectURL` + `<a download>` 触发浏览器下载
- 暗黑模式沿用现有 `.code-icon` 样式，不额外写规则

### 不改动

- 复制按钮、折叠按钮的 DOM 结构和事件监听器
- `.code-icon` 的样式定义

---

## 4. 前端：产物链接卡片

收到 SSE `artifact` 事件后，在 AI 消息中追加可点击的产物链接。

### 数据模型

```typescript
interface Artifact {
  name: string        // index.html
  path: string        // outputs/index.html
  contentType: string // text/html
  size: number        // 字节
}
```

`Message` 类型新增可选字段：`artifacts?: Artifact[]`

### 渲染

在 `MessageBubble.vue` 的 `.ai-bubble` 中，内容下方新增产物区域：

```html
<div v-if="message.artifacts?.length" class="artifacts-row">
  <div v-for="a in message.artifacts" :key="a.path" class="artifact-card">
    <Icon icon="lucide:file" />
    <span class="artifact-name">{{ a.name }}</span>
    <span class="artifact-size">{{ formatSize(a.size) }}</span>
    <a :href="fileUrl(a.path)" target="_blank" class="artifact-btn">在浏览器中打开</a>
    <a :href="fileUrl(a.path) + '?download=1'" class="artifact-btn">下载</a>
  </div>
</div>
```

- 文件 URL 拼接：`/chat/files/{workspaceId}/{a.path}`
- 不可预览类型隐藏"在浏览器中打开"按钮
- 样式使用 `var(--el-*)` 变量，暗黑适配独立非 scoped `<style>` 块

### ChatView.vue 改动

- SSE 解析中新增 `artifact` 分支，调用 `chatStore.addArtifact()` 追加到当前 assistant 消息
- `restoreConversation()` 加载历史消息时，从 tool 消息内容中解析路径并重建 artifacts

---

## 5. 前端：对话绑定工作空间后不可修改

消息内容可能引用工作空间中的文件，因此对话绑定工作空间后应锁定。

### 规则

| 状态 | 工作空间选择器行为 |
|------|-------------------|
| 新建对话（无 conversationId） | 可切换、可新建工作空间 |
| 已有对话（有 conversationId，有 workspaceId） | 只读，显示当前工作空间名称 |
| 已有对话（有 conversationId，无 workspaceId） | 只读，显示"无工作空间" |

### InputArea.vue 改动

- 工作空间选择器根据 `convStore.currentId` 判断是否只读
- 只读时：禁用下拉和新增按钮，仅显示当前工作空间名称
- tooltip 提示："对话已绑定工作空间，新建对话可切换"
- 样式与现有工作空间选择器一致，仅交互状态不同

---

## 6. 约束

- **不创建新的 controller 文件**，端点加在现有 `ChatCtrl`
- **不创建新的 service 文件**，逻辑在 `ChatServiceImpl` 中
- **不改变现有 `ChatEvent.type` 枚举值**，仅新增一个 type
- **不改变现有代码块操作按钮的事件逻辑**
- **文件服务端点不做目录列表**，仅单文件读取
- 产物输出目录：工作空间目录的子目录 `outputs/`
- **对话绑定工作空间后不可修改**，仅新建对话时可切换

---

## 7. 涉及文件

| 文件 | 改动 |
|------|------|
| `ChatCtrl.java` | 新增 `GET /chat/files/{workspaceId}/**` 端点 |
| `ChatEvent.java` | 新增 `artifactName`/`artifactPath`/`artifactType`/`artifactSize` 字段 |
| `ChatServiceImpl.java` | 工具分发后检查产物并发送 artifact SSE 事件 |
| `MessageBubble.vue` | 代码块增加下载按钮 + 产物卡片渲染 |
| `InputArea.vue` | 工作空间选择器根据对话状态切换只读/可编辑模式 |
| `ChatView.vue` | SSE 事件解析新增 `artifact` 分支 |
| `chat.ts` (types) | `Message` 新增 `artifacts` 字段，`ChatEvent` 新增可选字段 |
| `chat.ts` (store) | 新增 `addArtifact` action |
