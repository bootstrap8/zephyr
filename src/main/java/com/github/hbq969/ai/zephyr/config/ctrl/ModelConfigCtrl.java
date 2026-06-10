package com.github.hbq969.ai.zephyr.config.ctrl;

import com.github.hbq969.ai.zephyr.config.service.ModelConfigService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.common.spring.context.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import com.github.hbq969.code.sm.perm.api.SMRequiresPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "模型配置")
@RestController
@RequestMapping(path = "/zephyr-ui/model-config")
public class ModelConfigCtrl {

    @Resource
    private ModelConfigService modelConfigService;

    private String userName() {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null ? ui.getUserName() : "admin";
    }

    @Operation(summary = "模型列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "modelConfig_list", apiDesc = "模型配置_模型列表")
    public ReturnMessage<?> list() {
        return ReturnMessage.success(modelConfigService.list(userName()));
    }

    @Operation(summary = "新增模型")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "modelConfig_create", apiDesc = "模型配置_新增模型")
    public ReturnMessage<?> create(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(modelConfigService.create(body, userName()));
    }

    @Operation(summary = "修改模型")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "modelConfig_update", apiDesc = "模型配置_修改模型")
    public ReturnMessage<?> update(@RequestBody Map<String, String> body) {
        modelConfigService.update(body, userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除模型")
    @RequestMapping(path = "/delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "modelConfig_delete", apiDesc = "模型配置_删除模型")
    public ReturnMessage<?> delete(@RequestBody Map<String, String> body) {
        modelConfigService.delete(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "设为默认")
    @RequestMapping(path = "/set-default", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "modelConfig_setDefault", apiDesc = "模型配置_设为默认")
    public ReturnMessage<?> setDefault(@RequestBody Map<String, String> body) {
        modelConfigService.setDefault(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "探测上下文大小")
    @RequestMapping(path = "/detect-context", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体接口", apiKey = "modelConfig_detectContext", apiDesc = "模型配置_探测上下文大小")
    public ReturnMessage<?> detectContext(@RequestBody Map<String, String> body) {
        Long result = modelConfigService.detectContext(body, userName());
        if (result != null) return ReturnMessage.success(result);
        return ReturnMessage.fail("探测失败，请手动填写最大上下文");
    }
}