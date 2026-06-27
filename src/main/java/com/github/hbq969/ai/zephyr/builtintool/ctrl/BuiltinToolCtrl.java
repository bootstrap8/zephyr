package com.github.hbq969.ai.zephyr.builtintool.ctrl;

import com.github.hbq969.ai.zephyr.builtintool.service.BuiltinToolService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.sm.login.model.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import com.github.hbq969.code.sm.perm.api.SMRequiresPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "内置工具管控")
@RestController
@RequestMapping(path = "/zephyr-ui/builtin-tool")
public class BuiltinToolCtrl {

    @Resource
    private BuiltinToolService builtinToolService;

    @Operation(summary = "内置工具权限列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "builtinTool_list", apiDesc = "内置工具管控_权限列表")
    public ReturnMessage<?> list() {
        return ReturnMessage.success(builtinToolService.list());
    }

    @Operation(summary = "切换内置工具权限")
    @RequestMapping(path = "/toggle", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "builtinTool_toggle", apiDesc = "内置工具管控_切换权限")
    public ReturnMessage<?> toggle(@RequestBody Map<String, Object> body) {
        UserInfo ui = UserContext.getNoCheck();
        if (ui == null || !ui.isAdmin()) {
            return ReturnMessage.fail("仅管理员可操作");
        }
        String toolName = body.get("toolName").toString();
        int requireAdmin = Integer.parseInt(body.get("requireAdmin").toString());
        builtinToolService.toggle(toolName, requireAdmin);
        return ReturnMessage.success("ok");
    }
}
