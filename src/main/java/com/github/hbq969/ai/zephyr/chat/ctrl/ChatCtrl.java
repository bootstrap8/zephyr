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
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

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
        return ui != null ? ui.getUserName() : DEFAULT_USERNAME;
    }

    @Operation(summary = "发送消息（SSE 流式）")
    @RequestMapping(path = "/send", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "chat_sendMessage", apiDesc = "聊天接口_发送消息（SSE流式）")
    public SseEmitter sendMessage(@RequestBody ChatRequest body) {
        return chatService.send(
                userName(),
                body.getConversationId(),
                body.getWorkspaceId(),
                body.getMessage(),
                body.getMode(),
                body.getFilePaths()
        );
    }

    @Operation(summary = "取消对话")
    @RequestMapping(path = "/cancel", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "chat_cancel", apiDesc = "聊天接口_取消对话")
    public ReturnMessage<?> cancel(@RequestBody(required = false) ChatRequest body) {
        if (body != null && body.getConversationId() != null && !body.getConversationId().isEmpty()) {
            chatService.cancelByConversationId(body.getConversationId());
        } else {
            chatService.cancel(userName());
        }
        return ReturnMessage.success(RESPONSE_SUCCESS);
    }

    @Operation(summary = "获取当前用户信息")
    @SuppressWarnings("serial")
    @RequestMapping(path = "/whoami", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "chat_whoami", apiDesc = "聊天接口_获取当前用户")
    public ReturnMessage<?> whoami() {
        com.github.hbq969.code.sm.login.model.UserInfo ui = UserContext.getNoCheck();
        if (ui == null) {
            return ReturnMessage.success(new java.util.HashMap<String, Object>() {{
                put("username", DEFAULT_USERNAME);
                put("avatar", DEFAULT_AVATAR);
                put("isAdmin", false);
            }});
        }
        String uname = ui.getUserName();
        boolean admin = ui.isAdmin();
        return ReturnMessage.success(new java.util.HashMap<String, Object>() {{
            put("username", uname);
            put("avatar", uname.substring(0, 1).toUpperCase());
            put("isAdmin", admin);
        }});
    }

    @Operation(summary = "上传聊天附件")
    @RequestMapping(path = "/upload", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "chat_upload", apiDesc = "聊天接口_上传附件")
    public ReturnMessage<?> upload(@RequestParam("file") MultipartFile file,
                                   @RequestParam("workspaceId") String workspaceId) {
        try {
            return ReturnMessage.success(chatService.upload(file, workspaceId, userName()));
        } catch (IllegalArgumentException e) {
            return ReturnMessage.fail(e.getMessage());
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return ReturnMessage.fail("上传失败: " + e.getMessage());
        }
    }

    @Operation(summary = "上下文占比")
    @RequestMapping(path = "/context-usage", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "chat_contextUsage", apiDesc = "聊天接口_上下文占比")
    public ReturnMessage<?> contextUsage(@RequestParam(required = false) String conversationId,
                                         @RequestParam(required = false) String mode) {
        return ReturnMessage.success(chatService.contextUsage(
                userName(), conversationId, mode));
    }

    @Operation(summary = "用户确认/拒绝操作")
    @RequestMapping(path = "/confirm", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "chat_confirm", apiDesc = "聊天接口_确认操作")
    public ReturnMessage<?> confirm(@RequestBody Map<String, Object> body) {
        String confirmId = body.get(KEY_CONFIRM_ID).toString();
        String action = body.get(KEY_ACTION).toString(); // "allow" | "deny"

        // 身份校验：confirmId 格式为 "userName:randomId"，校验当前用户是否匹配
        int colonIdx = confirmId.indexOf(':');
        if (colonIdx < 0 || !userName().equals(confirmId.substring(0, colonIdx))) {
            return ReturnMessage.fail("无权操作此确认请求");
        }

        boolean allowed = ACTION_ALLOW.equals(action);
        chatService.confirm(confirmId, allowed);
        return ReturnMessage.success(Map.of(KEY_CONFIRM_ID, confirmId, KEY_ACTION, action));
    }

}