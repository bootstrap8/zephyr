# 记忆管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现记忆管理手动 CRUD，文件存储于 `~/.zephyr/memory/{user_name}/`，前端完整对接。

**Architecture:** 4 个后端 Java 文件（Ctrl/Service/impl/VO）+ 3 个前端文件修改（MemorySettings.vue/store/types）。不新建数据库表，纯文件系统读写，YAML frontmatter 解析 Markdown 文件。

**Tech Stack:** Spring Boot 3.5.4, SnakeYAML, Vue 3 + TypeScript, Element Plus

**Spec:** `docs/superpowers/specs/2026-06-09-zephyr-memory-management.md`

---

## 文件结构

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `src/main/java/.../memory/model/MemoryVO.java` | DTO，5 个字段 |
| 新建 | `src/main/java/.../memory/service/MemoryService.java` | 接口，5 个方法 |
| 新建 | `src/main/java/.../memory/service/impl/MemoryServiceImpl.java` | 文件读写 + YAML 解析 |
| 新建 | `src/main/java/.../memory/ctrl/MemoryCtrl.java` | REST 接口，4 个端点 |
| 修改 | `src/main/resources/static/src/types/chat.ts` | 新增 MemoryItem 类型 |
| 修改 | `src/main/resources/static/src/store/settings.ts` | 新增记忆 CRUD 方法 |
| 修改 | `src/main/resources/static/src/views/settings/MemorySettings.vue` | 完整页面重写 |

路由已存在 `/settings/memory`，无需修改。

---

### Task 1: MemoryVO

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/memory/model/MemoryVO.java`

- [ ] **Step 1: 创建 MemoryVO**

```java
package com.github.hbq969.ai.zephyr.memory.model;

import lombok.Data;

@Data
public class MemoryVO {
    private String name;
    private String type;
    private String description;
    private String content;
    private long createdAt;
    private long updatedAt;
}
```

- [ ] **Step 2: 验证编译**

```bash
cd /Users/hbq/Codes/me/github/zephyr
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn compile -pl . -am 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/memory/model/MemoryVO.java
git commit -m "feat: 新增 MemoryVO 数据类"
```

---

### Task 2: MemoryService 接口

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/memory/service/MemoryService.java`

- [ ] **Step 1: 创建 MemoryService 接口**

```java
package com.github.hbq969.ai.zephyr.memory.service;

import com.github.hbq969.ai.zephyr.memory.model.MemoryVO;
import java.util.List;
import java.util.Map;

public interface MemoryService {
    List<MemoryVO> list(String type, String userName);

    MemoryVO detail(String name, String userName);

    void create(Map<String, String> body, String userName);

    void update(Map<String, String> body, String userName);

    void delete(String namesStr, String userName);
}
```

- [ ] **Step 2: 验证编译**

```bash
cd /Users/hbq/Codes/me/github/zephyr
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn compile -pl . -am 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/memory/service/MemoryService.java
git commit -m "feat: 新增 MemoryService 接口定义"
```

---

### Task 3: MemoryServiceImpl

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/memory/service/impl/MemoryServiceImpl.java`

- [ ] **Step 1: 创建 MemoryServiceImpl**

```java
package com.github.hbq969.ai.zephyr.memory.service.impl;

