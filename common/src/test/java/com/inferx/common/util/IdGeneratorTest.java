package com.inferx.common.util;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.*;

class IdGeneratorTest {

    @Test
    void generatesUniqueIds() {
        var gen = new IdGenerator(0);
        var ids = new java.util.HashSet<Long>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(ids.add(gen.nextId())).isTrue();
        }
    }

    @Test
    void idsAreRoughlyOrdered() {
        var gen = new IdGenerator(0);
        long prev = gen.nextId();
        for (int i = 0; i < 100; i++) {
            long next = gen.nextId();
            assertThat(next).isGreaterThan(prev);
            prev = next;
        }
    }

    @Test
    void differentNodesProduceDifferentIds() {
        var gen0 = new IdGenerator(0);
        var gen1 = new IdGenerator(1);
        assertThat(gen0.nextId()).isNotEqualTo(gen1.nextId());
    }

    @Test
    void rejectsInvalidNodeId() {
        assertThatThrownBy(() -> new IdGenerator(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdGenerator(1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void threadSafeUniqueness() throws InterruptedException {
        var gen = new IdGenerator(0);
        int threads = 8;
        int idsPerThread = 5_000;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        var latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < idsPerThread; i++) {
                        ids.add(gen.nextId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        assertThat(ids).hasSize(threads * idsPerThread);
    }

    @Test
    void base36Conversion() {
        var gen = new IdGenerator(0);
        long id = gen.nextId();
        String base36 = IdGenerator.toBase36(id);
        assertThat(base36).isNotEmpty();
        assertThat(Long.parseLong(base36, 36)).isEqualTo(id);
    }
}
