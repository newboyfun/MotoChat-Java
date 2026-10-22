package com.motochat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motochat.common.FileHelper;
import com.motochat.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 双层记忆：短期上下文（最近 N 轮对话）+ 核心记忆（LLM 摘要），落盘为 JSON。
 * 对应 Python 版 services/memory.py。
 *
 * <p>目录结构 {@code userdata/avatars/{角色}/memory/{用户}/}：
 * <ul>
 *   <li>short_memory.json — [{user, bot, time}, ...]，最多 15 组</li>
 *   <li>core_memory.json — {timestamp, content}</li>
 * </ul>
 *
 * <p>原版用 mtime 做缓存失效判断 + 引用计数清理锁。这里保留 mtime 校验的缓存语义，
 * 锁改成固定分段锁（见 {@link #lockFor}）；记忆文件改为临时文件 + 原子 move 落盘，
 * 崩溃不会再留下半截 JSON。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 锁分段数。必须是 2 的幂（按位与取模） */
    private static final int LOCK_STRIPES = 64;

    private final FileHelper fileHelper;
    private final LlmGateway llmGateway;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 短期/核心记忆的内容缓存。键是「角色:用户:short|core」，值可能是一整段对话列表，
     * 不能无界增长——锁改成分段锁之后，这是最后一处会随用户数增长的 Map，用 Caffeine 封顶。
     * mtime 校验才是缓存有效性的最终依据，淘汰条目只会导致多读一次文件，不影响正确性。
     */
    private final Cache<String, CachedEntry> cache = Caffeine.newBuilder()
            .maximumSize(2048)
            .build();

    /**
     * 固定分段锁。
     *
     * <p>为什么不用 per-key 锁 Map：per-key 方案迟早要回收，而"删掉未被持有的锁"与
     * "另一个线程拿着引用还没 lock()"之间存在竞态窗口——可能出现同一 key 两把锁对象
     * 同时进临界区（一次更新覆盖另一次 = 丢对话；或读到写一半的文件）。
     * 分段锁永不需要删除，从根上没有这个竞态；代价是不同 key 可能共享一把锁，
     * 而 key 总量 = 角色数 × 用户数，量级很小，并发损失可忽略。
     */
    private final ReentrantLock[] lockStripes = new ReentrantLock[LOCK_STRIPES];

    {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            lockStripes[i] = new ReentrantLock();
        }
    }

    private final int maxGroups;

    public MemoryService(FileHelper fileHelper, LlmGateway llmGateway, ConfigService configService) {
        this.fileHelper = fileHelper;
        this.llmGateway = llmGateway;
        int configured = configService.getInt("llm.max_context_rounds", 15);
        this.maxGroups = configured > 0 ? configured : 15;
    }

    private Path memDir(String avatar, String userId) {
        Path dir = fileHelper.avatarsRoot().resolve(avatar).resolve("memory").resolve(userId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("创建记忆目录失败: {}", dir);
        }
        return dir;
    }

    private Path shortPath(String avatar, String userId) {
        return memDir(avatar, userId).resolve("short_memory.json");
    }

    private Path corePath(String avatar, String userId) {
        return memDir(avatar, userId).resolve("core_memory.json");
    }

    /**
     * 取「角色 + 用户」的互斥锁（固定分段，见 {@link #lockStripes} 的说明）。
     */
    private ReentrantLock lockFor(String avatar, String userId) {
        int h = (avatar + ":" + userId).hashCode();
        h ^= (h >>> 16);                       // 扩散高位，减少低位碰撞
        return lockStripes[h & (LOCK_STRIPES - 1)];
    }

    /**
     * 原子写 JSON：先写同目录临时文件再 move 替换。
     *
     * <p>不能原地覆盖——进程在写文件中途崩溃（断电 / OOM Kill）会留下半截 JSON，
     * 之后 readShort 解析失败静默返回空，用户记忆无声丢失。move 是原子操作，
     * 读者只会看到完整的新文件或完整的旧文件。
     */
    private void writeJsonAtomically(Path path, Object data) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);   // 极端文件系统降级
        }
    }

    // ==================== 短期记忆 ====================

    /** 追加一轮对话。超过 maxGroups 时丢弃最旧的（与 Python 的 data[-max:] 一致） */
    public void addConversation(String avatar, String userMsg, String botReply, String userId, boolean isSystem) {
        Path path = shortPath(avatar, userId);
        ReentrantLock lock = lockFor(avatar, userId);
        lock.lock();
        try {
            List<Map<String, Object>> data = readShort(path);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("user", userMsg);
            entry.put("bot", botReply);
            entry.put("time", LocalDateTime.now().format(TIME_FMT));
            if (isSystem) {
                entry.put("system", true);
            }
            data.add(entry);
            if (data.size() > maxGroups) {
                data = new ArrayList<>(data.subList(data.size() - maxGroups, data.size()));
            }
            writeJsonAtomically(path, data);
            cache.invalidate(avatar + ":" + userId + ":short");
        } catch (Exception e) {
            log.error("保存对话记忆失败: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public List<LlmGateway.ChatMessage> getRecentContext(String avatar, String userId) {
        Path path = shortPath(avatar, userId);
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        String key = avatar + ":" + userId + ":short";
        long mtime = mtimeOf(path);
        CachedEntry cached = cache.getIfPresent(key);
        if (cached != null && cached.mtime == mtime) {
            @SuppressWarnings("unchecked")
            List<LlmGateway.ChatMessage> hit = (List<LlmGateway.ChatMessage>) cached.value;
            return new ArrayList<>(hit);
        }

        ReentrantLock lock = lockFor(avatar, userId);
        lock.lock();
        try {
            List<Map<String, Object>> data = readShort(path);
            List<LlmGateway.ChatMessage> ctx = new ArrayList<>();
            int from = Math.max(0, data.size() - maxGroups);
            for (Map<String, Object> entry : data.subList(from, data.size())) {
                Object u = entry.get("user");
                Object b = entry.get("bot");
                ctx.add(new LlmGateway.ChatMessage("user", u == null ? "" : String.valueOf(u)));
                ctx.add(new LlmGateway.ChatMessage("assistant", b == null ? "" : String.valueOf(b)));
            }
            cache.put(key, new CachedEntry(mtime, ctx));
            return new ArrayList<>(ctx);
        } catch (Exception e) {
            log.error("读取短期记忆失败: {}", e.getMessage());
            return new ArrayList<>();
        } finally {
            lock.unlock();
        }
    }

    private List<Map<String, Object>> readShort(Path path) {
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> data = mapper.readValue(path.toFile(), new TypeReference<>() {
            });
            return data == null ? new ArrayList<>() : data;
        } catch (Exception e) {
            // 崩溃可能留下半截 JSON。备份坏文件并告警，而不是静默当成空记忆——
            // 否则用户看到的是"角色莫名其妙失忆"，且没有任何日志线索。
            log.warn("短期记忆文件损坏，已备份重建: {} ({})", path, e.getMessage());
            backupCorrupt(path, "short_memory.corrupt");
            return new ArrayList<>();
        }
    }

    /** 把损坏的记忆文件改名备份，失败只影响排障，不影响主流程 */
    private static void backupCorrupt(Path path, String prefix) {
        try {
            Files.move(path, path.resolveSibling(
                    prefix + "-" + System.currentTimeMillis() + ".json"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveErr) {
            // AtomicMoveNotSupportedException 等 IO 异常：备份失败不影响主流程
            log.debug("损坏文件备份失败: {}", moveErr.getMessage());
        }
    }

    // ==================== 核心记忆 ====================

    public String getCoreMemory(String avatar, String userId) {
        Path path = corePath(avatar, userId);
        if (!Files.exists(path)) {
            return "";
        }
        String key = avatar + ":" + userId + ":core";
        long mtime = mtimeOf(path);
        CachedEntry cached = cache.getIfPresent(key);
        if (cached != null && cached.mtime == mtime) {
            return (String) cached.value;
        }
        try {
            Map<String, Object> data = mapper.readValue(path.toFile(), new TypeReference<>() {
            });
            String content = data == null ? "" : String.valueOf(data.getOrDefault("content", ""));
            cache.put(key, new CachedEntry(mtime, content));
            return content;
        } catch (Exception e) {
            log.warn("核心记忆文件损坏，已备份重建: {} ({})", path, e.getMessage());
            backupCorrupt(path, "core_memory.corrupt");
            return "";
        }
    }

    /**
     * 用 LLM 把最近对话压缩成核心记忆。
     * 模板取 classpath:static/prompts/memory.md（与原版同一份文件）。
     */
    public boolean updateCoreMemory(String avatar, String userId) {
        String existing = getCoreMemory(avatar, userId);
        List<LlmGateway.ChatMessage> context = getRecentContext(avatar, userId);
        if (context.isEmpty()) {
            return false;
        }

        StringBuilder prompt = new StringBuilder(loadPromptTemplate("memory.md")).append("\n\n");
        prompt.append("现有核心记忆: ").append(existing).append("\n\n最近对话:\n");
        for (int i = 0; i < context.size(); i += 2) {
            String u = context.get(i).content();
            String b = (i + 1 < context.size()) ? context.get(i + 1).content() : "";
            prompt.append("用户: ").append(u).append("\n回复: ").append(b).append("\n");
        }

        LlmGateway.LlmResult result = llmGateway.chat(null, List.of(
                new LlmGateway.ChatMessage("system",
                        "你是一个记忆管理助手。根据用户提供的对话记录，提取并更新核心记忆摘要。"
                                + "只输出更新后的核心记忆内容，不要输出其他内容。"),
                new LlmGateway.ChatMessage("user", prompt.toString())
        ));

        if (result.error() != null || result.reply() == null || result.reply().isBlank()) {
            log.warn("核心记忆更新返回异常: {}", result.error());
            return false;
        }

        Path path = corePath(avatar, userId);
        ReentrantLock lock = lockFor(avatar, userId);
        lock.lock();
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("timestamp", LocalDateTime.now().format(TIME_FMT));
            data.put("content", result.reply());
            writeJsonAtomically(path, data);
            cache.invalidate(avatar + ":" + userId + ":core");
            log.info("核心记忆已更新: {}/{}", avatar, userId);
            return true;
        } catch (Exception e) {
            log.error("写入核心记忆失败: {}", e.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    public void clearCoreMemory(String avatar, String userId) {
        Path path = corePath(avatar, userId);
        ReentrantLock lock = lockFor(avatar, userId);
        lock.lock();
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("timestamp", LocalDateTime.now().format(TIME_FMT));
            data.put("content", "");
            writeJsonAtomically(path, data);
            cache.invalidate(avatar + ":" + userId + ":core");
        } catch (Exception e) {
            log.error("清空核心记忆失败: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // ==================== 辅助 ====================

    /** 读取 resources/static/prompts/ 下的模板 */
    public String loadPromptTemplate(String filename) {
        try (InputStream in = new ClassPathResource("static/prompts/" + filename).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("读取提示模板失败: {} - {}", filename, e.getMessage());
            return "";
        }
    }

    private static long mtimeOf(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private record CachedEntry(long mtime, Object value) {
    }
}
