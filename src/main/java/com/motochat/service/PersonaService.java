package com.motochat.service;

import com.motochat.common.ApiException;
import com.motochat.common.FileHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色（人设）管理。对应 Python 版 api/personas.py。
 *
 * <p>角色是文件系统实体：{@code userdata/avatars/{名称}/avatar.md}（设定）+ avatar.jpg（头像）。
 * 数据库里不存角色内容 —— 这与原版一致，好处是用户可以直接改文件。
 */
@Service
public class PersonaService {

    private static final Logger log = LoggerFactory.getLogger(PersonaService.class);
    private static final long AVATAR_MAX_BYTES = 5 * 1024 * 1024;
    private static final Map<String, String> BUILTIN_AVATARS = Map.of(
            "MONO", "avatar-mono.jpg",
            "SAGE", "avatar-sage.jpg");
    private static final String DEFAULT_AVATAR = "avatar-default.jpg";

    private final FileHelper fileHelper;
    private final PersonaState personaState;

    public PersonaService(FileHelper fileHelper, PersonaState personaState) {
        this.fileHelper = fileHelper;
        this.personaState = personaState;
    }

    /** 列出系统角色（avatars 目录下的所有子目录） */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        Path root = fileHelper.avatarsRoot();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (var stream = Files.list(root)) {
            List<Path> dirs = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path dir : dirs) {
                String name = dir.getFileName().toString();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("path", "data/avatars/" + name);
                item.put("has_avatar", Files.isRegularFile(dir.resolve("avatar.md")));
                item.put("active", name.equals(personaState.name()));
                result.add(item);
            }
        } catch (IOException e) {
            log.warn("读取角色目录失败: {}", e.getMessage());
        }
        return result;
    }

    public String readContent(String name) {
        Path md = fileHelper.personaMarkdown(name);
        try {
            return Files.readString(md, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(404, "角色 " + name + " 不存在");
        }
    }

    public void create(String rawName, String content) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("名称不合法");
        }
        Path dir = fileHelper.personaDir(name);
        if (Files.isDirectory(dir)) {
            throw ApiException.badRequest("角色 " + name + " 已存在");
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("avatar.md"),
                    content == null ? "# 角色\n\n你是一个友好的AI助手。" : content,
                    StandardCharsets.UTF_8);
            log.info("角色创建成功: {}", name);
        } catch (IOException e) {
            throw new ApiException(500, "操作失败，请查看服务器日志");
        }
    }

    public void update(String rawName, String content) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("名称不合法");
        }
        Path md = fileHelper.personaMarkdown(name);
        if (!Files.isRegularFile(md)) {
            throw new ApiException(404, "角色 " + name + " 不存在");
        }
        try {
            Files.writeString(md, content == null ? "" : content, StandardCharsets.UTF_8);
            if (name.equals(personaState.name())) {
                personaState.refreshPrompt(fileHelper.personaDir(name), content);
            }
            log.info("[角色] 设定已更新: {}", name);
        } catch (IOException e) {
            throw new ApiException(500, "操作失败，请查看服务器日志");
        }
    }

    public void delete(String rawName) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("名称不合法");
        }
        Path dir = fileHelper.personaDir(name);
        if (!Files.isDirectory(dir)) {
            throw new ApiException(404, "角色 " + name + " 不存在");
        }
        if (name.equals(personaState.name())) {
            throw ApiException.badRequest("不能删除当前正在使用的角色");
        }
        try {
            deleteRecursively(dir);
            log.info("[角色] 已删除: {}", name);
        } catch (IOException e) {
            throw new ApiException(500, "操作失败，请查看服务器日志");
        }
    }

    public void switchTo(String rawName) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("名称不合法");
        }
        Path dir = fileHelper.personaDir(name);
        if (!Files.isRegularFile(dir.resolve("avatar.md"))) {
            throw new ApiException(404, "角色 " + name + " 不存在");
        }
        personaState.apply(dir);
    }

    /** 头像读取：用户上传的 → 内置 → 默认（返回 null 表示要用内置静态资源） */
    public Path avatarFile(String rawName) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            return null;
        }
        Path p = fileHelper.personaAvatar(name);
        return Files.isRegularFile(p) ? p : null;
    }

    public String builtinAvatarFor(String rawName) {
        String key = rawName == null ? "" : rawName.toUpperCase();
        return BUILTIN_AVATARS.getOrDefault(key, DEFAULT_AVATAR);
    }

    /** 上传头像：中心裁成正方形 + 缩放到 512 + 存 JPEG（与原版 Pillow 处理等价） */
    public long saveAvatar(String rawName, byte[] data) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("名称不合法");
        }
        if (!Files.isDirectory(fileHelper.personaDir(name))) {
            throw new ApiException(404, "角色 " + name + " 不存在");
        }
        if (data == null || data.length == 0 || data.length > AVATAR_MAX_BYTES) {
            throw ApiException.badRequest("图片大小需在 5MB 以内");
        }
        if (FileHelper.sniffImageExt(data) == null) {
            throw ApiException.badRequest("仅支持 JPG / PNG / WebP / GIF 图片");
        }
        // 解码前校验真实尺寸：ImageIO.read 不信任头部声明的尺寸，
        // 一张几 MB 的图片可以声明几万像素宽，解码直接 OOM 打死整个 JVM
        if (!FileHelper.imageWithinLimit(data, FileHelper.MAX_IMAGE_PIXELS)) {
            throw ApiException.badRequest("图片尺寸过大");
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null) {
                throw ApiException.badRequest("图片处理失败，请换一张试试");
            }
            int w = src.getWidth();
            int h = src.getHeight();
            int side = Math.min(w, h);
            BufferedImage cropped = src.getSubimage((w - side) / 2, (h - side) / 2, side, side);

            BufferedImage out = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(cropped, 0, 0, 512, 512, null);
            g.dispose();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", buffer);
            Path target = fileHelper.personaAvatar(name);
            Files.write(target, buffer.toByteArray());
            log.info("[角色] 头像已更新: {} ({}KB)", name, Files.size(target) / 1024);
            return System.currentTimeMillis() / 1000;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("头像处理失败: {}", e.getMessage());
            throw ApiException.badRequest("图片处理失败，请换一张试试");
        }
    }

    public void deleteAvatar(String rawName) {
        String name = FileHelper.safePersonaName(rawName);
        if (name == null) {
            throw ApiException.badRequest("名称不合法");
        }
        try {
            Files.deleteIfExists(fileHelper.personaAvatar(name));
        } catch (IOException e) {
            throw new ApiException(500, "操作失败，请查看服务器日志");
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.deleteIfExists(dir);
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                deleteRecursively(child);
            }
        }
        Files.deleteIfExists(dir);
    }
}
