package com.github.hbq969.ai.zephyr.chat.ctrl;

import com.github.hbq969.ai.zephyr.chat.model.ChatRequest;
import com.github.hbq969.ai.zephyr.chat.service.ChatService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.common.spring.context.UserInfo;
import com.github.hbq969.code.common.utils.GsonUtils;
import com.github.hbq969.code.sm.login.session.UserContext;
import com.github.hbq969.code.sm.perm.api.SMRequiresPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Tag(name = "聊天接口")
@RestController
@RequestMapping(path = "/zephyr-ui/chat")
public class ChatCtrl {

    @Resource
    private ChatService chatService;

    private String userName() {
        UserInfo ui = UserContext.getNoCheck();
        log.info("++++ 会话信息: {}", ui == null ? "无" : ui.getUserName());
        return ui != null ? ui.getUserName() : "admin";
    }

    @Operation(summary = "发送消息（SSE 流式）")
    @RequestMapping(path = "/send", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "chat_sendMessage", apiDesc = "聊天接口_发送消息（SSE流式）")
    public SseEmitter sendMessage(@RequestBody ChatRequest body) {
        return chatService.send(
                userName(),
                body.getConversationId(),
                body.getMessage()
        );
    }

    @Operation(summary = "取消当前对话")
    @RequestMapping(path = "/cancel", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "chat_cancel", apiDesc = "聊天接口_取消当前对话")
    public ReturnMessage<?> cancel() {
        chatService.cancel(userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "上下文占比")
    @RequestMapping(path = "/context-usage", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "chat_contextUsage", apiDesc = "聊天接口_上下文占比")
    public ReturnMessage<?> contextUsage(@RequestParam(required = false) String conversationId) {
        return ReturnMessage.success(chatService.contextUsage(
                userName(), conversationId));
    }
}