import com.github.hbq969.ai.zephyr.memory.model.MemoryVO;
import com.github.hbq969.ai.zephyr.memory.service.MemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MemoryServiceImpl implements MemoryService {

    private static final String MEMORY_HOME = System.getProperty("user.home") + "/.zephyr/memory";

    private Path userDir(String userName) {
        Path dir = Paths.get(MEMORY_HOME, userName);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建记忆目录: " + dir, e);
        }
        return dir;
    }

    @Override
    public List<MemoryVO> list(String type, String userName) {
        Path dir = userDir(userName);
        List<MemoryVO> result = new ArrayList<>();
        File[] files = dir.toFile().listFiles(f -> f.getName().endsWith(".md") && !f.getName().equals("MEMORY.md"));
        if (files == null) return result;

        for (File file : files) {
            Map<String, String> fm = parseFrontmatter(file.toPath());
            if (fm.isEmpty()) continue;
            String memType = fm.get("type");
            if (type != null && !type.isEmpty() && !type.equals(memType)) continue;

            MemoryVO vo = new MemoryVO();
            vo.setName(fm.get("name"));
            vo.setType(memType);
            vo.setDescription(fm.getOrDefault("description", ""));
            vo.setCreatedAt(Long.parseLong(fm.getOrDefault("created_at", "0")));
            vo.setUpdatedAt(Long.parseLong(fm.getOrDefault("updated_at", "0")));
            result.add(vo);
        }
        result.sort(Comparator.comparingLong(MemoryVO::getUpdatedAt).reversed());
        return result;
    }

    @Override
    public MemoryVO detail(String name, String userName) {
        Path dir = userDir(userName);
        Path file = resolveFile(dir, name);
        if (!Files.exists(file)) throw new RuntimeException("记忆不存在: " + name);

        Map<String, String> fm = parseFrontmatter(file);
        String body = readBody(file);

        MemoryVO vo = new MemoryVO();
        vo.setName(fm.get("name"));
        vo.setType(fm.get("type"));
        vo.setDescription(fm.getOrDefault("description", ""));
        vo.setContent(body);
        vo.setCreatedAt(Long.parseLong(fm.getOrDefault("created_at", "0")));
        vo.setUpdatedAt(Long.parseLong(fm.getOrDefault("updated_at", "0")));
        return vo;
    }

    @Override
    public void create(Map<String, String> body, String userName) {
        String name = body.get("name");
        String type = body.get("type");
        String content = body.getOrDefault("content", "");

        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("名称不能为空");
        if (!"user".equals(type) && !"project".equals(type)) throw new IllegalArgumentException("类型无效: " + type);

        name = name.trim();
        Path dir = userDir(userName);

        // check duplicate
        Path file = resolveFile(dir, name);
        if (Files.exists(file)) throw new RuntimeException("记忆已存在: " + name);

        long now = System.currentTimeMillis() / 1000;
        String description = content.length() > 60 ? content.substring(0, 60).replace("\n", " ") + "..." : content.replace("\n", " ");

        writeMemoryFile(file, name, type, description, content, now, now);
        appendToIndex(dir, name, description, type);
    }

    @Override
    public void update(Map<String, String> body, String userName) {
        String name = body.get("name");
        String type = body.get("type");
        String content = body.getOrDefault("content", "");
        String oldName = body.get("oldName");

        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("名称不能为空");
        if (!"user".equals(type) && !"project".equals(type)) throw new IllegalArgumentException("类型无效: " + type);

        name = name.trim();
        Path dir = userDir(userName);

        // resolve old file
        Path oldFile = resolveFile(dir, oldName != null ? oldName : name);
        if (!Files.exists(oldFile)) throw new RuntimeException("记忆不存在: " + (oldName != null ? oldName : name));

        // read old metadata for created_at
        Map<String, String> oldFm = parseFrontmatter(oldFile);
        long createdAt = Long.parseLong(oldFm.getOrDefault("created_at", String.valueOf(System.currentTimeMillis() / 1000)));
        long now = System.currentTimeMillis() / 1000;
        String description = content.length() > 60 ? content.substring(0, 60).replace("\n", " ") + "..." : content.replace("\n", " ");

        // if name changed, delete old file and update index
        if (oldName != null && !oldName.equals(name)) {
            try { Files.delete(oldFile); } catch (IOException ignored) {}
            removeFromIndex(dir, oldName);
        }

        Path newFile = resolveFile(dir, name);
        writeMemoryFile(newFile, name, type, description, content, createdAt, now);
        upsertIndex(dir, name, description, type);
    }

    @Override
    public void delete(String namesStr, String userName) {
        if (namesStr == null || namesStr.isEmpty()) throw new IllegalArgumentException("名称不能为空");
        String[] names = namesStr.split(",");
        Path dir = userDir(userName);

        for (String name : names) {
            name = name.trim();
            Path file = resolveFile(dir, name);
            try { Files.deleteIfExists(file); } catch (IOException e) { log.warn("删除记忆文件失败: {}", file, e); }
            removeFromIndex(dir, name);
        }
    }

    // === file helpers ===

    private String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
    }

    private Path resolveFile(Path dir, String name) {
        // try to find existing file with matching name in frontmatter
        File[] files = dir.toFile().listFiles(f -> f.getName().endsWith(".md") && !f.getName().equals("MEMORY.md"));
        if (files != null) {
            for (File f : files) {
                Map<String, String> fm = parseFrontmatter(f.toPath());
                if (name.equals(fm.get("name"))) return f.toPath();
            }
        }
        // no existing file, build from name
        return dir.resolve(sanitize(name) + ".md");
    }

    private void writeMemoryFile(Path file, String name, String type, String description, String content, long createdAt, long updatedAt) {
        String yaml = "---\n" +
                "name: " + name + "\n" +
                "description: " + description + "\n" +
                "metadata:\n" +
                "  type: " + type + "\n" +
                "  created_at: " + createdAt + "\n" +
                "  updated_at: " + updatedAt + "\n" +
                "---\n\n" + content + "\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("写入记忆文件失败: " + file, e);
        }
    }

    // === frontmatter parsing (regex, consistent with SkillServiceImpl) ===

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);
    private static final Pattern KV_PATTERN = Pattern.compile("^(\\S+)\\s*:\\s*(.+)$", Pattern.MULTILINE);

    private Map<String, String> parseFrontmatter(Path file) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Matcher fm = FRONTMATTER_PATTERN.matcher(content);
            if (!fm.find()) return result;

            String yaml = fm.group(1);
            Matcher kv = KV_PATTERN.matcher(yaml);
            while (kv.find()) {
                String key = kv.group(1).trim();
                String value = kv.group(2).trim();
                result.put(key, value);
            }

            // parse nested metadata.type, metadata.created_at, metadata.updated_at
            if (yaml.contains("metadata:")) {
                String[] lines = yaml.split("\n");
                boolean inMetadata = false;
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("metadata:")) { inMetadata = true; continue; }
                    if (inMetadata) {
                        if (trimmed.startsWith("-") || (trimmed.length() > 0 && !trimmed.startsWith(" ") && !trimmed.isEmpty())) {
                            if (Character.isLetter(trimmed.charAt(0)) && !trimmed.startsWith(" ")) inMetadata = false;
                        }
                        if (inMetadata) {
                            Matcher mkv = Pattern.compile("^\\s+(\\S+)\\s*:\\s*(.+)$").matcher(line);
                            if (mkv.find()) result.put(mkv.group(1).trim(), mkv.group(2).trim());
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("解析记忆文件失败: {}", file, e);
        }
        return result;
    }

    private String readBody(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Matcher fm = FRONTMATTER_PATTERN.matcher(content);
            if (fm.find()) {
                return content.substring(fm.end()).trim();
            }
            return content.trim();
        } catch (IOException e) {
            throw new RuntimeException("读取记忆文件失败: " + file, e);
        }
    }

    // === MEMORY.md index helpers ===

    private Path indexPath(Path dir) {
        return dir.resolve("MEMORY.md");
    }

    private void appendToIndex(Path dir, String name, String description, String type) {
        String line = "- [" + name + "](" + sanitize(name) + ".md) — " + description + "\n";
        try {
            Files.writeString(indexPath(dir), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("更新 MEMORY.md 索引失败", e);
        }
    }

    private void removeFromIndex(Path dir, String name) {
        Path idx = indexPath(dir);
        if (!Files.exists(idx)) return;
        try {
            List<String> lines = Files.readAllLines(idx, StandardCharsets.UTF_8);
            String sanitized = sanitize(name);
            List<String> filtered = lines.stream()
                    .filter(l -> !l.contains("(" + sanitized + ".md)"))
                    .collect(Collectors.toList());
            Files.writeString(idx, String.join("\n", filtered) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("更新 MEMORY.md 索引失败", e);
        }
    }

    private void upsertIndex(Path dir, String name, String description, String type) {
        removeFromIndex(dir, name);
        appendToIndex(dir, name, description, type);
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd /Users/hbq/Codes/me/github/zephyr
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn compile -pl . -am 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/memory/service/impl/MemoryServiceImpl.java
git commit -m "feat: 实现 MemoryService 文件读写逻辑"
```

---

### Task 4: MemoryCtrl

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/memory/ctrl/MemoryCtrl.java`

- [ ] **Step 1: 创建 MemoryCtrl**

```java
package com.github.hbq969.ai.zephyr.memory.ctrl;

import com.github.hbq969.ai.zephyr.memory.service.MemoryService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.common.spring.context.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "记忆管理")
@RestController
@RequestMapping(path = "/zephyr-ui/memory")
public class MemoryCtrl {

    @Resource
    private MemoryService memoryService;

    private String userName() {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null ? ui.getUserName() : "admin";
    }

    @Operation(summary = "记忆列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> list(@RequestParam(required = false) String type) {
        return ReturnMessage.success(memoryService.list(type, userName()));
    }

    @Operation(summary = "记忆详情")
    @RequestMapping(path = "/detail", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> detail(@RequestParam String name) {
        return ReturnMessage.success(memoryService.detail(name, userName()));
    }

    @Operation(summary = "新增记忆")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> create(@RequestBody Map<String, String> body) {
        memoryService.create(body, userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "修改记忆")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> update(@RequestBody Map<String, String> body) {
        memoryService.update(body, userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除记忆")
    @RequestMapping(path = "/delete", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> delete(@RequestBody Map<String, String> body) {
        memoryService.delete(body.get("names"), userName());
        return ReturnMessage.success("ok");
    }
}
```

- [ ] **Step 2: 启动后端并验证接口**

```bash
# 启动后端（me 环境）
cd /Users/hbq/Codes/me/github/zephyr
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me &

# 等待启动后测试
sleep 15

# 创建记忆
curl -u admin:123456 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST http://localhost:30733/zephyr/zephyr-ui/memory/create \
  -d '{"name":"测试记忆","type":"user","content":"这是一条测试记忆内容。"}'

# 获取列表
curl -u admin:123456 -H "X-SM-Test: 1" \
  http://localhost:30733/zephyr/zephyr-ui/memory/list

# 获取详情
curl -u admin:123456 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/memory/detail?name=%E6%B5%8B%E8%AF%95%E8%AE%B0%E5%BF%86"

# 删除
curl -u admin:123456 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST http://localhost:30733/zephyr/zephyr-ui/memory/delete \
  -d '{"names":"测试记忆"}'
```

Expected: 所有接口返回 `{"state":"OK",...}`，list 返回数组。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/memory/ctrl/MemoryCtrl.java
git commit -m "feat: 新增 MemoryCtrl REST 接口"
```

---

### Task 5: TypeScript 类型定义

**Files:**
- Modify: `src/main/resources/static/src/types/chat.ts`

- [ ] **Step 1: 在 chat.ts 末尾添加 MemoryItem 类型**

在 `src/main/resources/static/src/types/chat.ts` 末尾添加：

```typescript
// === Memory ===
export interface MemoryItem {
  name: string
  type: 'user' | 'project'
  description: string
  content?: string
  createdAt: number
  updatedAt: number
}
```

- [ ] **Step 2: 验证类型检查**

```bash
cd /Users/hbq/Codes/me/github/zephyr/src/main/resources/static
npm run type-check 2>&1 | tail -5
```

Expected: exit 0，无类型错误。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/src/types/chat.ts
git commit -m "feat: 新增 MemoryItem 类型定义"
```

---

### Task 6: Store 方法

**Files:**
- Modify: `src/main/resources/static/src/store/settings.ts`

- [ ] **Step 1: 在 settings store 中添加记忆相关方法**

在 `src/main/resources/static/src/store/settings.ts` 中：

在 imports 中补充 `MemoryItem`：
```typescript
import type { ModelConfig, McpServer, McpTool, SkillConfig, MemoryItem } from '@/types/chat'
```

在 state 中添加：
```typescript
const memories = ref<MemoryItem[]>([])
```

在 return 的 export 闭包中添加方法（放在 loadSkills/syncInstallSkills 之后，return 之前）：

```typescript
// === Memory API 方法 ===

async function loadMemories(type?: string) {
  try {
    const params = type ? { type } : {}
    const res = await axios({ url: '/memory/list', method: 'get', params })
    if (res.data.state === 'OK' && Array.isArray(res.data.body)) {
      memories.value = res.data.body.map((m: any) => ({
        name: m.name,
        type: m.type,
        description: m.description,
        createdAt: m.createdAt,
        updatedAt: m.updatedAt
      }))
    }
  } catch (_) {}
}

async function loadMemoryDetail(name: string): Promise<MemoryItem | null> {
  try {
    const res = await axios({ url: '/memory/detail', method: 'get', params: { name } })
    if (res.data.state === 'OK') {
      const m = res.data.body
      return { name: m.name, type: m.type, description: m.description, content: m.content, createdAt: m.createdAt, updatedAt: m.updatedAt }
    }
  } catch (_) {}
  return null
}

async function createMemory(name: string, type: string, content: string) {
  const res = await axios({ url: '/memory/create', method: 'post', data: { name, type, content } })
  if (res.data.state === 'OK') return true
  return false
}

async function updateMemory(oldName: string, name: string, type: string, content: string) {
  const res = await axios({ url: '/memory/update', method: 'post', data: { oldName, name, type, content } })
  if (res.data.state === 'OK') return true
  return false
}

async function deleteMemories(names: string[]) {
  const res = await axios({ url: '/memory/delete', method: 'post', data: { names: names.join(',') } })
  if (res.data.state === 'OK') return true
  return false
}
```

在 return 对象中补充导出：
```typescript
memories,
loadMemories, loadMemoryDetail, createMemory, updateMemory, deleteMemories
```

- [ ] **Step 2: 验证类型检查**

```bash
cd /Users/hbq/Codes/me/github/zephyr/src/main/resources/static
npm run type-check 2>&1 | tail -5
```

Expected: exit 0。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/src/store/settings.ts
git commit -m "feat: store 新增记忆管理 API 方法"
```

---

### Task 7: MemorySettings.vue 页面

**Files:**
- Modify: `src/main/resources/static/src/views/settings/MemorySettings.vue`

- [ ] **Step 1: 重写 MemorySettings.vue**

```vue
<script lang="ts" setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/store/settings'
import { msg } from '@/utils/Utils'
import { Icon } from '@iconify/vue'

const router = useRouter()
const store = useSettingsStore()

const currentFilter = ref('all')
const expandedName = ref<string | null>(null)
const detailCache = ref<Record<string, string>>({})

const dialogVisible = ref(false)
const dialogTitle = ref('新增记忆')
const editingOldName = ref('')
const form = reactive({ name: '', type: 'user', content: '' })

const selectedNames = ref<string[]>([])
const selectAll = ref(false)
const deleteDialogVisible = ref(false)
const deleteTargets = ref<string[]>([])

const filteredMemories = computed(() => {
  if (currentFilter.value === 'all') return store.memories
  return store.memories.filter((m: any) => m.type === currentFilter.value)
})

const typeLabel: Record<string, string> = { user: '用户', project: '项目' }

function fmtTime(ts: number) {
  return new Date(ts * 1000).toISOString().slice(0, 10)
}

function toggleExpand(name: string) {
  if (expandedName.value === name) {
    expandedName.value = null
    return
  }
  expandedName.value = name
  if (!detailCache.value[name]) {
    store.loadMemoryDetail(name).then((m: any) => {
      if (m) detailCache.value[name] = m.content || ''
    })
  }
}

function openCreate() {
  dialogTitle.value = '新增记忆'
  editingOldName.value = ''
  form.name = ''
  form.type = 'user'
  form.content = ''
  dialogVisible.value = true
}

function openEdit(m: any) {
  dialogTitle.value = '编辑记忆'
  editingOldName.value = m.name
  form.name = m.name
  form.type = m.type
  // load content if not cached
  if (!detailCache.value[m.name]) {
    store.loadMemoryDetail(m.name).then((d: any) => {
      if (d) {
        detailCache.value[m.name] = d.content || ''
        form.content = d.content || ''
      }
    })
  } else {
    form.content = detailCache.value[m.name] || ''
  }
  dialogVisible.value = true
}

async function saveMemory() {
  if (!form.name.trim()) { msg('请输入名称', 'warning'); return }
  if (!form.content.trim()) { msg('请输入内容', 'warning'); return }

  let ok: boolean
  if (editingOldName.value) {
    ok = await store.updateMemory(editingOldName.value, form.name.trim(), form.type, form.content.trim())
  } else {
    ok = await store.createMemory(form.name.trim(), form.type, form.content.trim())
  }

  if (ok) {
    dialogVisible.value = false
    // clear detail cache for updated items
    delete detailCache.value[editingOldName.value || form.name]
    await store.loadMemories(currentFilter.value === 'all' ? undefined : currentFilter.value)
  }
}

function confirmDeleteSingle(m: any) {
  deleteTargets.value = [m.name]
  deleteDialogVisible.value = true
}

function confirmDeleteBatch() {
  if (selectedNames.value.length === 0) return
  deleteTargets.value = [...selectedNames.value]
  deleteDialogVisible.value = true
}

async function executeDelete() {
  const ok = await store.deleteMemories(deleteTargets.value)
  if (ok) {
    deleteDialogVisible.value = false
    selectedNames.value = []
    deleteTargets.value.forEach(n => delete detailCache.value[n])
    if (expandedName.value && deleteTargets.value.includes(expandedName.value)) {
      expandedName.value = null
    }
    await store.loadMemories(currentFilter.value === 'all' ? undefined : currentFilter.value)
  }
}

function toggleSelectAll() {
  if (selectAll.value) {
    selectedNames.value = filteredMemories.value.map((m: any) => m.name)
  } else {
    selectedNames.value = []
  }
}

function toggleSelect(name: string) {
  const idx = selectedNames.value.indexOf(name)
  if (idx >= 0) {
    selectedNames.value.splice(idx, 1)
  } else {
    selectedNames.value.push(name)
  }
  selectAll.value = selectedNames.value.length === filteredMemories.value.length
}

async function setFilter(type: string) {
  currentFilter.value = type
  expandedName.value = null
  selectedNames.value = []
  await store.loadMemories(type === 'all' ? undefined : type)
}

onMounted(() => { store.loadMemories() })
</script>

<template>
  <div class="settings-page">
    <div class="page-header">
      <button class="back-btn" @click="router.push('/chat')">
        <Icon icon="lucide:chevron-left" />
      </button>
      <h2>记忆管理</h2>
    </div>

    <div class="page-toolbar">
      <div class="filter-tabs">
        <button :class="['filter-tab', { active: currentFilter === 'all' }]" @click="setFilter('all')">
          全部 {{ currentFilter === 'all' ? store.memories.length : '' }}
        </button>
        <button :class="['filter-tab', { active: currentFilter === 'user' }]" @click="setFilter('user')">
          用户
        </button>
        <button :class="['filter-tab', { active: currentFilter === 'project' }]" @click="setFilter('project')">
          项目
        </button>
      </div>
      <button class="add-btn" @click="openCreate">
        <Icon icon="lucide:plus" /> 新增记忆
      </button>
    </div>

    <div v-if="selectedNames.length > 0" class="batch-bar">
      <span>已选 <strong>{{ selectedNames.length }}</strong> 项</span>
      <button class="batch-del-btn" @click="confirmDeleteBatch">批量删除</button>
    </div>

    <div v-if="filteredMemories.length === 0" class="empty-state">
      <Icon icon="lucide:file-text" :width="40" />
      <h3>暂无记忆</h3>
      <p>点击「新增记忆」创建第一条</p>
    </div>

    <div v-else class="card-list">
      <div v-for="m in filteredMemories" :key="m.name" class="card">
        <div class="card-inner">
          <div class="card-header">
            <el-checkbox
              :model-value="selectedNames.includes(m.name)"
              @change="toggleSelect(m.name)"
            />
            <Icon icon="lucide:file-text" class="card-icon" />
            <div class="card-body">
              <div class="card-title" @click="toggleExpand(m.name)">{{ m.name }}</div>
              <div class="card-desc">{{ m.description }}</div>
              <div class="card-meta">
                <span class="type-badge" :class="m.type">{{ typeLabel[m.type] }}</span>
                <span>{{ fmtTime(m.updatedAt) }}</span>
              </div>
            </div>
            <div class="card-actions">
              <button class="card-action-btn" @click="openEdit(m)" title="编辑">
                <Icon icon="lucide:edit-3" />
              </button>
              <button class="card-action-btn danger" @click="confirmDeleteSingle(m)" title="删除">
                <Icon icon="lucide:trash-2" />
              </button>
            </div>
          </div>
        </div>
        <div v-if="expandedName === m.name" class="card-expand">
          <div class="card-expand-body" v-html="detailCache[m.name] || '加载中...'"></div>
          <div class="card-expand-footer">
            <span>创建于 {{ fmtTime(m.createdAt) }}</span>
            <span>·</span>
            <span>更新于 {{ fmtTime(m.updatedAt) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px" destroy-on-close>
      <el-form :model="form" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="简短标题" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" style="width:100%">
            <el-option label="用户" value="user" />
            <el-option label="项目" value="project" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="6" placeholder="记忆详情，支持 Markdown" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveMemory">保存</el-button>
      </template>
    </el-dialog>

    <!-- 删除确认弹窗 -->
    <el-dialog v-model="deleteDialogVisible" title="确认删除" width="400px">
      <p style="margin-bottom:10px">确定删除以下 {{ deleteTargets.length }} 条记忆？</p>
      <ul style="padding-left:18px;color:var(--el-text-color-regular)">
        <li v-for="n in deleteTargets" :key="n">{{ n }}</li>
      </ul>
      <template #footer>
        <el-button @click="deleteDialogVisible = false">取消</el-button>
        <el-button type="danger" @click="executeDelete">删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.settings-page { max-width: 720px; margin: 0 auto; padding: 24px; }

.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
.back-btn {
  width: 36px; height: 36px; border-radius: 50%;
  border: 1px solid var(--el-border-color); background: var(--el-bg-color);
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  color: var(--el-text-color-secondary); font-size: 18px;
}
.back-btn:hover { background: var(--el-fill-color-light); }
h2 { font-family: Georgia, serif; font-weight: 400; font-size: 22px; letter-spacing: -0.3px; color: var(--el-text-color-primary); margin: 0; }

.page-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 20px; }

.filter-tabs { display: flex; gap: 4px; margin-right: auto; }
.filter-tab {
  padding: 6px 14px; border-radius: 8px; border: none;
  background: transparent; color: var(--el-text-color-secondary);
  font-family: inherit; font-size: 13px; font-weight: 500; cursor: pointer;
  transition: all 0.15s;
}
.filter-tab:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.filter-tab.active { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }

.add-btn {
  display: flex; align-items: center; gap: 4px;
  padding: 8px 18px; border-radius: 8px; border: none;
  background: var(--el-color-primary); color: #fff;
  font-family: inherit; font-size: 14px; font-weight: 500; cursor: pointer;
}
.add-btn:hover { opacity: 0.9; }

.batch-bar {
  display: flex; align-items: center; gap: 10px; margin-bottom: 12px;
  padding: 8px 12px; border-radius: 8px;
  background: var(--el-fill-color-light); font-size: 13px; color: var(--el-text-color-secondary);
}
.batch-del-btn {
  padding: 4px 12px; border-radius: 8px; border: 1px solid var(--el-color-danger);
  background: transparent; color: var(--el-color-danger);
  font-family: inherit; font-size: 13px; cursor: pointer;
}
.batch-del-btn:hover { background: var(--el-color-danger); color: #fff; }

.empty-state {
  text-align: center; padding: 80px 20px; color: var(--el-text-color-secondary);
}
.empty-state h3 { font-family: Georgia, serif; font-size: 18px; font-weight: 400; color: var(--el-text-color-primary); margin: 12px 0 4px; }

.card-list { display: flex; flex-direction: column; gap: 1px; background: var(--el-border-color); border-radius: 12px; overflow: hidden; }

.card { background: var(--el-bg-color); }
.card-inner { padding: 14px 16px; }
.card-header { display: flex; align-items: flex-start; gap: 10px; }
.card-icon { color: var(--el-text-color-secondary); font-size: 16px; flex-shrink: 0; margin-top: 1px; }
.card-body { flex: 1; min-width: 0; }
.card-title { font-size: 14px; font-weight: 500; color: var(--el-text-color-primary); cursor: pointer; }
.card-title:hover { color: var(--el-color-primary); }
.card-desc { font-size: 13px; color: var(--el-text-color-secondary); margin-top: 2px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.card-meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: 12px; color: var(--el-text-color-placeholder); }
.type-badge { display: inline-block; padding: 2px 8px; border-radius: 9999px; font-size: 11px; font-weight: 500; }
.type-badge.user { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.type-badge.project { background: var(--el-color-success-light-9); color: var(--el-color-success); }

.card-actions { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.card-action-btn {
  width: 30px; height: 30px; border-radius: 50%; border: none;
  background: transparent; color: var(--el-text-color-placeholder); cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.15s;
}
.card-action-btn:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.card-action-btn.danger:hover { background: rgba(198,69,69,0.08); color: var(--el-color-danger); }

.card-expand-body {
  padding: 0 16px 14px;
  margin: 0 16px 0 41px;
  border-top: 1px solid var(--el-border-color);
  font-size: 14px; color: var(--el-text-color-regular); line-height: 1.65;
}
.card-expand-footer {
  display: flex; align-items: center; gap: 8px;
  margin-top: 10px; font-size: 12px; color: var(--el-text-color-placeholder);
  padding: 0 16px 12px 41px;
}

/* dark mode */
html.dark .card { background: var(--el-bg-color); }
html.dark .card:hover { background: var(--el-fill-color-light); }
html.dark .card-action-btn:hover { background: var(--el-fill-color); }
html.dark .filter-tab:hover { background: var(--el-fill-color); }
html.dark .filter-tab.active { background: var(--el-fill-color); }
html.dark .back-btn:hover { background: var(--el-fill-color); }
</style>
```

- [ ] **Step 2: 构建前端并验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr/src/main/resources/static
npm run build 2>&1 | tail -10
```

Expected: 构建成功，dist 输出到 `zephyr-ui/`。

- [ ] **Step 3: 复制前端产物到 target**

```bash
cd /Users/hbq/Codes/me/github/zephyr
mkdir -p target/classes/static && cp -rf src/main/resources/static/zephyr-ui target/classes/static/
```

- [ ] **Step 4: 浏览器验证**

打开 `http://localhost:30733/zephyr/zephyr-ui/index.html`，登录后进入 Settings → 记忆管理，验证：
- 创建一���用户类型记忆
- 创建一条项目类型记忆
- 筛选切换（全部/用户/项目）
- 点击标题展开/收起
- 编辑记忆
- 单条删除
- checkbox 批量勾选 + 批量删除

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/src/views/settings/MemorySettings.vue
git commit -m "feat: 重写 MemorySettings.vue 对接记忆管理 API"
```

---

## 验证清单

全部 Task 完成后：

- [ ] `curl` 测试 4 个 API 端点（create/list/detail/delete）
- [ ] 浏览器中完整操作：新增 → 查看 → 编辑 → 删除
- [ ] 筛选切换（全部/用户/项目）正常
- [ ] 展开/收起正常
- [ ] 批量删除正常
- [ ] 空状态显示正常
- [ ] 文件系统中 `~/.zephyr/memory/{user}/` 生成正确的 `.md` 文件
- [ ] `MEMORY.md` 索引正确维护
- [ ] 暗黑模式下样式正常
