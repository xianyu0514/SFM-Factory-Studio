package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.PinyinSearch;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PinyinSearchTests {
    @Test
    public void normalizedFastPathKeepsTheSameSearchResults() {
        for (String source : new String[]{"Iron Ingot", "minecraft:stone", "石头"}) {
            for (String query : new String[]{"IRON", "stone", "不存在", ""}) {
                assertEquals(PinyinSearch.matches(source, query),
                        PinyinSearch.matchesNormalized(source, query.toLowerCase(Locale.ROOT)));
            }
        }
    }
}
