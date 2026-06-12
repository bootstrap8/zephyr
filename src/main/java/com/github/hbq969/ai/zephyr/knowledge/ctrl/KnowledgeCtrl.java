package com.github.hbq969.ai.zephyr.knowledge.ctrl;

import com.github.hbq969.ai.zephyr.knowledge.service.KnowledgeService;
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

@Tag(name = "知识库管理")
@RestController
@RequestMapping(path = "/zephyr-ui/knowledge")
public class KnowledgeCtrl {

    @Resource
    private KnowledgeService knowledgeService;

    private String userName() {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null ? ui.getUserName() : "admin";
    }

    @Operation(summary = "知识库列表")
    @RequestMapping(path = "/kb/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_kb_list", apiDesc = "知识库管理_知识库列表")
    public ReturnMessage<?> listKb() {
        return ReturnMessage.success(knowledgeService.listKb(userName()));
    }

    @Operation(summary = "新建知识库")
    @RequestMapping(path = "/kb/create", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_kb_create", apiDesc = "知识库管理_新建知识库")
    public ReturnMessage<?> createKb(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(knowledgeService.createKb(body, userName()));
    }

    @Operation(summary = "修改知识库")
    @RequestMapping(path = "/kb/update", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_kb_update", apiDesc = "知识库管理_修改知识库")
    public ReturnMessage<?> updateKb(@RequestBody Map<String, String> body) {
        knowledgeService.updateKb(body, userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除知识库")
    @RequestMapping(path = "/kb/delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_kb_delete", apiDesc = "知识库管理_删除知识库")
    public ReturnMessage<?> deleteKb(@RequestBody Map<String, String> body) {
        knowledgeService.deleteKb(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "文档列表")
    @RequestMapping(path = "/doc/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_doc_list", apiDesc = "知识库管理_文档列表")
    public ReturnMessage<?> listDocs(@RequestParam String kbId) {
        return ReturnMessage.success(knowledgeService.listDocs(kbId));
    }

    @Operation(summary = "删除文档")
    @RequestMapping(path = "/doc/delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_doc_delete", apiDesc = "知识库管理_删除文档")
    public ReturnMessage<?> deleteDoc(@RequestBody Map<String, String> body) {
        knowledgeService.deleteDoc(body.get("id"));
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "对话关联知识库列表")
    @RequestMapping(path = "/conversation/kb/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_conversation_kb_list", apiDesc = "知识库管理_对话关联知识库列表")
    public ReturnMessage<?> getConversationKbs(@RequestParam String conversationId) {
        return ReturnMessage.success(knowledgeService.getConversationKbIds(conversationId));
    }

    @Operation(summary = "保存对话关联知识库")
    @RequestMapping(path = "/conversation/kb/save", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_conversation_kb_save", apiDesc = "知识库管理_保存对话关联知识库")
    public ReturnMessage<?> saveConversationKbs(@RequestBody Map<String, Object> body) {
        String conversationId = (String) body.get("conversationId");
        @SuppressWarnings("unchecked")
        List<String> kbIds = (List<String>) body.get("kbIds");
        knowledgeService.saveConversationKbIds(conversationId, kbIds);
        return ReturnMessage.success("ok");
    }
}
