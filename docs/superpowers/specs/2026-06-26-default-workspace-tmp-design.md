# 新建对话默认绑定 tmp workspace

## 目标

点击"新建对话"时自动选中 `tmp` workspace（路径 `${zephyr.workspace.browse-root}/tmp`），首次发送消息时自动绑定，无需用户手动选择。

## 当前行为

- 点击"新建对话"仅清空前端状态，不调后端
- 对话在首次发消息时懒创建（`ChatServiceImpl.send()`）
- workspace 需用户手动选择并调用 `update-workspace` 绑定

## 目标行为

- 应用启动 / 刷新时，`GET /workspace/list` 自动保证 `tmp` workspace 记录存在
- 前端点击"新建对话"时，自动预选 `tmp` workspace
- 首次 `onSend` 兜底检查，确保必有 workspace
- 用户仍可手动选择其他 workspace，不受影响

## 实现方案

### 后端：WorkspaceServiceImpl.list() 自动补 tmp

```
list(userName):
    1. 查用户所有 workspace
    2. 检查列表中是否有 path = ${browseRoot}/tmp 的记录
    3. 没有则调 create(name="tmp", path=...) 创建（已有幂等逻辑）
    4. 将 tmp 插入列表头部，返回
```

- `create()` 内部 `queryByPath` 已做幂等
- 路径通过 `Path.of(user.home, cfg.getWorkspace().getBrowseRoot(), "tmp")` 构建
- 不创建实际目录（首次文件操作时由 OS 创建）

### 前端：ChatView.vue 预选 + 兜底

**newChat() 追加：** 从 `workspaceStore.workspaces` 中找 `name === 'tmp'` 的记录，调用 `selectWorkspace(id)` 预设选中态。

**onSend() 兜底：** 如果 `workspaceStore.currentId` 仍为空（例如列表尚未加载完），再补一次查找和选中。

### 边界情况

| 场景 | 处理 |
|------|------|
| 首次启动无 workspace | `list()` 自动补 tmp |
| 用户删除 tmp workspace | 下次 `list()` 重建 |
| 用户在发送前手动选了其他 | `selectWorkspace` 覆盖默认 |
| newChat 时列表未加载完 | `onSend` 兜底检查 |
