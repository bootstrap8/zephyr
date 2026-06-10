# Skill 管理页面搜索过滤 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 SkillSettings.vue 添加纯前端搜索过滤（按来源+名称+描述）和按名称排序功能。

**Architecture:** 在现有 SkillSettings.vue 中新增 computed 属性 `filteredSkills` 对 `store.skills` 做本地过滤+排序，template 新增过滤栏 UI，v-for 从 `store.skills` 改为 `filteredSkills`。不改后端。

**Tech Stack:** Vue 3 + TypeScript

---

### Task 1: 添加过滤状态和 computed 属性

**Files:**
- Modify: `src/main/resources/static/src/views/settings/SkillSettings.vue`

- [ ] **Step 1: 导入 `computed`，新增过滤状态变量和 computed**

在 `<script>` 中，把 `import { ref, onMounted } from 'vue'` 改为 `import { ref, computed, onMounted } from 'vue'`。

在 `onMounted` 行下面（第 32 行之后），新增过滤状态和 computed：

```typescript
// 搜索过滤
const filterKeyword = ref('')
const filterSource = ref('')

const filteredSkills = computed(() => {
  let list = [...store.skills]
  const kw = filterKeyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter(s =>
      (s.skillName || '').toLowerCase().includes(kw) ||
      (s.displayName || '').toLowerCase().includes(kw) ||
      (s.description || '').toLowerCase().includes(kw)
    )
  }
  if (filterSource.value) {
    list = list.filter(s => s.source === filterSource.value)
  }
  list.sort((a, b) => (a.skillName || '').localeCompare(b.skillName || ''))
  return list
})

function clearFilters() {
  filterKeyword.value = ''
  filterSource.value = ''
}
```

- [ ] **Step 2: 运行类型检查确认无 TS 错误**

```bash
cd src/main/resources/static && npm run type-check
```

预期：通过，无类型错误。

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/static/src/views/settings/SkillSettings.vue
git commit -m "feat: SkillSettings 新增 filteredSkills computed 和过滤状态"
```

---

### Task 2: 添加过滤栏 UI

**Files:**
- Modify: `src/main/resources/static/src/views/settings/SkillSettings.vue`

- [ ] **Step 1: 在 template 中添加过滤栏**

在 `subtitle` 的 `<p>` 和第一个 `skill-list` 之间（第 171 行 `<p class="subtitle">` 之后，第 173 行 `v-if="store.skills.length === 0"` 之前），插入过滤栏：

```html
    <!-- 搜索过滤栏 -->
    <div v-if="store.skills.length > 0" class="filter-bar">
      <el-icon class="filter-search-icon"><el-icon-search /></el-icon>
      <el-input
        v-model="filterKeyword"
        class="filter-input"
        :placeholder="langData.skillMgmt_searchPlaceholder || '搜索名称、描述...'"
        clearable
      />
      <el-select v-model="filterSource" class="filter-select" :placeholder="langData.skillMgmt_filterSource || '全部来源'" clearable>
        <el-option label="全部来源" value="" />
        <el-option :label="langData.skillMgmt_source_builtin" value="builtin" />
        <el-option :label="langData.skillMgmt_source_git" value="git" />
        <el-option :label="langData.skillMgmt_source_url" value="url" />
        <el-option :label="langData.skillMgmt_source_local" value="local" />
        <el-option :label="langData.skillMgmt_source_upload" value="upload" />
        <el-option :label="langData.skillMgmt_source_sync" value="sync" />
        <el-option :label="langData.skillMgmt_source_marketplace" value="marketplace" />
      </el-select>
      <span class="filter-count">{{ filteredSkills.length }} / {{ store.skills.length }}</span>
    </div>
