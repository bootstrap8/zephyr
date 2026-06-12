package com.github.hbq969.ai.zephyr.chat.ctrl;

import com.github.hbq969.ai.zephyr.chat.model.ConversationVO;
import com.github.hbq969.ai.zephyr.chat.service.ConversationService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.common.spring.context.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import com.github.hbq969.code.sm.perm.api.SMRequiresPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "会话接口")
@RestController
@RequestMapping(path = "/zephyr-ui/conversations")
public class ConversationCtrl {

    @Resource
    private ConversationService conversationService;

    private String userName() {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null ? ui.getUserName() : "admin";
    }

    @Operation(summary = "获取会话列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "conversations_list", apiDesc = "会话管理_获取会话列表")
    public ReturnMessage<?> list() {
        return ReturnMessage.success(conversationService.list(userName()));
    }

    @Operation(summary = "新建会话")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "conversations_create", apiDesc = "会话管理_新建会话")
    public ReturnMessage<?> create(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(conversationService.create(body, userName()));
    }

    @Operation(summary = "重命名会话")
    @RequestMapping(path = "/rename", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "conversations_rename", apiDesc = "会话管理_重命名会话")
    public ReturnMessage<?> rename(@RequestBody Map<String, String> body) {
        conversationService.rename(body, userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除会话")
    @RequestMapping(path = "/delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "conversations_delete", apiDesc = "会话管理_删除会话")
    public ReturnMessage<?> delete(@RequestBody Map<String, String> body) {
        conversationService.delete(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "更新对话工作空间")
    @RequestMapping(path = "/update-workspace", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "conversations_updateWorkspace", apiDesc = "会话管理_更新对话工作空间")
    public ReturnMessage<?> updateWorkspace(@RequestBody Map<String, String> body) {
        conversationService.updateWorkspace(body.get("conversationId"), body.get("workspaceId"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "获取会话历史消息")
    @RequestMapping(path = "/{id}/messages", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "conversations_messages", apiDesc = "会话管理_获取会话历史消息")
    public ReturnMessage<?> messages(@PathVariable String id) {
        return ReturnMessage.success(conversationService.getMessages(id, userName()));
    }
}