package io.github.xianynomial.sfmfactorystudio.test;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.SfmlValidate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 探索 20 global ticks + 能量组合 + plus/offset 语义。 */
public class BalanceEnergyTest {

    private boolean parses(String timerLine) {
        String sfml = "NAME \"t\"\n" + timerLine + "\n    input fe:: from a each side\nend\n";
        return SfmlValidate.check(sfml).isEmpty();
    }

    @Test
    public void discoverValidForms() {
        System.out.println("== 20 global ticks + energy: " + parses("every 20 global ticks do"));
        System.out.println("== global 20 ticks plus: " + parses("every 20 global ticks plus 5 do"));
        System.out.println("== 20 global ticks offset: " + parses("every 20 global ticks offset 5 do"));
        System.out.println("== 20 global ticks delay: " + parses("every 20 global ticks delay 5 do"));
        System.out.println("== 20 global ticks after: " + parses("every 20 global ticks after 5 do"));
        System.out.println("== 20 global ticks phase: " + parses("every 20 global ticks phase 5 do"));
        assertTrue(true);
    }
}
