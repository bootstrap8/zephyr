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
import org.springframework.web.multipart.MultipartFile;

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

    @Operation(summary = "上传文档")
    @RequestMapping(path = "/doc/upload", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "doc_upload", apiDesc = "知识库管理_上传文档")
    public ReturnMessage<?> uploadDoc(@RequestParam("file") MultipartFile file, @RequestParam String kbId) {
        return ReturnMessage.success(Map.of("docId", knowledgeService.uploadDoc(kbId, file, userName())));
    }

    @Operation(summary = "重新解析文档")
    @RequestMapping(path = "/doc/re-parse", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "doc_reparse", apiDesc = "知识库管理_重新解析")
    public ReturnMessage<?> reParseDoc(@RequestBody Map<String, String> body) {
        knowledgeService.reParseDoc(body.get("id"), body.get("kbId"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "创建内联文档")
    @RequestMapping(path = "/doc/create-inline", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_doc_create_inline", apiDesc = "知识库管理_创建内联文档")
    public ReturnMessage<?> createInlineDoc(@RequestBody Map<String, String> body) {
        String docId = knowledgeService.createInlineDoc(
                body.get("kbId"), body.get("title"), body.get("content"), userName());
        return ReturnMessage.success(Map.of("docId", docId));
    }

    @Operation(summary = "更新内联文档")
    @RequestMapping(path = "/doc/update-inline", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_doc_update_inline", apiDesc = "知识库管理_更新内联文档")
    public ReturnMessage<?> updateInlineDoc(@RequestBody Map<String, String> body) {
        knowledgeService.updateInlineDoc(body.get("id"), body.get("title"), body.get("content"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "召回测试")
    @RequestMapping(path = "/kb/{kbId}/recall-test", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "knowledge_recall_test", apiDesc = "知识库管理_召回测试")
    public ReturnMessage<?> recallTest(@PathVariable String kbId, @RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        int topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 5;
        return ReturnMessage.success(knowledgeService.search(query, List.of(kbId), topK));
    }
}
