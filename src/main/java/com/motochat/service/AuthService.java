package com.motochat.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.motochat.common.ApiException;
import com.motochat.dto.ChangePasswordRequest;
import com.motochat.dto.LoginRequest;
import com.motochat.entity.User;
import com.motochat.mapper.UserMapper;
import com.motochat.security.LegacyPbkdf2Encoder;
import com.motochat.security.LoginRateLimiter;
import com.motochat.security.SessionStore;
import com.motochat.security.SessionUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证业务。对应 Python 版 api/auth.py。
 *
 * <p>与原版的差异只有一处：用户从 config.json 换成了 users 表，
 * 其余校验顺序、错误文案、状态码全部保持一致。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * 预生成的 dummy 哈希。用户不存在时也对它跑一次 matches，
     * 抹平"用户不存在不跑 PBKDF2"造成的响应时间差，防止按响应耗时枚举有效用户名。
     * 静态初始化只付一次 310000 轮迭代的成本。
     */
    private static final String DUMMY_HASH = new LegacyPbkdf2Encoder().encode("timing-equalizer");

    private final UserMapper userMapper;
    private final LegacyPbkdf2Encoder passwordEncoder;
    private final SessionStore sessionStore;
    private final LoginRateLimiter rateLimiter;
    private final ConfigService configService;

    public AuthService(UserMapper userMapper,
                       LegacyPbkdf2Encoder passwordEncoder,
                       SessionStore sessionStore,
                       LoginRateLimiter rateLimiter,
                       ConfigService configService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.sessionStore = sessionStore;
        this.rateLimiter = rateLimiter;
        this.configService = configService;
    }

    public User findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    public SessionUser toSessionUser(User user) {
        return new SessionUser(user.getUsername(), user.getRole(),
                user.getPersonaName(), user.getConversationId());
    }

    /**
     * 登录。返回 (token, 用户)。
     * 错误文案与原版一致：不区分"用户不存在"和"密码错误"，避免账号枚举。
     */
    public LoginResult login(LoginRequest req, String ip) {
        if (!rateLimiter.allow(ip)) {
            throw new ApiException(429, "请求过于频繁，请稍后再试");
        }
        User user = findByUsername(req.username());
        boolean ok = user != null
                && user.getPasswordHash() != null && !user.getPasswordHash().isBlank()
                && passwordEncoder.matches(req.password(), user.getPasswordHash());
        if (!ok) {
            if (user == null) {
                // 用户不存在时也跑一次 PBKDF2，避免响应过快泄露"账号不存在"
                passwordEncoder.matches(req.password(), DUMMY_HASH);
            }
            rateLimiter.recordFailure(ip);
            throw ApiException.unauthorized("账号或密码错误");
        }
        rateLimiter.clear(ip);
        String token = sessionStore.create(toSessionUser(user));
        log.info("用户登录成功: {} ({})", user.getUsername(), ip);
        return new LoginResult(token, user);
    }

    public void logout(String token) {
        sessionStore.remove(token);
    }

    /** 校验当前登录用户的密码，用于敏感操作前的二次确认 */
    public void verifyPassword(SessionUser current, String rawPassword) {
        User user = findByUsername(current.username());
        if (user == null || user.getPasswordHash() == null) {
            throw ApiException.forbidden("管理员密码未设置");
        }
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw ApiException.unauthorized("旧密码不正确");
        }
    }

    /** 读取指定类型的 API Key（仅管理员，且需先通过密码校验） */
    public String revealKey(String keyType) {
        String path = switch (keyType == null ? "llm" : keyType) {
            case "vision" -> "vision.api_key";
            case "tts" -> "tts.api_key";
            default -> "llm.api_key";
        };
        return configService.getString(path, "");
    }

    /**
     * 自助改密。成功后踢掉该用户的其他设备会话（含被盗会话），保留当前设备登录态。
     *
     * @param token 当前会话 token，用于豁免自己；为空时退化为清除全部会话
     */
    @Transactional
    public void changePassword(SessionUser current, String token, ChangePasswordRequest req) {
        if (req.newPassword() == null || req.newPassword().length() < 4) {
            throw ApiException.badRequest("新密码至少 4 位");
        }
        User user = findByUsername(current.username());
        if (user == null) {
            throw ApiException.notFound("用户不存在");
        }
        if (!passwordEncoder.matches(req.oldPassword(), user.getPasswordHash())) {
            throw ApiException.unauthorized("旧密码不正确");
        }
        User update = new User();
        update.setId(user.getId());
        update.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        try {
            userMapper.updateById(update);
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "用户名冲突");
        }
        // 改密后让其他设备重新登录：疑似账号泄露时改密，旧会话必须失效
        sessionStore.removeByUsernameExcept(current.username(), token);
        log.info("用户修改密码: {}", current.username());
    }

    /** 登录成功后返回给控制器的载荷 */
    public record LoginResult(String token, User user) {
    }
}
