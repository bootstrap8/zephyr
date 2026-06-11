# 聊天输入框文件上传功能设计

## 概述

聊天输入框支持上传文件，文件存入当前工作空间的 `.zephyr-uploads/` 目录，模型通过 tool call 自主读取/解析文件内容。不支持图片多模态，仅支持文本内容提取。

## 整体流程

```
用户点 paperclip → 选文件 → 前端 POST /zephyr-ui/chat/upload
  → 后端校验(≤10MB) → 存到 {workspace}/.zephyr-uploads/
  → 返回文件路径 → 输入框出现 file chip
  → 用户输入消息 → 点发送
  → ChatRequest 带 filePaths → 后端拼入上下文
  → 模型通过 tool call 自主读取/解析文件
```

关键原则：
- **上传即落盘**：选文件时立即上传，非发送时才上传
- **模型主导解析**：后端不预解析文件内容，只告知模型文件路径，模型自主选择 tool
- **不存数据库**：文件存在 workspace 目录下，不和 conversation 关联
- **文件 chip**：类似 MCP/Skill 标签，在 contenteditable 输入框中以内联 chip 展示

## 前端改动

### InputArea.vue

**Paperclip 按钮：**
- 点击触发隐藏的 `<input type="file" multiple">`
- 拿到 FileList 后逐个上传
- 上传中 chip 显示 spinner，失败显示红色（不可发送）
- 成功后 chip 显示正常状态

**File chip：**
- 和 `cmd-tag` 同一风格，用 `cmd-tag--file` 区分
- 显示 `File/文件名`（前缀 + 分隔符 + 文件名）
- 前缀颜色用文件类型色（如 `#6b8cce` 蓝色），区别于 MCP teal 和 Skill amber
- 悬浮显示完整路径
- Backspace 可删除光标前的 chip，点击 × 也可删除
- 删除时不调后端删除接口（文件留在 workspace，不做清理）

**发送：**
- `doSend()` 收集所有 file chip 的 `data-name`（文件路径）
- emit 时携带 filePaths

### ChatView.vue

- `send` 事件处理增加 `filePaths` 参数
- 调用 SSE 接口时传 `filePaths`

### types/chat.ts

```typescript
export interface FileAttachment {
  path: string       // workspace 相对路径
  name: string       // 原始文件名（展示用）
  size: number       // 字节
  status: 'uploading' | 'done' | 'error'
}
```

### 国际化

新增 key：
- `inputArea_uploadTooltip`: "上传文件"
- `inputArea_fileTooLarge`: "文件大小不能超过 10MB"
- `inputArea_uploadFailed`: "上传失败"

## 后端改动

### 上传接口

```
POST /zephyr-ui/chat/upload
```

参数：
- `file`: MultipartFile
- `workspaceId`: String

返回：
```json
{
  "state": "OK",
  "body": {
    "path": ".zephyr-uploads/1718000000_readme.md",
    "name": "readme.md",
    "size": 2048
  }
}
```

校验：
- 文件大小 ≤ 10MB，接口内单独判断 `file.getSize() > 10 * 1024 * 1024`，超限返回 400
- workspace 必须存在

存储：
- 路径：`{workspace.path}/.zephyr-uploads/{timestamp}_{originalFilename}`
- 时间戳用 `System.currentTimeMillis() / 1000`（秒级 Unix timestamp）
- 文件重名不覆盖（时间戳前缀天然去重）

### ChatRequest 加字段

```java
private List<String> filePaths;  // workspace 相对路径，如 .zephyr-uploads/xxx.md
```

### ChatServiceImpl 上下文拼合

如果 `filePaths` 不为空，在用户消息前拼：

```
[用户上传的文件:]
- .zephyr-uploads/readme.md
- .zephyr-uploads/需求文档.xlsx

用户消息: 帮我总结一下这两个文件
```

模型拿到后自主调用 Read 等 tool 读取文件内容。

## 文件清单

| 文件 | 改动 |
|------|------|
| `src/main/resources/static/src/views/chat/InputArea.vue` | paperclip 接文件选择、file chip 渲染/删除、上传逻辑 |
| `src/main/resources/static/src/views/chat/ChatView.vue` | send 事件加 filePaths 参数 |
| `src/main/resources/static/src/types/chat.ts` | 加 `FileAttachment` 类型 |
| `src/main/resources/static/src/i18n/common.ts` | 加上传相关 i18n key |
| `src/main/java/.../chat/ctrl/ChatCtrl.java` | 加 `/upload` 接口 |
| `src/main/java/.../chat/model/ChatRequest.java` | 加 `filePaths` 字段 |
| `src/main/java/.../chat/service/ChatService.java` | 加 `upload` 方法签名 |
| `src/main/java/.../chat/service/impl/ChatServiceImpl.java` | 实现 upload、上下文拼合 |

## 错误处理

| 场景 | 处理 |
|------|------|
| 文件 > 10MB | 后端 400，前端 chip 变红 + toast |
| 上传网络失败 | chip 变红，可删除 |
| workspace 不存在 | 后端 400 |

## 不做

- 图片多模态 — 后续扩展
- 服务端文件清理 — workspace 管理后续加
- 文件类型白名单 — 先不限制，模型自己判断能不能读
- 上传后删除服务端文件 — 简单路径，文件就留在那里
