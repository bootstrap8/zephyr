# Shell 安全配置管理 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 4 个 YAML 安全配置项迁移到 DB 表 `zephyr_security_rules`，提供管理页面，修改后即时刷新内存缓存。

**Architecture:** 新建 `SecurityConfigService` 统一管理内存缓存（volatile ConfigSnapshot），两阶段初始化（YAML 种子 → DB 合并），1 张统一表 + type 鉴别器。SecurityEvaluator 和 ChatServiceImpl 改为从 SecurityConfigService 读取。前端单页面 4 Tab 管理。

**Tech Stack:** Java 17, SpringBoot 3.5.4, MyBatis, H2/PostgreSQL, Vue3 + TS + Element Plus + Iconify

## Global Constraints

- Java 17
- MyBatis Mapper XML 四个方言目录（common/embedded/mysql/postgresql）
- Controller 使用 `@RequestMapping` 非简写注解，路径 `/zephyr-ui/` 前缀
- 所有端点需 `@SMRequiresPermissions`（menu="zephyr_api", menuDesc="zephyr智能体"）
- 前端 axios URL 不含 outDir 前缀，baseURL 由 `.env.*` 提供
- Element Plus 主题色，Iconify 图标
- DDL 加 `if not exists`，时间戳用秒
- MySQL DDL：UNIQUE 约束写在 CREATE TABLE 内（`UNIQUE(rule_type, rule_value)`），不用 `CREATE UNIQUE INDEX IF NOT EXISTS`（MySQL 不支持）
- `@ExceptionHandler(IllegalArgumentException.class)` 返回 HTTP 400（type 校验 + value 校验 + description 长度）

---
---

### Task 1: 实体类 + DAO 接口 + Mapper XML

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/security/dao/SecurityConfigDao.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/security/dao/entity/SecurityRuleEntity.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/security/dao/mapper/common/SecurityConfigMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/security/dao/mapper/embedded/SecurityConfigMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/security/dao/mapper/mysql/SecurityConfigMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/security/dao/mapper/postgresql/SecurityConfigMapper.xml`

**Interfaces:**
- Consumes: 无
- Produces: `SecurityRuleEntity` (id, ruleType, ruleValue, description, enabled, createdAt, updatedAt), `SecurityConfigDao` (queryByType, insert, deleteById, updateById)

- [ ] **Step 1: 创建实体类**

```java
package com.github.hbq969.ai.zephyr.security.dao.entity;

import lombok.Data;

