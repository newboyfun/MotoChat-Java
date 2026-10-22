package com.motochat.llm;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 表情标签定义，全项目唯一来源（对应 Python 版 services/emoji.py）。
 *
 * <p>prompt_base.md 要求模型输出 [happy]/[sad] 等标签，但聊天界面不展示，
 * 所以流式、非流式、落库、TTS 每个入口都要剥离。
 *
 * <p>正则靠「方括号 + 标签白名单」定界，正文里的其他方括号（[SPLIT]、[100]）不会被误删。
 */
public final class EmotionTags {

    public static final List<String> TAGS = List.of(
            "happy", "sad", "angry", "neutral", "love", "funny", "cute", "bored", "shy",
            "embarrassed", "sleepy", "lonely", "hungry", "comfort", "surprise", "confused",
            "playful", "excited", "tease", "hot", "speechless", "scared",
            "afraid", "amused", "anxious", "confident", "cold", "suspicious",
            "loving", "curious", "envious", "jealous", "miserable", "sick",
            "ashamed", "indifferent", "sorry", "determined", "crazy", "bashful",
            "depressed", "enraged", "frightened", "interested", "hopeful",
            "regretful", "stubborn", "thirsty", "guilty", "nervous", "disgusted",
            "proud", "ecstatic", "frustrated", "hurt", "tired", "smug",
            "thoughtful", "optimistic", "relieved", "puzzled", "shocked",
            "joyful", "skeptical", "bad", "worried"
    );

    public static final Pattern PATTERN = Pattern.compile(
            "\\[(" + String.join("|", TAGS) + ")\\]");

    /** 最长标签长度 + 1，用于判断未闭合片段是否可能是表情标签 */
    public static final int MAX_TAG_LEN = TAGS.stream().mapToInt(String::length).max().orElse(7) + 1;

    private EmotionTags() {
    }

    /** 移除所有已知表情标签并去首尾空白 */
    public static String clean(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        return PATTERN.matcher(message).replaceAll("").strip();
    }
}
