package com.motochat;

import com.motochat.llm.OutputFilter;
import com.motochat.stream.ChunkCoalescer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心算法单测：流式合并与输出过滤。
 * 这两块是"每个 chunk 都要过一遍"的热路径，出错不会抛异常，只会静默产出脏文本，
 * 所以必须有测试兜底。
 */
class StreamPipelineTest {

    // ==================== ChunkCoalescer ====================

    @Test
    @DisplayName("不足阈值且未超时：继续攒着")
    void coalescerBuffersShortChunks() {
        // 时间阈值设很大，只测字符阈值
        ChunkCoalescer coalescer = new ChunkCoalescer(24, 60_000);
        assertNull(coalescer.add("你好"));
        assertNull(coalescer.add("世界"));
        assertEquals(4, coalescer.pending());
    }

    @Test
    @DisplayName("攒够字符阈值：一次性放行并清空")
    void coalescerFlushesAtThreshold() {
        ChunkCoalescer coalescer = new ChunkCoalescer(10, 60_000);
        assertNull(coalescer.add("12345"));
        String out = coalescer.add("67890");
        assertEquals("1234567890", out);
        assertEquals(0, coalescer.pending());
    }

    @Test
    @DisplayName("drain 强制放行，空缓冲返回 null")
    void coalescerDrain() {
        ChunkCoalescer coalescer = new ChunkCoalescer(1000, 60_000);
        coalescer.add("abc");
        assertEquals("abc", coalescer.drain());
        assertNull(coalescer.drain());
    }

    // ==================== OutputFilter（非流式）====================

    @Test
    @DisplayName("非流式：去掉 think 块、表情标签、合并三连换行")
    void cleanRemovesNoise() {
        String raw = "<think>推理过程</think>你好[happy]呀\n\n\n再见";
        assertEquals("你好呀\n\n再见", OutputFilter.clean(raw));
    }

    @Test
    @DisplayName("非流式：保留 [SPLIT] 这类功能性方括号")
    void cleanKeepsFunctionalTags() {
        assertTrue(OutputFilter.clean("第一段[SPLIT]第二段").contains("[SPLIT]"));
    }

    @Test
    @DisplayName("非流式：普通方括号数字不受影响")
    void cleanKeepsPlainBrackets() {
        assertEquals("价格[100]元", OutputFilter.clean("价格[100]元"));
    }

    // ==================== OutputFilter（流式）====================

    @Test
    @DisplayName("流式：think 块跨 chunk 切割时不泄露")
    void streamingHidesSplitThinkBlock() {
        OutputFilter.Streaming filter = new OutputFilter.Streaming();
        StringBuilder visible = new StringBuilder();
        // "<thi" + "nk>" 被切成两块，正是原版 _partial_tag_len 处理的场景
        for (String piece : new String[]{"你好", "<thi", "nk>", "隐藏", "</thi", "nk>", "世界"}) {
            visible.append(filter.feed(piece));
        }
        visible.append(filter.flush());
        assertEquals("你好世界", visible.toString());
    }

    @Test
    @DisplayName("流式：表情标签跨 chunk 切割时不泄露")
    void streamingHidesSplitEmotionTag() {
        OutputFilter.Streaming filter = new OutputFilter.Streaming();
        StringBuilder visible = new StringBuilder();
        for (String piece : new String[]{"你好", "[hap", "py]", "呀"}) {
            visible.append(filter.feed(piece));
        }
        visible.append(filter.flush());
        assertEquals("你好呀", visible.toString());
    }

    @Test
    @DisplayName("流式：结尾未闭合的标签直接被丢弃")
    void streamingDropsUnclosedTagAtEnd() {
        OutputFilter.Streaming filter = new OutputFilter.Streaming();
        String visible = filter.feed("你好[hap");
        String tail = filter.flush();
        assertEquals("你好", visible + tail);
    }

    @Test
    @DisplayName("流式：正文里孤立的 [ 不会被无限卡住")
    void streamingDoesNotStallOnPlainBracket() {
        OutputFilter.Streaming filter = new OutputFilter.Streaming();
        String visible = filter.feed("数组下标 [0] 表示第一个元素");
        assertTrue(visible.contains("数组下标"));
    }
}
