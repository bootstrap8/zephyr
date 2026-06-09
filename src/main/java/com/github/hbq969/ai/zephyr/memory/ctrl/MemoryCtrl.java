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
