package com.yiittv.mtv;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpuSamplerTest {

    @Test
    void groupsKnownThreads() {
        assertEquals("MTV video decoder (FFmpeg)", CpuSampler.group("MTV-live-3k9x"));
        assertEquals("MTV live audio decoder", CpuSampler.group("MTV-live-audio"));
        assertEquals("MTV render (map conversion)", CpuSampler.group("MTV-MapEngine"));
        assertEquals("Minecraft main thread", CpuSampler.group("Server thread"));
        assertEquals("Network (Netty)", CpuSampler.group("Netty Epoll Server IO #2"));
    }

    @Test
    void measuresAndFormats() throws Exception {
        Map<String, Double> sample = CpuSampler.sample(50);
        assertTrue(sample.values().stream().allMatch(value -> value >= 0));
        Map<String, Double> fixed = new LinkedHashMap<>();
        fixed.put("MTV video decoder (FFmpeg)", 80.0);
        fixed.put("Other", 0.1);
        assertEquals(2, CpuSampler.format(fixed, 4).size());
    }
}