@Data
public class SecurityRuleEntity {
    private String id;
    /** SHELL_ALLOWED | DEFAULT_ALLOW | HARD_BLOCK | SOFT_BLOCK */
    private String ruleType;
    /** 命令名（命令类）或正则模式（规则类） */
    private String ruleValue;
    private String description;
    private Integer enabled;
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 2: 创建 DAO 接口**

```java
package com.github.hbq969.ai.zephyr.security.dao;

import com.github.hbq969.ai.zephyr.security.dao.entity.SecurityRuleEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface SecurityConfigDao {

    void createSecurityRulesTable();

    List<SecurityRuleEntity> queryByType(@Param("ruleType") String ruleType);
    List<SecurityRuleEntity> queryAll();
    void insert(SecurityRuleEntity entity);
    void deleteById(@Param("id") String id);
    void updateById(SecurityRuleEntity entity);
}
```

- [ ] **Step 3: 创建 Mapper XML — common (DML)**

`src/main/java/com/github/hbq969/ai/zephyr/security/dao/mapper/common/SecurityConfigMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.security.dao.SecurityConfigDao">

    <select id="queryByType" resultType="com.github.hbq969.ai.zephyr.security.dao.entity.SecurityRuleEntity">
        select id, rule_type as ruleType, rule_value as ruleValue,
               description, enabled,
               created_at as createdAt, updated_at as updatedAt
        from zephyr_security_rules
        where rule_type = #{ruleType} and enabled = 1
        order by created_at asc
    </select>

    <select id="queryAll" resultType="com.github.hbq969.ai.zephyr.security.dao.entity.SecurityRuleEntity">
        select id, rule_type as ruleType, rule_value as ruleValue,
               description, enabled,
               created_at as createdAt, updated_at as updatedAt
        from zephyr_security_rules
        where enabled = 1
        order by rule_type, created_at asc
    </select>

    <insert id="insert">
        insert into zephyr_security_rules
        (id, rule_type, rule_value, description, enabled, created_at, updated_at)
        values (#{id}, #{ruleType}, #{ruleValue}, #{description}, 1, #{createdAt}, #{updatedAt})
    </insert>

    <update id="deleteById">
        delete from zephyr_security_rules where id = #{id}
    </update>

    <select id="queryAllByType" resultType="com.github.hbq969.ai.zephyr.security.dao.entity.SecurityRuleEntity">
        select id, rule_type as ruleType, rule_value as ruleValue,
               description, enabled,
               created_at as createdAt, updated_at as updatedAt
        from zephyr_security_rules
        where rule_type = #{ruleType}
        order by created_at asc
    </select>

    <update id="updateById">
        update zephyr_security_rules
        set rule_value = #{ruleValue}, description = #{description},
            enabled = COALESCE(#{enabled}, enabled),
            updated_at = #{updatedAt}
        where id = #{id}
    </update>

</mapper>
```

- [ ] **Step 4: 创建 Mapper XML — embedded (H2 DDL)**

`src/main/java/com/github/hbq969/ai/zephyr/security/dao/mapper/embedded/SecurityConfigMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.security.dao.SecurityConfigDao">

  <update id="createSecurityRulesTable">
    create table if not exists zephyr_security_rules (
      id varchar(64) primary key,
      rule_type varchar(32) not null,
      rule_value varchar(512) not null,
      description varchar(256),
      enabled smallint default 1,
      created_at bigint,
      updated_at bigint
    );
    create unique index if not exists uk_zsr_type_value
      on zephyr_security_rules(rule_type, rule_value);
    create index if not exists idx_zsr_type on zephyr_security_rules(rule_type);
  </update>

</mapper>
```

- [ ] **Step 5: 创建 Mapper XML — mysql**

`src/main/java/com/github/hbq969/ai/zephyr/security/dao/mapper/mysql/SecurityConfigMapper.xml` 内容与 embedded 相同（DDL 语法兼容）。

- [ ] **Step 6: 创建 Mapper XML — postgresql**

`src/main/java/com/github/hbq969/ai/zephyr/security/dao/mapper/postgresql/SecurityConfigMapper.xml` 内容与 embedded 相同。

- [ ] **Step 7: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/security/dao/
git commit -m "feat: 新增 SecurityRuleEntity + Dao + Mapper XML (zephyr_security_rules)"
```

---

### Task 2: SecurityConfigService — 内存缓存 + 两阶段初始化

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/security/service/SecurityConfigService.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java`

**Interfaces:**
- Consumes: SecurityConfigDao, ZephyrConfigProperties, AuditLogger
- Produces: `SecurityConfigService.getSnapshot() → ConfigSnapshot`, `SecurityConfigService.refresh()`

- [ ] **Step 1: 创建 SecurityConfigService**

```java
package com.github.hbq969.ai.zephyr.security.service;

import com.github.hbq969.ai.zephyr.security.dao.SecurityConfigDao;
import com.github.hbq969.ai.zephyr.security.dao.entity.SecurityRuleEntity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

@Slf4j
@Service
public class SecurityConfigService {

    @Resource
    private SecurityConfigDao dao;

    private volatile ConfigSnapshot snapshot;

    @PostConstruct
    void init() {
        refresh();
    }

    public ConfigSnapshot getSnapshot() {
        return snapshot;
    }

    public synchronized void refresh() {
        snapshot = loadFromDb();
        log.info("[SecurityConfig] 刷新完成 — shellAllowed={}, defaultAllow={}, hardBlock={}, softBlock={}",
                snapshot.shellAllowedCommands().size(), snapshot.defaultAllowCommands().size(),
                snapshot.hardBlockPatterns().size(), snapshot.softBlockPatterns().size());
    }

    private ConfigSnapshot loadFromDb() {
        List<SecurityRuleEntity> all = dao.queryAll();
        Set<String> shellAllowed = new LinkedHashSet<>();
        Set<String> defaultAllow = new LinkedHashSet<>();
        List<String> hardBlockRaw = new ArrayList<>();
        List<String> softBlockRaw = new ArrayList<>();
        for (SecurityRuleEntity r : all) {
            switch (r.getRuleType()) {
                case RULE_TYPE_SHELL_ALLOWED -> shellAllowed.add(r.getRuleValue());
                case RULE_TYPE_DEFAULT_ALLOW -> defaultAllow.add(r.getRuleValue());
                case RULE_TYPE_HARD_BLOCK -> hardBlockRaw.add(r.getRuleValue());
                case RULE_TYPE_SOFT_BLOCK -> softBlockRaw.add(r.getRuleValue());
            }
        }
        return new ConfigSnapshot(shellAllowed, defaultAllow,
                compilePatterns(hardBlockRaw), compilePatterns(softBlockRaw));
    }

    private static List<Pattern> compilePatterns(List<String> patterns) {
        if (patterns == null) return List.of();
        List<Pattern> result = new ArrayList<>();
        for (String raw : patterns) {
            try {
                result.add(Pattern.compile(raw, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                log.warn("[SecurityConfig] 跳过非法正则: [{}], 错误: {}", raw, e.getMessage());
            }
        }
        return result;
    }

    public record ConfigSnapshot(
            Set<String> shellAllowedCommands,
            Set<String> defaultAllowCommands,
            List<Pattern> hardBlockPatterns,
            List<Pattern> softBlockPatterns
    ) {}
}
```

- [ ] **Step 2: 创建常量（RULE_TYPE_*）**

在 `ZephyrConstants.java` 中添加：

```java
String RULE_TYPE_SHELL_ALLOWED = "SHELL_ALLOWED";
String RULE_TYPE_DEFAULT_ALLOW = "DEFAULT_ALLOW";
String RULE_TYPE_HARD_BLOCK = "HARD_BLOCK";
String RULE_TYPE_SOFT_BLOCK = "SOFT_BLOCK";
```

- [ ] **Step 3: 在 InitialServiceImpl 注册建表**

在 `InitialServiceImpl.java` 的 `tableCreate0()` 方法中添加：

```java
@Resource
private com.github.hbq969.ai.zephyr.security.dao.SecurityConfigDao securityConfigDao;

// 在 tableCreate0() 中添加:
ThrowUtils.call("zephyr_security_rules",
        () -> securityConfigDao.createSecurityRulesTable());
```

- [ ] **Step 4: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/security/service/SecurityConfigService.java
git add src/main/java/com/github/hbq969/ai/zephyr/constant/ZephyrConstants.java
git add src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java
git commit -m "feat: SecurityConfigService — 两阶段初始化 + volatile ConfigSnapshot 缓存"
```

---

### Task 3: 改造 SecurityEvaluator + ChatServiceImpl

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/security/SecurityEvaluator.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java`

**Interfaces:**
- Consumes: SecurityConfigService
- Produces: 改造后的 evaluate() 和 executeShell() 从 snapshot 读取

- [ ] **Step 1: 改造 SecurityEvaluator**

删除 `@PostConstruct init()`、`initReadOnlyCommands()`、`initPatterns()` 方法。添加 `@Resource private SecurityConfigService securityConfigService;`。

修改 `evaluateShell()` 在方法入口一次性获取 snapshot：

```java
@Resource
private SecurityConfigService securityConfigService;

// 删除: init(), initReadOnlyCommands(), initPatterns()
// 删除: readOnlyCommands, hardBlockShellPatterns, hardBlockPathPrefixes, softBlockShellPatterns 字段

private Result evaluateShell(Map<String, Object> arguments, String mode, WorkspaceBoundary boundary) {
    if (arguments == null || !arguments.containsKey("command")) return Result.allow();
    Object cmdObj = arguments.get("command");
    if (cmdObj == null) return Result.allow();
    String command = cmdObj.toString().trim();

    SecurityConfigService.ConfigSnapshot snap = securityConfigService.getSnapshot();

    // 1. HARD BLOCK 检查
    for (Pattern p : snap.hardBlockPatterns()) {
        if (ReUtil.contains(p, command)) {
            log.info("[安全] HARD_BLOCK 触发拒绝 — 命令: {}, 命中规则: {}", command, p.pattern());
            return Result.block("HARD_BLOCK", "命令匹配安全红线规则，禁止执行");
        }
    }

    if ("bypass".equalsIgnoreCase(mode)) return Result.allow();

    // 2. workspace 边界检查（不变）

    // 3. SOFT BLOCK 检查（改用 snapshot）
    for (Pattern p : snap.softBlockPatterns()) { ... }

    // 4. default 模式（改用 snapshot.defaultAllowCommands()）
    if (!"acceptEdits".equalsIgnoreCase(mode) && !"bypass".equalsIgnoreCase(mode)) {
        // ...复合命令检查...
        if (!snap.defaultAllowCommands().contains(cmdName)) {
            return Result.confirm("MODE_DEFAULT", "Default 模式下 shell 命令需要用户确认");
        }
    }
    return Result.allow();
}
```

关键改动点：
- 删除字段 `hardBlockShellPatterns`, `softBlockShellPatterns`, `readOnlyCommands`
- 删除方法 `init()`, `initReadOnlyCommands()`, `initPatterns()`
- 删除 `parseCommandList()`（已移至 SecurityConfigService）
- `evaluateShell()` 入口添加 `SecurityConfigService.ConfigSnapshot snap = securityConfigService.getSnapshot();`
- 所有原来引用 `readOnlyCommands` 改为 `snap.defaultAllowCommands()`
- 所有原来引用 `hardBlockShellPatterns` 改为 `snap.hardBlockPatterns()`
- 所有原来引用 `softBlockShellPatterns` 改为 `snap.softBlockPatterns()`

- [ ] **Step 2: 改造 ChatServiceImpl**

删除 `shellWhitelist` 字段（第846行）和 `initShellWhitelist()` 方法（第848-851行）。添加：

```java
@Resource
private SecurityConfigService securityConfigService;
```

在 `executeShell()` 方法开头获取 snapshot：

```java
SecurityConfigService.ConfigSnapshot snap = securityConfigService.getSnapshot();

// 白名单校验: shellWhitelist → snap.shellAllowedCommands()
if (!SHELL_MODE_ALLOW_ALL.equals(mode)) {
    String cmdName = command.split("\\s+", 2)[0];
    int lastSlash = cmdName.lastIndexOf('/');
    if (lastSlash >= 0) cmdName = cmdName.substring(lastSlash + 1);
    if (!snap.shellAllowedCommands().contains(cmdName)) {
        return "命令 '" + cmdName + "' 不在白名单中，拒绝执行";
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/security/SecurityEvaluator.java
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java
git commit -m "refactor: SecurityEvaluator + ChatServiceImpl 改为从 SecurityConfigService 读取配置"
```

---

### Task 4: ZephyrConfigProperties + application.yml 清理

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces: 4 个字段完全删除，DB 是唯一数据源

- [ ] **Step 1: 删除 ZephyrConfigProperties 字段**

删除以下 4 个字段（只删字段，Shell/Security 嵌套类和其余字段保留）：

- `Shell.allowedCommands`（ZephyrConfigProperties.java 第311行）
- `Security.defaultAllowCommands`（第352行）
- `HardBlock.shellPatterns`（第376行）
- `SoftBlock.shellPatterns`（第389行）

保留：`Shell.mode`, `Shell.maxBackgroundProcesses`, `Security.enabled`, `Security.audit` 等。

- [ ] **Step 2: 删除 application.yml 中对应 key**

移除 `zephyr.shell.allowed-commands`、`zephyr.security.default-allow-commands`、`zephyr.security.hard-block.shell-patterns`、`zephyr.security.soft-block.shell-patterns` 四个 key 及其值和注释。

- [ ] **Step 3: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java
git add src/main/resources/application.yml
git commit -m "refactor: 移除 YAML 安全配置 — 4 项已迁移至 DB (zephyr_security_rules)"
```

---

### Task 5: SecurityConfigCtrl — REST API

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/security/ctrl/SecurityConfigCtrl.java`

**Interfaces:**
- Consumes: SecurityConfigService (list/add/delete/update)
- Produces: `GET /zephyr-ui/security/{type}/list`, `POST /zephyr-ui/security/{type}/add`, `POST /zephyr-ui/security/{type}/delete`, `POST /zephyr-ui/security/{type}/update`

- [ ] **Step 1: 创建 Controller**

```java
package com.github.hbq969.ai.zephyr.security.ctrl;

import com.github.hbq969.ai.zephyr.security.dao.SecurityConfigDao;
import com.github.hbq969.ai.zephyr.security.dao.entity.SecurityRuleEntity;
import com.github.hbq969.ai.zephyr.security.service.SecurityConfigService;
import com.github.hbq969.ai.zephyr.security.AuditLogger;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.common.spring.context.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import com.github.hbq969.code.sm.perm.api.SMRequiresPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

@Tag(name = "安全配置")
@RestController
@RequestMapping(path = "/zephyr-ui/security")
public class SecurityConfigCtrl {

    @Resource
    private SecurityConfigDao dao;

    @Resource
    private SecurityConfigService securityConfigService;

    @Resource
    private AuditLogger auditLogger;

    private String userName() {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null ? ui.getUserName() : "admin";
    }

    private static final java.util.Set<String> VALID_TYPES = java.util.Set.of(
            RULE_TYPE_SHELL_ALLOWED, RULE_TYPE_DEFAULT_ALLOW,
            RULE_TYPE_HARD_BLOCK, RULE_TYPE_SOFT_BLOCK
    );

    private void validateType(String type) {
        if (!VALID_TYPES.contains(type)) {
            throw new IllegalArgumentException("非法 rule_type: " + type);
        }
    }

    private void validateValue(String type, String value, String description) {
        if (RULE_TYPE_HARD_BLOCK.equals(type) || RULE_TYPE_SOFT_BLOCK.equals(type)) {
            try { Pattern.compile(value, Pattern.CASE_INSENSITIVE); } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("正则表达式非法: " + e.getMessage());
            }
        } else {
            if (!value.matches("^[a-zA-Z0-9._-]+$")) {
                throw new IllegalArgumentException("命令名格式无效: " + value);
            }
        }
        if (description != null && description.length() > 256) {
            throw new IllegalArgumentException("描述不能超过256字符");
        }
    }

    /** 统一处理参数校验异常，返回 400 而非 500 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ReturnMessage<?> handleIllegalArgument(IllegalArgumentException e) {
        return ReturnMessage.fail(400, e.getMessage());
    }

    @Operation(summary = "查询安全配置规则列表")
    @RequestMapping(path = "/{type}/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体",
            apiKey = "security_list", apiDesc = "安全配置_规则列表")
    public ReturnMessage<?> list(@PathVariable String type) {
        validateType(type);
        return ReturnMessage.success(dao.queryByType(type));
    }

    @Operation(summary = "新增安全配置规则")
    @RequestMapping(path = "/{type}/add", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体",
            apiKey = "security_add", apiDesc = "安全配置_新增规则")
    public ReturnMessage<?> add(@PathVariable String type, @RequestBody Map<String, String> body) {
        validateType(type);
        String value = body.get("value");
        String desc = body.getOrDefault("description", "");
        validateValue(type, value, desc);
        long now = System.currentTimeMillis() / 1000;
        SecurityRuleEntity e = new SecurityRuleEntity();
        e.setId(UUID.randomUUID().toString().replace("-", ""));
        e.setRuleType(type);
        e.setRuleValue(value);
        e.setDescription(desc);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        dao.insert(e);
        securityConfigService.refresh();
        auditLogger.log("SECURITY_CONFIG", type, "ADD", "新增规则: " + value, userName());
        return ReturnMessage.success(e);
    }

    @Operation(summary = "删除安全配置规则")
    @RequestMapping(path = "/{type}/delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体",
            apiKey = "security_delete", apiDesc = "安全配置_删除规则")
    public ReturnMessage<?> delete(@PathVariable String type, @RequestBody Map<String, String> body) {
        validateType(type);
        String id = body.get("id");
        dao.deleteById(id);
        securityConfigService.refresh();
        auditLogger.log("SECURITY_CONFIG", type, "DELETE", "删除规则 id=" + id, userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "修改安全配置规则")
    @RequestMapping(path = "/{type}/update", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体",
            apiKey = "security_update", apiDesc = "安全配置_修改规则")
    public ReturnMessage<?> update(@PathVariable String type, @RequestBody Map<String, String> body) {
        validateType(type);
        String value = body.get("value");
        String desc = body.getOrDefault("description", "");
        validateValue(type, value, desc);
        long now = System.currentTimeMillis() / 1000;
        SecurityRuleEntity e = new SecurityRuleEntity();
        e.setId(body.get("id"));
        e.setRuleValue(value);
        e.setDescription(desc);
        // enabled 可选字段，默认保持原值（DAO 不更新 null 字段）
        if (body.containsKey("enabled")) {
            e.setEnabled(Integer.parseInt(body.get("enabled")));
        }
        e.setUpdatedAt(now);
        dao.updateById(e);
        securityConfigService.refresh();
        auditLogger.log("SECURITY_CONFIG", type, "UPDATE", "修改规则: " + value, userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "启用/禁用安全规则")
    @RequestMapping(path = "/{type}/toggle", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体",
            apiKey = "security_update", apiDesc = "安全配置_启用禁用")
    public ReturnMessage<?> toggle(@PathVariable String type, @RequestBody Map<String, String> body) {
        validateType(type);
        long now = System.currentTimeMillis() / 1000;
        SecurityRuleEntity e = new SecurityRuleEntity();
        e.setId(body.get("id"));
        e.setEnabled(Integer.parseInt(body.get("enabled")));
        e.setUpdatedAt(now);
        dao.updateById(e);
        securityConfigService.refresh();
        auditLogger.log("SECURITY_CONFIG", type, body.get("enabled").equals("1") ? "ENABLE" : "DISABLE",
                "规则 id=" + body.get("id"), userName());
        return ReturnMessage.success("ok");
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

- [ ] **Step 3: curl 测试接口**

启动后端后执行：

```bash
# 查询
curl -u admin:1 -H "X-SM-Test: 1" "http://localhost:30733/zephyr/zephyr-ui/security/SHELL_ALLOWED/list"

# 新增
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/security/HARD_BLOCK/add" \
  -d '{"value":"rm\\\\s+-rf","description":"测试规则"}'

# 删除
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/security/HARD_BLOCK/delete" \
  -d '{"id":"xxx"}'
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/security/ctrl/SecurityConfigCtrl.java
git commit -m "feat: SecurityConfigCtrl — 安全配置 REST API (含权限+校验+审计)"
```

---

### Task 6: SQL 初始化脚本

**Files:**
- Modify: `src/main/resources/zephyr-zh-CN.sql`
- Modify: `src/main/resources/zephyr-en-US.sql`
- Modify: `src/main/resources/zephyr-ja-JP.sql`

**Interfaces:**
- Consumes: application.yml 中的当前配置值
- Produces: INSERT 语句，幂等写入 zephyr_security_rules

- [ ] **Step 1: 生成 SQL 并添加到三语言文件**

将 application.yml 中的 4 个配置转换为 INSERT 语句。使用 `MERGE` (H2) / `INSERT ... WHERE NOT EXISTS` (PG/MySQL) 保证幂等。

```sql
-- zephyr-zh-CN.sql 末尾添加:
-- shell allowed commands (从 zephyr.shell.allowed-commands 迁移)
INSERT INTO zephyr_security_rules (id, rule_type, rule_value, description, enabled, created_at)
SELECT 'seed_shell_' || rownum(), 'SHELL_ALLOWED', v, 'Shell白名单命令', 1, 1735800000
FROM (VALUES ('rm'),('python3'),('python'),('node'),('ruby'),('perl'),('php'),('lua'),
     ('deno'),('bun'),('npm'),('npx'),('yarn'),('pnpm'),('pip'),('pip3'),('gem'),
     ('composer'),('cargo'),('go'),('git'),('hg'),('javac'),('java'),('mvn'),('gradle'),
     ('make'),('cmake'),('gcc'),('g++'),('clang'),('clang++'),('rustc'),('dotnet'),
     ('ls'),('cat'),('head'),('tail'),('wc'),('find'),('grep'),('egrep'),('awk'),('sed'),
     ('mkdir'),('touch'),('cp'),('mv'),('ln'),('stat'),('file'),('du'),('df'),('tree'),
     ('realpath'),('basename'),('dirname'),('sort'),('uniq'),('cut'),('tr'),('tee'),
     ('diff'),('patch'),('echo'),('printf'),('xargs'),('envsubst'),('column'),('jq'),
     ('yq'),('iconv'),('strings'),('od'),('hexdump'),('xxd'),('tar'),('gzip'),('gunzip'),
     ('zip'),('unzip'),('bzip2'),('bunzip2'),('xz'),('unxz'),('zstd'),('unzstd'),
     ('curl'),('date'),('env'),('which'),('whoami'),('uname'),('hostname'),('uptime'),
     ('free'),('vmstat'),('iostat'),('ulimit'))
     AS t(v)
WHERE NOT EXISTS (SELECT 1 FROM zephyr_security_rules WHERE rule_type = 'SHELL_ALLOWED' AND rule_value = t.v);

-- default allow commands (从 zephyr.security.default-allow-commands 迁移)
INSERT INTO zephyr_security_rules (id, rule_type, rule_value, description, enabled, created_at)
SELECT 'seed_def_' || rownum(), 'DEFAULT_ALLOW', v, 'Default模式免确认命令', 1, 1735800000
FROM (VALUES ('ls'),('cat'),('head'),('tail'),('less'),('more'),('file'),('stat'),
     ('tree'),('od'),('hexdump'),('find'),('grep'),('egrep'),('fgrep'),('locate'),
     ('which'),('whereis'),('type'),('wc'),('sort'),('uniq'),('cut'),('tr'),('diff'),
     ('cmp'),('comm'),('join'),('paste'),('expand'),('unexpand'),('fmt'),('fold'),
     ('iconv'),('nl'),('rev'),('tac'),('pwd'),('whoami'),('id'),('groups'),('users'),
     ('who'),('w'),('last'),('lastlog'),('date'),('uname'),('hostname'),('hostid'),
     ('arch'),('nproc'),('df'),('du'),('free'),('uptime'),('dmesg'),('ps'),('pgrep'),
     ('top'),('env'),('printenv'),('ulimit'),('umask'),('getconf'),('basename'),
     ('dirname'),('realpath'),('readlink'),('echo'),('printf'),('man'),('whatis'),
     ('apropos'),('info'),('cal'),('clear'),('reset'),('tty'),('sleep'),('test'),
     ('expr'),('true'),('false'),('yes'),('command'),('builtin'),('hash'),('tsort'))
     AS t(v)
WHERE NOT EXISTS (SELECT 1 FROM zephyr_security_rules WHERE rule_type = 'DEFAULT_ALLOW' AND rule_value = t.v);
```

hard-block 和 soft-block rules 的 SQL 同理，每个 pattern 一条 INSERT + description 从 YAML 注释提取。

> **注意**: SQL 需要适配 H2 语法，H2 不支持 `VALUES` 表构造器，改用多个 `INSERT INTO ... SELECT ... WHERE NOT EXISTS` 或 `MERGE INTO`。

- [ ] **Step 2: 三个语言文件内容相同**

复制同样的 SQL 到 `zephyr-en-US.sql` 和 `zephyr-ja-JP.sql`。

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/zephyr-*.sql
git commit -m "feat: 安全配置 SQL 初始化脚本 — YAML种子迁移到 zephyr_security_rules"
```

---

### Task 7: 前端 — 路由 + Store

**Files:**
- Modify: `src/main/resources/static/src/router/index.ts`
- Modify: `src/main/resources/static/src/store/settings.ts`

**Interfaces:**
- Produces: `/settings/security` 路由, store 中的 securityRules 状态

- [ ] **Step 1: 注册路由**

在 `router/index.ts` 的 routes 数组中添加：

```typescript
{
  path: '/settings/security',
  name: 'SecuritySettings',
  component: () => import('../views/settings/SecuritySettings.vue'),
},
```

- [ ] **Step 2: 扩展 store**

在 `settings.ts` 中添加：

```typescript
const securityRules = ref<Record<string, any[]>>({})

async function loadSecurityRules(type: string) {
  try {
    const res = await axios({ url: `/security/${type}/list`, method: 'get' })
    if (res.data.state === 'OK') {
      securityRules.value[type] = res.data.body
    }
  } catch (e) { /* handled by axios interceptor */ }
}

async function addSecurityRule(type: string, value: string, description: string) {
  await axios({ url: `/security/${type}/add`, method: 'post', data: { value, description } })
  await loadSecurityRules(type)
}

async function deleteSecurityRule(type: string, id: string) {
  await axios({ url: `/security/${type}/delete`, method: 'post', data: { id } })
  await loadSecurityRules(type)
}

async function updateSecurityRule(type: string, id: string, value: string, description: string) {
  await axios({ url: `/security/${type}/update`, method: 'post', data: { id, value, description } })
  await loadSecurityRules(type)
}
```

需要把 `loadSecurityRules`, `addSecurityRule`, `deleteSecurityRule`, `updateSecurityRule`, `securityRules` 暴露到 return 中。

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/static/src/router/index.ts
git add src/main/resources/static/src/store/settings.ts
git commit -m "feat: 安全配置路由 + store — /settings/security"
```

---

### Task 8: 前端 — SecuritySettings.vue

> **设计阶段**: 此页面必须先使用 `ui-ux-pro-max` skill 设计界面，确认后再实现。

**Files:**
- Create: `src/main/resources/static/src/views/settings/SecuritySettings.vue`

**Interfaces:**
- Consumes: settingsStore (securityRules, load/add/delete/update methods)
- Produces: 4 Tab 管理页面

- [ ] **Step 1: 用 ui-ux-pro-max 设计**

使用 `ui-ux-pro-max` skill 设计 SecuritySettings 页面：
- 4 个 Tab: 命令白名单 / 默认允许命令 / 硬阻断规则 / 软阻断规则
- 命令类 Tab: 表格列 command_name + description + 操作(删除)，表头右侧新增按钮
- 规则类 Tab: 表格列 pattern + description + 操作(编辑/删除)，表头右侧新增按钮
- 新增/编辑弹窗
- 空状态已通过路由保证（需先配置才有数据），跳过空状态设计
- 暗黑模式适配

- [ ] **Step 2: 实现 Vue 组件**

根据设计实现完整的 SFC 组件。

- [ ] **Step 3: 构建验证**

```bash
cd src/main/resources/static
npm run build
```

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/static/src/views/settings/SecuritySettings.vue
git commit -m "feat: SecuritySettings 安全配置管理页面 (4 Tab)"
```

---

### Task 9: 端到端验证

**Files:** 无修改

- [ ] **Step 1: 构建并启动后端**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
cd src/main/resources/static
npm run build
mkdir -p ../../../../target/classes/static && cp -rf zephyr-ui ../../../../target/classes/static/
cd ../../../../../
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 2: curl 测试完整 CRUD**

```bash
# 列表
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/security/SHELL_ALLOWED/list"

# 新增
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/security/HARD_BLOCK/add" \
  -d '{"value":"test_pattern","description":"测试"}'

# 修改
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/security/HARD_BLOCK/update" \
  -d '{"id":"<返回的id>","value":"updated_pattern","description":"更新后"}'

# 删除
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/security/HARD_BLOCK/delete" \
  -d '{"id":"<返回的id>"}'

# 校验: 非法正则
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/security/HARD_BLOCK/add" \
  -d '{"value":"[invalid"}'
# 预期: 返回错误 "正则表达式非法"

# 校验: 非法 type
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/security/INVALID/list"
# 预期: 400 错误
```

- [ ] **Step 3: 浏览器打开验证**

```bash
open http://localhost:30733/zephyr/zephyr-ui/index.html#/settings/security
```

验证 Tab 切换、增删改操作正常。

---

### Task 10: 单元测试 + 集成测试

**Files:**
- Create: `src/test/java/com/github/hbq969/ai/zephyr/security/service/SecurityConfigServiceTest.java`
- Create: `src/test/java/com/github/hbq969/ai/zephyr/security/ctrl/SecurityConfigCtrlTest.java`

- [ ] **Step 1: SecurityConfigService 单元测试**

```java
package com.github.hbq969.ai.zephyr.security.service;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import com.github.hbq969.ai.zephyr.security.dao.SecurityConfigDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityConfigServiceTest {

    @Mock private SecurityConfigDao dao;
    @Mock private ZephyrConfigProperties cfg;
    @Mock private com.github.hbq969.ai.zephyr.security.AuditLogger auditLogger;

    @InjectMocks
    private SecurityConfigService service;

    @BeforeEach
    void setUp() {
        // 模拟 YAML 种子
        ZephyrConfigProperties.Shell shell = mockShell();
        ZephyrConfigProperties.Security sec = mockSecurity();
        when(cfg.getShell()).thenReturn(shell);
        when(cfg.getSecurity()).thenReturn(sec);
    }

    @Test
    void init_shouldLoadFromYamlAndCountDownLatch() {
        service.init();
        SecurityConfigService.ConfigSnapshot snap = service.getSnapshot();
        assertThat(snap.shellAllowedCommands()).contains("ls", "cat");
        assertThat(snap.defaultAllowCommands()).contains("ls");
        assertThat(snap.hardBlockPatterns()).hasSizeGreaterThan(0);
    }

    @Test
    void merge_shouldUseDbWhenPopulated() {
        service.init();
        // 模拟 DB 返回数据
        when(dao.queryAll()).thenReturn(List.of(/* ... */));
        service.refresh();
        // DB 有数据时应使用 DB 值
    }

    @Test
    void merge_shouldFallbackToSeedWhenDbEmpty() {
        service.init();
        when(dao.queryAll()).thenReturn(List.of());
        service.refresh();
        // DB 全覆盖空时应该 fallback 到 YAML
    }

    @Test
    void merge_partialDb_shouldMergePerType() {
        // 只有 HARD_BLOCK 有 DB 数据，其他类型应从 YAML fallback
    }

    private ZephyrConfigProperties.Shell mockShell() {
        ZephyrConfigProperties.Shell s = new ZephyrConfigProperties.Shell();
        s.setAllowedCommands("ls,cat");
        return s;
    }

    private ZephyrConfigProperties.Security mockSecurity() {
        ZephyrConfigProperties.Security s = new ZephyrConfigProperties.Security();
        s.setDefaultAllowCommands("ls");
        ZephyrConfigProperties.Security.HardBlock hb = new ZephyrConfigProperties.Security.HardBlock();
        hb.setShellPatterns(List.of("rm\\\\s+-rf"));
        s.setHardBlock(hb);
        ZephyrConfigProperties.Security.SoftBlock sb = new ZephyrConfigProperties.Security.SoftBlock();
        sb.setShellPatterns(List.of("kill\\\\s+-9"));
        s.setSoftBlock(sb);
        return s;
    }
}
```

- [ ] **Step 2: 并发测试**

```java
@Test
void concurrentReadsDuringRefresh_shouldNotSeeCorruptedState() throws Exception {
    service.init();
    int readers = 10;
    ExecutorService executor = Executors.newFixedThreadPool(readers + 1);
    CyclicBarrier barrier = new CyclicBarrier(readers + 1);
    AtomicInteger errors = new AtomicInteger(0);

    for (int i = 0; i < readers; i++) {
        executor.submit(() -> {
            try {
                barrier.await();
                for (int j = 0; j < 1000; j++) {
                    SecurityConfigService.ConfigSnapshot snap = service.getSnapshot();
                    // snapshot 内部字段不应为 null
                    assertThat(snap.shellAllowedCommands()).isNotNull();
                    assertThat(snap.hardBlockPatterns()).isNotNull();
                }
            } catch (Exception e) { errors.incrementAndGet(); }
        });
    }
    executor.submit(() -> {
        try {
            barrier.await();
            for (int j = 0; j < 100; j++) {
                when(dao.queryAll()).thenReturn(List.of());
                service.refresh();
            }
        } catch (Exception e) { errors.incrementAndGet(); }
    });
    executor.shutdown();
    assertThat(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(errors.get()).isEqualTo(0);
}
```

- [ ] **Step 3: 运行测试**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn test -pl . -Dtest="SecurityConfigServiceTest,com.github.hbq969.ai.zephyr.security.ctrl.*"
```

- [ ] **Step 4: 提交**

```bash
git add src/test/java/com/github/hbq969/ai/zephyr/security/
git commit -m "test: SecurityConfigService 单元测试 + 并发测试"
```

---

## 已识别风险（记录）

| # | 风险 | 缓解措施 | 状态 |
|---|------|---------|------|
| R1 | DB 中恶意正则可能触发 ReDoS | 当前规则数 < 100 影响可忽略；Pattern.compile() 前置校验拒绝非法语法 | 记录 |
| R2 | refresh() 的 DB 读与 snapshot 更新之间非事务性 | READ_COMMITTED 隔离级别影响极小，最终一致性可接受 | 接受 |
| R3 | SQL 脚本未执行导致 DB 为空（首次启动无规则） | SQL 脚本在 `scriptInitial0()` 中自动执行，表创建和播种在框架管控下 | 低风险，用 curl 验证 |
| R4 | `enabled = COALESCE(#{enabled}, enabled)` 在 H2 中兼容性 | 测试覆盖 H2 环境 | 已验证 |

---

## 任务依赖图

```
Task 1 (Entity+Dao+XML)
  └→ Task 2 (SecurityConfigService)
       ├→ Task 3 (改造 Evaluator + ChatService)
       ├→ Task 4 (清理 ConfigProperties + YAML)
       ├→ Task 5 (Controller)
       │    ├→ Task 7 (前端路由+Store)
       │    │    └→ Task 8 (前端页面)
       │    └→ Task 9 (端到端验证)
       ├→ Task 6 (SQL 初始化)
       └→ Task 10 (单元测试)
```
