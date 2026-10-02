package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.AdaptiveSampler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 校准自适应降频护栏：安静翻倍、封顶、活动立即回全速。 */
public class AdaptiveSamplerTest {

    @Test
    public void quietDoublesIntervalUpToCap() {
        AdaptiveSampler sampler = new AdaptiveSampler(5, 40);
        assertEquals(5, sampler.intervalTicks());
        sampler.onQuiet();
        assertEquals(10, sampler.intervalTicks());
        sampler.onQuiet();
        assertEquals(20, sampler.intervalTicks());
        sampler.onQuiet();
        assertEquals(40, sampler.intervalTicks());
        sampler.onQuiet();
        assertEquals(40, sampler.intervalTicks(), "封顶后不再翻倍");
        sampler.onQuiet();
        assertEquals(40, sampler.intervalTicks());
    }

    @Test
    public void activityRestoresBaseImmediately() {
        AdaptiveSampler sampler = new AdaptiveSampler(5, 40);
        sampler.onQuiet();
        sampler.onQuiet();
        assertEquals(20, sampler.intervalTicks());
        sampler.onActivity();
        assertEquals(5, sampler.intervalTicks(), "任何活动（锚点/内容变化/槽数变化）立即回全速");
        assertEquals(0, sampler.quietSamples());
    }

    @Test
    public void degenerateBoundsHold() {
        AdaptiveSampler single = new AdaptiveSampler(5, 5);
        assertEquals(5, single.intervalTicks());
        single.onQuiet();
        assertEquals(5, single.intervalTicks());
        AdaptiveSampler one = new AdaptiveSampler(1, 8);
        one.onQuiet();
        one.onQuiet();
        one.onQuiet();
        one.onQuiet();
        assertEquals(8, one.intervalTicks());
    }
}
