package com.motochat.llm;

import java.util.regex.Pattern;

/**
 * 输出清理：去掉 {@code <think>...</think>} 推理块、表情标签、三连换行。
 *
 * <p>流式版本是个状态机，难点在于标签可能被切在两个 chunk 之间
 * （比如 {@code "<thi"} + {@code "nk>"}）。处理办法是把疑似未完成的尾部
 * 扣回缓冲区，等下一个 chunk 到齐再判断 —— 直接照搬 Python 版
 * {@code LLMService.get_response_stream} 的做法，包括那两个 partial 判断函数。
 */
public final class OutputFilter {

    private static final Pattern THINK_RE = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private static final Pattern TRIPLE_NL_RE = Pattern.compile("\n{3,}");
    private static final String THINK_START = "<think>";

    private OutputFilter() {
    }

    /** 非流式清理 */
    public static String clean(String content) {
        if (content == null) {
            return null;
        }
        String s = THINK_RE.matcher(content).replaceAll("");
        s = EmotionTags.PATTERN.matcher(s).replaceAll("");
        s = TRIPLE_NL_RE.matcher(s).replaceAll("\n\n");
        return s.strip();
    }

    /**
     * 流式过滤器。每次流式请求创建一个实例，不是线程安全的，也不需要。
     */
    public static final class Streaming {

        private final StringBuilder buffer = new StringBuilder();

        /**
         * 喂入一个增量，返回本次可以安全放出的文本（可能为空串）。
         */
        public String feed(String delta) {
            if (delta == null || delta.isEmpty()) {
                return "";
            }
            buffer.append(delta);

            String safe;
            String bufStr = buffer.toString();

            if (bufStr.contains(THINK_START)) {
                // 先删掉成对的 think 块
                String cleaned = THINK_RE.matcher(bufStr).replaceAll("");
                int lastOpen = cleaned.lastIndexOf(THINK_START);
                if (lastOpen >= 0) {
                    // 还有未闭合的 think，后面的都不能放出去
                    safe = cleaned.substring(0, lastOpen);
                    reset(cleaned.substring(lastOpen));
                } else {
                    safe = cleaned;
                    reset("");
                }
            } else {
                safe = bufStr;
                reset("");
            }

            // 尾部可能是被切断的 "<think>" 前缀（如 "<thi"），扣回缓冲
            int hold = partialTagLen(safe);
            if (hold > 0) {
                buffer.insert(0, safe.substring(safe.length() - hold));
                safe = safe.substring(0, safe.length() - hold);
            }

            // 尾部可能是被切断的表情标签（如 "[hap"）
            int ehold = partialEmotionLen(safe);
            if (ehold > 0) {
                buffer.insert(0, safe.substring(safe.length() - ehold));
                safe = safe.substring(0, safe.length() - ehold);
            }

            if (safe.indexOf('[') >= 0) {
                safe = EmotionTags.PATTERN.matcher(safe).replaceAll("");
            }
            return safe;
        }

        /** 流结束时把缓冲区剩余内容吐出来（未闭合的标签直接丢弃） */
        public String flush() {
            String rest = buffer.toString();
            buffer.setLength(0);
            if (rest.isEmpty()) {
                return "";
            }
            rest = THINK_RE.matcher(rest).replaceAll("");
            // 删除未闭合的 think（从 <think> 到结尾）
            int open = rest.indexOf(THINK_START);
            if (open >= 0) {
                rest = rest.substring(0, open);
            }
            rest = EmotionTags.PATTERN.matcher(rest).replaceAll("");
            int ehold = partialEmotionLen(rest);
            if (ehold > 0) {
                rest = rest.substring(0, rest.length() - ehold);
            }
            rest = TRIPLE_NL_RE.matcher(rest).replaceAll("\n\n");
            return rest.strip();
        }

        private void reset(String value) {
            buffer.setLength(0);
            buffer.append(value);
        }

        /** 返回 s 尾部与 "<think>" 前缀重合的最大长度 */
        private static int partialTagLen(String s) {
            int max = Math.min(s.length(), THINK_START.length() - 1);
            for (int len = max; len > 0; len--) {
                if (s.endsWith(THINK_START.substring(0, len))) {
                    return len;
                }
            }
            return 0;
        }

        /**
         * 返回 s 尾部应扣留的未闭合表情标签长度。
         * 只有确实是已知标签前缀时才扣留，避免正文里普通的 "[" 被无限卡住。
         */
        private static int partialEmotionLen(String s) {
            int idx = s.lastIndexOf('[');
            if (idx < 0) {
                return 0;
            }
            String tail = s.substring(idx);
            if (tail.indexOf(']') >= 0) {
                return 0;
            }
            if (tail.length() > EmotionTags.MAX_TAG_LEN) {
                return 0;
            }
            String inner = tail.substring(1);
            for (String tag : EmotionTags.TAGS) {
                if (tag.startsWith(inner)) {
                    return tail.length();
                }
            }
            return 0;
        }
    }
}
