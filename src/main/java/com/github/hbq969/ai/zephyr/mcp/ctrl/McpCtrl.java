package com.github.hbq969.ai.zephyr.mcp.ctrl;

import com.github.hbq969.ai.zephyr.mcp.service.McpService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.sm.login.session.UserContext;
import com.github.hbq969.code.sm.perm.api.SMRequiresPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "MCP管理")
@RestController
@RequestMapping(path = "/zephyr-ui/mcp")
public class McpCtrl {

    @Resource
    private McpService mcpService;

    private String userName() {
        com.github.hbq969.code.common.spring.context.UserInfo ui = UserContext.getNoCheck();
        return ui != null ? ui.getUserName() : "admin";
    }

    @Operation(summary = "MCP服务器列表")
    @RequestMapping(path = "/server/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "mcp_listServers", apiDesc = "MCP管理_MCP服务器列表")
    public ReturnMessage<?> listServers() {
        return ReturnMessage.success(mcpService.listServers(userName()));
    }

    @Operation(summary = "新增MCP服务器")
    @RequestMapping(path = "/server/create", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "mcp_createServer", apiDesc = "MCP管理_新增MCP服务器")
    public ReturnMessage<?> createServer(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(mcpService.createServer(body, userName()));
    }

    @Operation(summary = "修改MCP服务器")
    @RequestMapping(path = "/server/update", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "mcp_updateServer", apiDesc = "MCP管理_修改MCP服务器")
    public ReturnMessage<?> updateServer(@RequestBody Map<String, String> body) {
        mcpService.updateServer(body, userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除MCP服务器")
    @RequestMapping(path = "/server/delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "mcp_deleteServer", apiDesc = "MCP管理_删除MCP服务器")
    public ReturnMessage<?> deleteServer(@RequestBody Map<String, String> body) {
        mcpService.deleteServer(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "连接MCP服务器")
    @RequestMapping(path = "/server/connect", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "mcp_connect", apiDesc = "MCP管理_连接MCP服务器")
    public ReturnMessage<?> connect(@RequestBody Map<String, String> body) {
        mcpService.connect(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "断开MCP服务器")
    @RequestMapping(path = "/server/disconnect", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "mcp_disconnect", apiDesc = "MCP管理_断开MCP服务器")
    public ReturnMessage<?> disconnect(@RequestBody Map<String, String> body) {
        mcpService.disconnect(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "MCP工具列表")
    @RequestMapping(path = "/tool/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "mcp_listTools", apiDesc = "MCP管理_MCP工具列表")
    public ReturnMessage<?> listTools(@RequestParam("serverId") String serverId) {
        return ReturnMessage.success(mcpService.listTools(serverId, userName()));
    }

    @Operation(summary = "手动添加MCP工具")
    @RequestMapping(path = "/tool/create", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "mcp_createTool", apiDesc = "MCP管理_手动添加MCP工具")
    public ReturnMessage<?> createTool(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(mcpService.createTool(body, userName()));
    }

    @Operation(summary = "修改MCP工具")
    @RequestMapping(path = "/tool/update", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "mcp_updateTool", apiDesc = "MCP管理_修改MCP工具")
    public ReturnMessage<?> updateTool(@RequestBody Map<String, String> body) {
        mcpService.updateTool(body, userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除MCP工具")
    @RequestMapping(path = "/tool/delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "mcp_deleteTool", apiDesc = "MCP管理_删除MCP工具")
    public ReturnMessage<?> deleteTool(@RequestBody Map<String, String> body) {
        mcpService.deleteTool(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "启用/禁用MCP工具")
    @RequestMapping(path = "/tool/toggle", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "mcp_toggleTool", apiDesc = "MCP管理_启用/禁用MCP工具")
    public ReturnMessage<?> toggleTool(@RequestBody Map<String, String> body) {
        mcpService.toggleTool(body.get("id"), Integer.parseInt(body.get("enabled")), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "已启用工具数")
    @RequestMapping(path = "/tool/count", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "mcp_countEnabledTools", apiDesc = "MCP管理_已启用工具数")
    public ReturnMessage<?> countEnabledTools() {
        return ReturnMessage.success(mcpService.countEnabledTools(userName()));
    }
}