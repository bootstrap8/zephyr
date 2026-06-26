package com.github.hbq969.ai.zephyr.security.ctrl;

import com.github.hbq969.ai.zephyr.security.AuditLogger;
import com.github.hbq969.ai.zephyr.security.dao.SecurityConfigDao;
import com.github.hbq969.ai.zephyr.security.dao.entity.SecurityRuleEntity;
import com.github.hbq969.ai.zephyr.security.service.SecurityConfigService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.common.spring.context.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import com.github.hbq969.code.sm.perm.api.SMRequiresPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value 不能为空");
        }
        if (RULE_TYPE_HARD_BLOCK.equals(type) || RULE_TYPE_SOFT_BLOCK.equals(type)) {
            try {
                Pattern.compile(value, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
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
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public ReturnMessage<?> handleIllegalArgument(IllegalArgumentException e) {
        return ReturnMessage.fail(e.getMessage());
    }

    @Operation(summary = "查询安全配置规则列表")
    @RequestMapping(path = "/{type}/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体",
            apiKey = "security_list", apiDesc = "安全配置_规则列表")
    public ReturnMessage<?> list(@PathVariable String type) {
        validateType(type);
        return ReturnMessage.success(dao.queryAllByType(type));
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
        dao.updateEnabled(e);
        securityConfigService.refresh();
        auditLogger.log("SECURITY_CONFIG", type, body.get("enabled").equals("1") ? "ENABLE" : "DISABLE",
                "规则 id=" + body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "统计各类型安全规则数量")
    @RequestMapping(path = "/stats", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体",
            apiKey = "security_list", apiDesc = "安全配置_规则列表")
    public ReturnMessage<?> stats() {
        java.util.List<Map<String, Object>> rows = dao.queryCounts();
        Map<String, Long> result = new java.util.HashMap<>();
        for (String t : VALID_TYPES) {
            result.put(t, 0L);
        }
        for (Map<String, Object> row : rows) {
            String rt = (String) row.get("ruleType");
            Object cnt = row.get("cnt");
            result.put(rt, cnt instanceof Number ? ((Number) cnt).longValue() : 0L);
        }
        return ReturnMessage.success(result);
    }

    @Operation(summary = "批量删除安全规则")
    @RequestMapping(path = "/{type}/batch-delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体",
            apiKey = "security_delete", apiDesc = "安全配置_删除规则")
    public ReturnMessage<?> batchDelete(@PathVariable String type, @RequestBody Map<String, Object> body) {
        validateType(type);
        @SuppressWarnings("unchecked")
        java.util.List<String> ids = (java.util.List<String>) body.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids 不能为空");
        }
        dao.batchDelete(ids);
        securityConfigService.refresh();
        auditLogger.log("SECURITY_CONFIG", type, "BATCH_DELETE",
                "批量删除规则 count=" + ids.size(), userName());
        return ReturnMessage.success("ok");
    }
}
