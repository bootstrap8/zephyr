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

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

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

    @Resource
    private com.github.hbq969.ai.zephyr.chat.dao.ChatDao chatDao;

    @Resource
    private com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao workspaceDao;

    @Resource
    private com.github.hbq969.ai.zephyr.knowledge.dao.KnowledgeDao knowledgeDao;

    @Resource
    private com.github.hbq969.ai.zephyr.config.dao.UserModelPreferenceDao userModelPreferenceDao;

    @Resource
    private com.github.hbq969.ai.zephyr.mcp.service.McpService mcpService;

    @Override
    protected void tableCreate0() {
        com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_model_configs",
                () -> modelConfigDao.createModelConfigsTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_mcp_servers",
                () -> mcpDao.createMcpServersTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_mcp_tools",
                () -> mcpDao.createMcpToolsTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_skill_configs",
                () -> skillDao.createSkillConfigsTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_conversations",
                () -> chatDao.createConversationsTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_messages",
                () -> chatDao.createMessagesTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_workspaces",
                () -> workspaceDao.createWorkspacesTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_knowledge_base",
                () -> knowledgeDao.createKnowledgeBaseTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_knowledge_doc",
                () -> knowledgeDao.createKnowledgeDocTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_conversation_kb",
                () -> knowledgeDao.createConversationKbTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_user_model_prefs",
                () -> userModelPreferenceDao.createUserModelPrefsTable());

        // 建表完成后重连之前处于 connected 状态的 MCP 服务器
        asyncScriptInitialDone(MCP_DISCOVER_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS, () -> {
            mcpService.reconnectOnStartup();
        });
    }

    @Override
    protected void scriptInitial0() {
        String lang = I18nUtils.getFullLanguage(context);
        String filename = String.join("", "zephyr-", lang, EXT_SQL);
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
