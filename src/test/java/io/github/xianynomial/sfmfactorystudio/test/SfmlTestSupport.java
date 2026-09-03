package io.github.xianynomial.sfmfactorystudio.test;

import ca.teamdman.sfml.ast.Program;
import ca.teamdman.sfml.ast.BoolParen;
import ca.teamdman.sfml.ast.WithParen;
import ca.teamdman.sfml.program_builder.ProgramBuildResult;
import ca.teamdman.sfml.program_builder.ProgramBuilder;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test support that compiles SFML using SFM's public {@link ProgramBuilder}
 * (available on the test classpath from the SFM jar). Mirrors the slice of SFM's
 * internal test helpers that the migrated codegen tests rely on, without
 * depending on SFM's non-published test sources.
 */
public final class SfmlTestSupport {
    private SfmlTestSupport() {
    }

    /** Assert that the given SFML compiles with no build errors. */
    public static void assertNoCompileErrors(String sfml) {
        ProgramBuildResult result = new ProgramBuilder(sfml).useCache(false).build();
        var errors = result.metadata().errors();
        if (!errors.isEmpty()) {
            System.out.println("Compile errors for:\n" + sfml);
            errors.forEach(e -> System.out.println("  " + e));
        }
        assertTrue(errors.isEmpty(), "expected no compile errors, got: " + errors);
        assertNotNull(result.program(), "expected a compiled program");
    }

    /** Compile SFML to a {@link Program} and return its canonical string form. */
    public static String canonical(String sfml) {
        Program program = new ProgramBuilder(sfml).useCache(false).build().program();
        assertNotNull(program, "program failed to compile: " + sfml);
        return program.toString();
    }

    /**
     * A compiler-level meaning fingerprint. Unlike Program.toString(), this
     * deliberately ignores explicit parenthesis wrapper nodes: parentheses
     * that do not change the AST operation are formatting, not behaviour.
     */
    public static String semanticFingerprint(String sfml) {
        Program program = new ProgramBuilder(sfml).useCache(false).build().program();
        assertNotNull(program, "program failed to compile: " + sfml);
        return "Program(" + fingerprint(program.name()) + "," + fingerprint(program.triggers()) + ")";
    }

    private static String fingerprint(Object value) {
        if (value == null) return "null";
        if (value instanceof BoolParen paren) return fingerprint(paren.inner());
        if (value instanceof WithParen paren) return fingerprint(paren.inner());
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return value.getClass().getSimpleName() + ":" + value;
        }
        if (value instanceof Set<?> set) {
            List<String> entries = set.stream().map(SfmlTestSupport::fingerprint).sorted().toList();
            return "Set" + entries;
        }
        if (value instanceof Collection<?> collection) {
            List<String> entries = collection.stream().map(SfmlTestSupport::fingerprint).toList();
            return "List" + entries;
        }
        Class<?> type = value.getClass();
        if (type.isRecord()) {
            List<String> parts = new ArrayList<>();
            for (RecordComponent component : type.getRecordComponents()) {
                // ASTBuilder contains parser/source metadata rather than runtime meaning.
                if (component.getName().equals("astBuilder")) continue;
                try {
                    parts.add(component.getName() + "=" + fingerprint(component.getAccessor().invoke(value)));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Unable to inspect SFM AST " + type.getName(), e);
                }
            }
            return type.getSimpleName() + parts;
        }
        return type.getSimpleName() + ":" + value;
    }
}
