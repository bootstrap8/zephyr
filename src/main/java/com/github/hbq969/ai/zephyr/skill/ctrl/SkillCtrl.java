package com.github.hbq969.ai.zephyr.skill.ctrl;

import com.github.hbq969.ai.zephyr.skill.service.SkillService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.sm.login.session.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Tag(name = "Skill管理")
@RestController
@RequestMapping(path = "/zephyr-ui/skill")
public class SkillCtrl {

    @Resource
    private SkillService skillService;

    private String userName() {
        com.github.hbq969.code.common.spring.context.UserInfo ui = UserContext.getNoCheck();
        return ui != null ? ui.getUserName() : "admin";
    }

    @Operation(summary = "已安装Skill列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> list() {
        return ReturnMessage.success(skillService.list(userName()));
    }

    @Operation(summary = "安装Skill")
    @RequestMapping(path = "/install", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> install(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(skillService.install(body, userName()));
    }

    @Operation(summary = "上传压缩包安装Skill")
    @RequestMapping(path = "/upload", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> upload(@RequestParam("file") MultipartFile file) {
        return ReturnMessage.success(skillService.upload(file, userName()));
    }

    @Operation(summary = "扫描本地平台可同步的Skill")
    @RequestMapping(path = "/sync-scan", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> syncScan() {
        return ReturnMessage.success(skillService.syncScan(userName()));
    }

    @Operation(summary = "执行平台同步安装")
    @RequestMapping(path = "/sync-install", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> syncInstall(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(skillService.syncInstall(body, userName()));
    }

    @Operation(summary = "启用/禁用Skill")
    @RequestMapping(path = "/toggle", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> toggle(@RequestBody Map<String, String> body) {
        skillService.toggle(body.get("id"), Integer.parseInt(body.get("enabled")), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "卸载Skill")
    @RequestMapping(path = "/uninstall", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> uninstall(@RequestBody Map<String, String> body) {
        skillService.uninstall(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }
}
