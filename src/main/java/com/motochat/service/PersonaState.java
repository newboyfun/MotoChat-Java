package com.motochat.service;

import com.motochat.common.FileHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 当前激活角色的共享状态，对应 Python 版 ChatService 的 avatar_name / avatar_prompt。
 *
 * <p>原版这些字段挂在 ChatService 上，切换角色时改属性、并把路径写回 config。
 * 这里单独抽出来，是因为人设接口、聊天接口、管理端都要读它，
 * 挂在 ChatService 上会让依赖方向变乱。
 */
@Component
public class PersonaState {

    private static final Logger log = LoggerFactory.getLogger(PersonaState.class);

    private final FileHelper fileHelper;
    private final ConfigService configService;

    private volatile String name = "MONO";
    private volatile String prompt = "";
    private volatile Path dir;

    public PersonaState(FileHelper fileHelper, ConfigService configService) {
        this.fileHelper = fileHelper;
        this.configService = configService;
    }

    /** 启动后由 StartupRunner 调用：按 config 里记录的路径恢复上次使用的角色 */
    public void restoreFromConfig() {
        String configured = configService.getString("behavior.avatar_dir", "");
        Path target = configured.isBlank() ? null : Path.of(configured);
        if (target != null && Files.isDirectory(target)) {
            apply(target);
        } else {
            Path fallback = fileHelper.personaDir("MONO");
            if (Files.isDirectory(fallback)) {
                apply(fallback);
            }
        }
    }

    public synchronized void apply(Path personaDir) {
        this.dir = personaDir.toAbsolutePath().normalize();
        this.name = this.dir.getFileName().toString();
        this.prompt = readPrompt(this.dir);
        log.info("[角色] 当前激活: {}", name);
    }

    /** 当前角色内容被修改后刷新内存中的 prompt */
    public synchronized void refreshPrompt(Path personaDir, String content) {
        if (dir != null && dir.equals(personaDir.toAbsolutePath().normalize())) {
            this.prompt = content;
        }
    }

    private static String readPrompt(Path dir) {
        Path md = dir.resolve("avatar.md");
        try {
            return Files.isRegularFile(md) ? Files.readString(md, java.nio.charset.StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    public String name() {
        return name;
    }

    public String prompt() {
        return prompt;
    }

    public Path dir() {
        return dir;
    }
}
