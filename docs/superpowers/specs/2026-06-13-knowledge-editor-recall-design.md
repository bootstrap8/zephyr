# 知识库在线编辑 + 召回测试设计

日期：2026-06-13

## 需求概述

知识库管理目前仅支持文件上传，缺少两项能力：
1. **在线编写文档** — 直接在浏览器中编写 Markdown 文档，无需上传文件
2. **召回测试** — 在知识库内测试检索效果，验证向量+关键词混合检索的召回质量

---

## 一、在线编写文档

### 1.1 实体变更

`KnowledgeDocEntity` 增加两个字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | String(CLOB) | 内联文档的原始 Markdown 文本 |
| `sourceType` | String | `"upload"` 文件上传 / `"inline"` 内联编写 |

### 1.2 新增接口

**创建内联文档**

```
POST /zephyr-ui/knowledge/doc/create-inline
入参: { kbId, title, content(title will be used as fileName + ".md") }
返回: { docId }
```

**更新内联文档**

```
POST /zephyr-ui/knowledge/doc/update-inline
入参: { id, title, content }
```

### 1.3 Service 实现

- `createInlineDoc(kbId, title, content)`:
  1. 生成 docId，fileName = title + ".md"
  2. 将 content 写入 dataDir/kbId/docId_fileName
  3. 插入 DB（sourceType=inline，status=processing）
  4. 异步处理：直接用 content 走 TextSplitter → Embedding → Chroma + KeywordIndex（跳过 TikaParser）

- `updateInlineDoc(docId, title, content)`:
  1. 更新 DB 中 title/content/status
  2. 删除旧的 Chroma chunks + keyword index
  3. 覆盖文件，重新异步处理

### 1.4 前端

- **KnowledgeDocs.vue** — 工具栏"上传文档"旁增加"新建 Markdown"按钮
- **MarkdownEditorDialog.vue**（新建）— `el-dialog` 内嵌编辑器：
  - 标题 `el-input`
  - 左右分栏：`el-input type=textarea` 编辑区 + `marked` 渲染的预览区
  - 保存时调用 `create-inline` 或 `update-inline`
- 文档列表中内联文档来源列显示"在线编写"标签，操作列增加"编辑"按钮
- npm 依赖：`marked`（Markdown 预览渲染），`@types/marked`

---

## 二、召回测试

### 2.1 新增接口

```
POST /zephyr-ui/knowledge/kb/{kbId}/recall-test
入参: { query, topK }  (topK 默认 5，支持 3/5/10/20)
返回: [ { content, sourceFile, score, vecScore, kwScore, rrfScore } ]
```

调用现有 `KnowledgeService.search(query, [kbId], topK)`，在返回结果中补充分项得分。

### 2.2 前端

- **路由** — 新增 `/settings/knowledge/:kbId/recall-test`
- **KnowledgeSettings.vue** — 知识库卡片操作列增加"召回测试"按钮（`lucide:search` 图标），路由跳转到 recall-test 页
- **KnowledgeRecallTest.vue**（新建）— 独立页面：
  - 顶部：返回按钮 + "召回测试 · {知识库名}"
  - 查询区：`el-input` + `el-select`(TopK) + 搜索按钮
  - 结果区：el-table 或卡片列表，每项显示：
    - 排名（#1 高亮）
    - 来源（文件名 + chunk 序号）
    - 融合分数（绿色药丸标签，≥0.7 绿色，≥0.4 黄色，<0.4 灰色）
    - 内容预览（命中词高亮，`<mark>` 标签）
    - 分项得分：向量分 / 关键词分 / RRF 融合分

---

## 三、涉及文件清单

| 层 | 文件 | 操作 | 说明 |
|---|---|---|---|
| Entity | `knowledge/dao/entity/KnowledgeDocEntity.java` | 改 | 加 content、sourceType |
| Mapper XML | `knowledge_embedded.xml` / `mysql.xml` / `postgresql.xml` | 改 | DDL 加列 |
| Mapper XML | `knowledge_common.xml` | 改 | insert/select 加字段 |
| SQL | `zephyr-zh-CN.sql` | 改 | 增量 ALTER TABLE ADD COLUMN |
| Ctrl | `knowledge/ctrl/KnowledgeCtrl.java` | 改 | 3 个新接口 |
| Service | `knowledge/service/KnowledgeService.java` | 改 | 2 个新方法签名 |
| Service | `knowledge/service/impl/KnowledgeServiceImpl.java` | 改 | 3 个新方法实现 + SearchResult 扩展分项得分 |
| Vue | `views/settings/KnowledgeDocs.vue` | 改 | 加新建/编辑按钮 |
| Vue | `views/settings/KnowledgeSettings.vue` | 改 | 加召回测试按钮 |
| Vue | `views/settings/MarkdownEditorDialog.vue` | **新建** | Markdown 编辑弹窗 |
| Vue | `views/settings/KnowledgeRecallTest.vue` | **新建** | 召回测试页面 |
| Router | `router/index.ts` | 改 | 加 recall-test 路由 |
| i18n | `i18n/locale/*.ts` | 改 | 加文案 |

---

## 四、约束与注意

1. 内联文档编辑后需重新分块+向量化，走异步处理，与上传文档保持一致
2. `listDocs` 返回 `KnowledgeDocEntity` 列表（非 VO），sourceType/content 通过 Entity 和 Mapper XML select 直接透传
3. `SearchResult` 目前仅有 `score` 字段，需追加 `vecScore`、`kwScore`、`rrfScore` 三个分项得分字段，供召回测试页面展示。search 方法内部在 RRF 融合和原始向量/关键词检索阶段保留各维度原始分
4. Mapper XML 的 DDL 变更需在 `embedded`/`mysql`/`postgresql` 三方言同步
5. 增量 SQL 按 `application.yml` → `jpaConfig-postgresql.xml` → 使用 PostgreSQL ALTER TABLE 语法
6. 前端新增 npm 依赖：`marked` + `@types/marked`（Markdown 预览渲染），通过 `@fontsource/*` 离线安装，禁止 CDN
