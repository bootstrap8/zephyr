package com.github.hbq969.ai.zephyr.workspace.ctrl;

import com.github.hbq969.ai.zephyr.workspace.service.WorkspaceService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.common.spring.context.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import com.github.hbq969.code.sm.perm.api.SMRequiresPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "工作空间接口")
@RestController
@RequestMapping(path = "/zephyr-ui/workspace")
public class WorkspaceCtrl {

    @Resource
    private WorkspaceService workspaceService;

    private String userName() {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null ? ui.getUserName() : "admin";
    }

    @Operation(summary = "获取工作空间列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "workspace_list", apiDesc = "工作空间_获取列表")
    public ReturnMessage<?> list() {
        return ReturnMessage.success(workspaceService.list(userName()));
    }

    @Operation(summary = "新建工作空间")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "workspace_create", apiDesc = "工作空间_新建")
    public ReturnMessage<?> create(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(workspaceService.create(body, userName()));
    }

    @Operation(summary = "删除工作空间")
    @RequestMapping(path = "/delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "workspace_delete", apiDesc = "工作空间_删除")
    public ReturnMessage<?> delete(@RequestBody Map<String, String> body) {
        workspaceService.delete(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "浏览目录")
    @RequestMapping(path = "/browse", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "workspace_browse", apiDesc = "工作空间_浏览目录")
    public ReturnMessage<?> browse(@RequestParam(required = false) String parent) {
        return ReturnMessage.success(workspaceService.browse(parent));
    }

    @Operation(summary = "创建目录")
    @RequestMapping(path = "/mkdir", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "workspace_mkdir", apiDesc = "工作空间_创建目录")
    public ReturnMessage<?> mkdir(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(workspaceService.mkdir(body.get("parent"), body.get("name")));
    }
}
