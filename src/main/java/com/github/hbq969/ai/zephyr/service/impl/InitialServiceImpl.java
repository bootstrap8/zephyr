package com.github.hbq969.ai.zephyr.service.impl;

import com.github.hbq969.code.common.initial.AbstractScriptInitialAware;
import com.github.hbq969.code.common.spring.context.SpringContext;
import com.github.hbq969.code.common.spring.i18n.LangInfo;
import com.github.hbq969.code.common.spring.i18n.LanguageEvent;
import com.github.hbq969.code.common.utils.InitScriptUtils;
import com.github.hbq969.code.sm.login.service.LoginService;
import com.github.hbq969.code.sm.login.utils.I18nUtils;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class InitialServiceImpl extends AbstractScriptInitialAware {

    @Resource
    private SpringContext context;

    @Resource
    private LoginService loginService;

    @Override
    public String nameOfScriptInitialAware() {
        return context.getProperty("spring.application.name");
    }

    @Resource
    private com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao modelConfigDao;

    @Resource
    private com.github.hbq969.ai.zephyr.mcp.dao.McpDao mcpDao;

    @Resource
    private com.github.hbq969.ai.zephyr.skill.dao.SkillDao skillDao;

    @Override
    protected void tableCreate0() {
        com.github.hbq969.code.common.utils.ThrowUtils.call("model_configs",
                () -> modelConfigDao.createModelConfigsTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("mcp_servers",
                () -> mcpDao.createMcpServersTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("mcp_tools",
                () -> mcpDao.createMcpToolsTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("skill_configs",
                () -> skillDao.createSkillConfigsTable());
    }

    @Override
    protected void scriptInitial0() {
        String lang = I18nUtils.getFullLanguage(context);
        String filename = String.join("", "zephyr-", lang, ".sql");
        InitScriptUtils.initial(context, filename, StandardCharsets.UTF_8, null,
                () -> loginService.loadSMInfo());
    }

    @Override
    public void onApplicationEvent(LanguageEvent event) {
        LangInfo info = (LangInfo) event.getSource();
        String filename = info.filename("zephyr", "sql");
        InitScriptUtils.initial(context, filename, StandardCharsets.UTF_8, null,
                () -> loginService.loadSMInfo());
    }
}
