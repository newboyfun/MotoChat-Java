package com.motochat.stream;

/**
 * 流式片段合并器。对应 Python 版 core/streaming.py。
 *
 * <p>背景：LLM 逐 token 产出，若每个 token 都发一帧，2000 字回复就是上千帧，
 * 而前端本来就按 ~50ms 批量渲染，绝大多数帧毫无意义。
 *
 * <p>放行条件（满足其一）：
 * <ul>
 *   <li>缓冲区字符数 ≥ 24：快速流下大幅减少帧数</li>
 *   <li>距上次放行 ≥ 50ms：慢速流下不额外增加延迟</li>
 * </ul>
 *
 * <p><b>不要改成"固定 50ms 定时 flush"</b> —— 那会引入固定延迟，
 * 而原设计的妙处正是"字数够了立刻发，不够才等时间"。
 *
 * <p>非线程安全，每个流一个实例。
 */
public final class ChunkCoalescer {

    private final int minChars;
    private final long maxWaitNanos;

    private final StringBuilder buffer = new StringBuilder();
    private long lastFlushNanos;

    public ChunkCoalescer() {
        this(24, 50);
    }

    public ChunkCoalescer(int minChars, long maxWaitMillis) {
        this.minChars = Math.max(1, minChars);
        this.maxWaitNanos = Math.max(0, maxWaitMillis) * 1_000_000L;
        this.lastFlushNanos = System.nanoTime();
    }

    /**
     * 加入一段文本。
     * @return 可放行的合并结果；返回 null 表示继续攒着
     */
    public String add(String text) {
        if (text != null && !text.isEmpty()) {
            buffer.append(text);
        }
        if (buffer.length() > 0
                && (buffer.length() >= minChars || System.nanoTime() - lastFlushNanos >= maxWaitNanos)) {
            return drain();
        }
        return null;
    }

    /** 强制放行缓冲区全部内容；缓冲区为空返回 null */
    public String drain() {
        lastFlushNanos = System.nanoTime();
        if (buffer.length() == 0) {
            return null;
        }
        String out = buffer.toString();
        buffer.setLength(0);
        return out;
    }

    public int pending() {
        return buffer.length();
    }
}
