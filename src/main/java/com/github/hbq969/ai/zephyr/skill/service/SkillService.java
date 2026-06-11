package com.github.hbq969.ai.zephyr.skill.service;

import com.github.hbq969.ai.zephyr.skill.model.SkillVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface SkillService {

    List<SkillVO> list(String userName);

    List<SkillVO> install(Map<String, String> body, String userName);

    List<SkillVO> upload(MultipartFile file, String userName);

    List<SkillVO> syncScan(String userName);

    List<SkillVO> syncInstall(Map<String, String> body, String userName);

    void toggle(String id, Integer enabled, String userName);

    void uninstall(String id, String userName);

    void batchUninstall(List<String> ids, String userName);
}