```

> 注：`el-input` 带 `clearable` 属性替换独立的清除按钮，更符合 Element Plus 惯例。`el-icon-search` 是 Element Plus 内置图标，无需额外引入。

- [ ] **Step 2: 将 `v-for` 从 `store.skills` 改为 `filteredSkills`**

原代码第 183 行：
```html
<div v-for="s in store.skills" :key="s.id ?? s.skillName" class="skill-card" @click="showSkillDetail(s)">
```

改为：
```html
<div v-for="s in filteredSkills" :key="s.id ?? s.skillName" class="skill-card" @click="showSkillDetail(s)">
```

- [ ] **Step 3: 新增空过滤结果状态**

在 `skill-list` 闭合 `</div>` 之后、空状态之前（第 205 行之后），插入：

```html
    <div v-if="store.skills.length > 0 && filteredSkills.length === 0" class="empty-result">
      <el-icon class="empty-icon"><el-icon-search /></el-icon>
      <h3 class="empty-title">没有匹配的 Skill</h3>
      <p class="empty-desc">尝试调整搜索关键词或来源筛选条件。</p>
    </div>
```

- [ ] **Step 4: 运行类型检查**

```bash
cd src/main/resources/static && npm run type-check
```

预期：通过。

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/static/src/views/settings/SkillSettings.vue
git commit -m "feat: SkillSettings 添加搜索过滤栏 UI"
```

---

### Task 3: 添加过滤栏样式

**Files:**
- Modify: `src/main/resources/static/src/views/settings/SkillSettings.vue`

- [ ] **Step 1: 在 scoped 样式块中添加过滤栏样式**

在 `<style scoped>` 中 `.subtitle` 样式之后（第 447 行之后），插入：

```css
/* Filter bar */
.filter-bar {
  display: flex; gap: 10px; align-items: center;
  margin-bottom: 16px;
  padding: 10px 14px;
  background: var(--el-fill-color-lighter);
  border-radius: 10px;
}
.filter-search-icon { color: var(--el-text-color-placeholder); flex-shrink: 0; font-size: 15px; }
.filter-input { flex: 1; min-width: 0; }
.filter-select { width: 120px; flex-shrink: 0; }
.filter-count { font-size: 12px; color: var(--el-text-color-placeholder); flex-shrink: 0; white-space: nowrap; }

.empty-result { text-align: center; padding: 64px 24px; }
.empty-result .empty-icon { font-size: 40px; color: var(--el-text-color-placeholder); }
.empty-result .empty-title { font-family: Georgia, serif; font-size: 20px; color: var(--el-text-color-primary); margin: 12px 0 6px; font-weight: 400; }
.empty-result .empty-desc { font-size: 13px; color: var(--el-text-color-secondary); }
```

- [ ] **Step 2: 在非 scoped 样式块中添加暗黑适配**

在 `<style>` 非 scoped 块末尾（第 575 行 `</style>` 之前），插入：

```css
html.dark .filter-bar { background: var(--el-fill-color-light); }
```

- [ ] **Step 3: 运行类型检查**

```bash
cd src/main/resources/static && npm run type-check
```

预期：通过。

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/static/src/views/settings/SkillSettings.vue
git commit -m "feat: SkillSettings 添加过滤栏样式和暗黑适配"
```

---

### Task 4: 端到端验证

- [ ] **Step 1: 构建前端**

```bash
cd src/main/resources/static && npm run build
```

预期：构建成功，无错误。

- [ ] **Step 2: 复制构建产物到 target**

```bash
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

- [ ] **Step 3: 用 curl 验证后端接口正常**

```bash
curl -u admin:1 -H "X-SM-Test: 1" "http://localhost:30733/zephyr/zephyr-ui/skill/list"
```

预期：返回 skill 列表 JSON，state 为 OK。

- [ ] **Step 4: 浏览器打开确认页面渲染正常**

```bash
open http://localhost:30733/zephyr/zephyr-ui/index.html
```

手动验证：
- 过滤栏显示在标题和卡片列表之间
- 输入关键词可过滤卡片
- 切换来源下拉可过滤
- 卡片按名称排序
- 清除搜索条件恢复全部显示

- [ ] **Step 5: 提交（如有微调）**

```bash
git add src/main/resources/static/src/views/settings/SkillSettings.vue
git commit -m "chore: SkillSettings 构建验证"
```
