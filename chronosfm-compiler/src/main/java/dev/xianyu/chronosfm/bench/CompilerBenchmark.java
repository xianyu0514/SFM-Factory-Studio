package dev.xianyu.chronosfm.bench;

import dev.xianyu.chronosfm.compiler.ChronoSfmCompiler;
import dev.xianyu.chronosfm.model.ProgramModel;
import dev.xianyu.chronosfm.model.StatementModel;
import dev.xianyu.chronosfm.model.TriggerModel;

import java.util.ArrayList;
import java.util.List;

public final class CompilerBenchmark {
    private CompilerBenchmark() {}

    public static void main(String[] args) {
        int regions = args.length == 0 ? 100_000 : Integer.parseInt(args[0]);
        List<StatementModel> statements = new ArrayList<>(regions);
        for (int i = 0; i < regions; i++) {
            statements.add(new StatementModel.Transfer(
                    "source-" + (i % 1024),
                    "dest-" + (i % 2048),
                    "minecraft:item/" + (i % 128),
                    false,
                    Long.MAX_VALUE,
                    0
            ));
        }

        ProgramModel program = new ProgramModel(List.of(
                new TriggerModel.Timer(1, TriggerModel.Alignment.LOCAL, 0, statements)
        ));

        ChronoSfmCompiler compiler = new ChronoSfmCompiler();
        for (int i = 0; i < 3; i++) compiler.compile(program);

        long start = System.nanoTime();
        var compiled = compiler.compile(program);
        long elapsed = System.nanoTime() - start;

        System.out.printf(
                "Compiled %,d transfer regions in %.3f ms (%.1f ns/region), legacy=%s%n",
                compiled.transferRegions().size(),
                elapsed / 1_000_000.0,
                elapsed / (double) compiled.transferRegions().size(),
                compiled.requiresLegacyExecution()
        );
    }
}
