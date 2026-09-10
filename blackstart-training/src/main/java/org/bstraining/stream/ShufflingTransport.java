package org.bstraining.stream;

import org.bstraining.model.SimEvent;
import org.bstraining.sim.EventTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 乱序注入传输：把仿真器按 seq 产生的事件按块洗牌后再投递，
 * 专用于“消息乱序”测试（验证 ReorderingFlow 能恢复原始顺序）。
 *
 * <p>不丢事件（全排列后下游仍为完整集合）；若需测试丢包跳空，用 {@link #setDropOne}。</p>
 */
public final class ShufflingTransport implements EventTransport {

    private final EventTransport downstream;
    private final int blockSize;
    private final Random random;
    private final List<SimEvent> block = new ArrayList<>();
    private Long dropSeq;

    public ShufflingTransport(EventTransport downstream, int blockSize, Random random) {
        this.downstream = downstream;
        this.blockSize = blockSize;
        this.random = random;
    }

    /** 丢弃指定 seq 的事件（模拟丢包，触发缺口超时跳空）。 */
    public void setDropOne(Long seq) {
        this.dropSeq = seq;
    }

    @Override
    public void emit(SimEvent event) {
        if (dropSeq != null && dropSeq == event.seq()) {
            dropSeq = null;
            return;
        }
        block.add(event);
        if (block.size() >= blockSize) {
            flush();
        }
    }

    public void flush() {
        List<SimEvent> shuffled = new ArrayList<>(block);
        Collections.shuffle(shuffled, random);
        shuffled.forEach(downstream::emit);
        block.clear();
    }
}
