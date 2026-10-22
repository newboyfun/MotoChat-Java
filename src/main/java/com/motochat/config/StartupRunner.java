package com.motochat.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.motochat.entity.User;
import com.motochat.mapper.UserMapper;
import com.motochat.service.PersonaState;
import com.motochat.service.TtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 启动初始化：恢复上次使用的角色、清理过期语音文件、检查默认管理员密码。
 * 对应 Python 版 server.py 的 lifespan 里那几件事。
 */
@Component
public class StartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    /** schema.sql 里的初始管理员哈希。启动时比对，未修改则大声告警 */
    private static final String DEFAULT_ADMIN_HASH =
            "$pbkdf2-sha256$310000$a1b2c3d4e5f60718293a4b5c6d7e8f90$824de8b696405246d9d702d69045f391205ff1db14cc335b7775e2bda43ac641";

    private final PersonaState personaState;
    private final TtsService ttsService;
    private final UserMapper userMapper;

    public StartupRunner(PersonaState personaState, TtsService ttsService, UserMapper userMapper) {
        this.personaState = personaState;
        this.ttsService = ttsService;
        this.userMapper = userMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        warnIfDefaultAdminPassword();
        try {
            personaState.restoreFromConfig();
        } catch (Exception e) {
            log.warn("恢复角色失败: {}", e.getMessage());
        }
        try {
            int cleaned = ttsService.cleanupOld(24);
            if (cleaned > 0) {
                log.info("启动时清理了 {} 个过期语音文件", cleaned);
            }
        } catch (Exception e) {
            log.debug("启动清理失败: {}", e.getMessage());
        }
        log.info("MotoChat 启动完成，当前角色: {}", personaState.name());
    }

    /** 默认口令（admin/admin123）还挂在库里时，每次启动都吼一声 */
    private void warnIfDefaultAdminPassword() {
        try {
            User admin = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                    .eq(User::getRole, "admin").last("LIMIT 1"));
            if (admin != null && DEFAULT_ADMIN_HASH.equals(admin.getPasswordHash())) {
                log.warn("########################################################");
                log.warn("# 管理员仍在使用 schema.sql 的默认密码（admin/admin123）！#");
                log.warn("# 请立即登录管理端修改密码，否则任何人都能进入管理端。      #");
                log.warn("########################################################");
            }
        } catch (Exception e) {
            log.debug("默认密码检查失败: {}", e.getMessage());
        }
    }

    /** 每小时清理一次过期语音文件，防止 voices 目录无限增长 */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void cleanupVoices() {
        try {
            ttsService.cleanupOld(24);
        } catch (Exception e) {
            log.debug("定期清理语音失败: {}", e.getMessage());
        }
    }
}
