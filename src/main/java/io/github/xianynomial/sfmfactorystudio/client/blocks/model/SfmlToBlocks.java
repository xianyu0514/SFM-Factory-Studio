package io.github.xianynomial.sfmfactorystudio.client.blocks.model;

import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram.Bool;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram.Branch;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram.LabelAccess;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram.PulseTrigger;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram.ResourceLimit;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram.ResourceRef;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram.Statement;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram.TimerTrigger;
import io.github.xianynomial.sfmfactorystudio.client.blocks.model.BProgram.Trigger;
import ca.teamdman.langs.SFMLLexer;
import ca.teamdman.langs.SFMLParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses SFML text into the block model using the same ANTLR parser SFM ships.
 * Structured blocks are produced for every shape in the current SFML grammar.
 * Read-only raw nodes are retained solely as a forward-compatibility guard.
 * Returns an error message instead of throwing when the text does not compile —
 * the caller decides how to present it.
 */
public final class SfmlToBlocks {
    private SfmlToBlocks() {
    }

    public record Result(BProgram program, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public static Result parse(String sfml) {
        List<String> errors = new ArrayList<>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg, RecognitionException e) {
                errors.add("第 " + line + " 行: " + msg);
            }
        };
        try {
            var lexer = new SFMLLexer(CharStreams.fromString(sfml == null ? "" : sfml));
            lexer.removeErrorListeners();
            lexer.addErrorListener(listener);
            var tokens = new CommonTokenStream(lexer);
            var parser = new SFMLParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(listener);
            var ctx = parser.program();
            tokens.fill();
            CommentIndex comments = new CommentIndex(tokens.getTokens());

            BProgram program = new BProgram();
            if (ctx.name() != null) {
                String s = ctx.name().string().getText();
                program.name = unquote(s);
            }
            for (var tctx : ctx.trigger()) {
                Trigger t = readTrigger(tctx, comments);
                if (t != null) program.triggers.add(t);
            }
            readTopLevelComments(ctx, program, comments);
            return new Result(program, errors);
        } catch (Exception e) {
            errors.add("解析失败: " + e.getMessage());
            return new Result(null, errors);
        }
    }

    // ---------------------------------------------------------------- triggers

    private static Trigger readTrigger(SFMLParser.TriggerContext ctx, CommentIndex comments) {
        if (ctx instanceof SFMLParser.TimerTriggerContext t) {
            TimerTrigger tt = new TimerTrigger();
            readInterval(t.interval(), tt);
            readBlock(t.block(), tt.body, comments);
            return tt;
        } else if (ctx instanceof SFMLParser.PulseTriggerContext p) {
            PulseTrigger pt = new PulseTrigger();
            readBlock(p.block(), pt.body, comments);
            return pt;
        }
        return null;
    }

    private static void readInterval(SFMLParser.IntervalContext ctx, TimerTrigger tt) {
        if (ctx instanceof SFMLParser.IntervalSpaceContext s) {
            // NUMBER? GLOBAL? (PLUS NUMBER)? UNIT
            tt.global = s.GLOBAL() != null;
            List<Token> numbers = new ArrayList<>();
            for (var n : s.NUMBER()) numbers.add(n.getSymbol());
            if (!numbers.isEmpty()) tt.count = Long.parseLong(numbers.get(0).getText());
            if (s.PLUS() != null && numbers.size() > 1) {
                tt.plus = Long.parseLong(numbers.get(numbers.size() - 1).getText());
            }
            tt.unit = (s.TICKS() != null || s.TICK() != null) ? TimerTrigger.Unit.TICKS : TimerTrigger.Unit.SECONDS;
        } else if (ctx instanceof SFMLParser.IntervalNoSpaceContext ns) {
            // NUMBER_WITH_G_SUFFIX (PLUS NUMBER)? UNIT
            String g = ns.NUMBER_WITH_G_SUFFIX() != null ? ns.NUMBER_WITH_G_SUFFIX().getText() : "1";
            tt.global = g.toLowerCase().endsWith("g");
            tt.count = Long.parseLong(g.substring(0, g.length() - 1));
            tt.unit = (ns.TICKS() != null || ns.TICK() != null) ? TimerTrigger.Unit.TICKS : TimerTrigger.Unit.SECONDS;
            if (ns.PLUS() != null && ns.NUMBER() != null) {
                tt.plus = Long.parseLong(ns.NUMBER().getText());
            }
        }
    }

    private static void readBlock(SFMLParser.BlockContext ctx, List<Statement> into, CommentIndex comments) {
        List<PositionedStatement> positioned = new ArrayList<>();
        for (var sctx : ctx.statement()) {
            // Read nested IF blocks first. Their comments become used before this
            // block gathers the still-unclaimed comments in its own region.
            Statement s = readStatement(sctx, comments);
            if (s != null) positioned.add(new PositionedStatement(tokenIndex(sctx.getStart()), 0, s));
        }
        for (Token comment : comments.takeDirectComments(ctx)) {
            positioned.add(new PositionedStatement(comment.getTokenIndex(), 1,
                    new Statement.Comment(stripComment(comment.getText()))));
        }
        positioned.sort(Comparator.comparingInt(PositionedStatement::tokenIndex)
                .thenComparingInt(PositionedStatement::kindOrder));
        for (PositionedStatement entry : positioned) into.add(entry.statement());
    }

    // -------------------------------------------------------------- statements

    private static Statement readStatement(SFMLParser.StatementContext ctx, CommentIndex comments) {
        if (ctx.inputStatement() != null) return readInput(ctx.inputStatement());
        if (ctx.outputStatement() != null) return readOutput(ctx.outputStatement());
        if (ctx.forgetStatement() != null) return readForget(ctx.forgetStatement());
        if (ctx.ifStatement() != null) return readIf(ctx.ifStatement(), comments);
        return new Statement.Raw(rawText(ctx));
    }

    private static Statement.Input readInput(SFMLParser.InputStatementContext ctx) {
        Statement.Input in = new Statement.Input();
        in.each = ctx.EACH() != null;
        if (ctx.inputResourceLimits() != null) {
            readLimitList(ctx.inputResourceLimits().resourceLimitList(), in.limits);
        }
        if (ctx.resourceExclusion() != null) {
            readResourceIdList(ctx.resourceExclusion().resourceIdList(), in.except);
        }
        readLabelAccess(ctx.labelAccess(), in.access);
        return in;
    }

    private static Statement.Output readOutput(SFMLParser.OutputStatementContext ctx) {
        Statement.Output out = new Statement.Output();
        out.each = ctx.EACH() != null;
        out.emptySlots = ctx.emptyslots() != null;
        if (ctx.outputResourceLimits() != null) {
            readLimitList(ctx.outputResourceLimits().resourceLimitList(), out.limits);
        }
        if (ctx.resourceExclusion() != null) {
            readResourceIdList(ctx.resourceExclusion().resourceIdList(), out.except);
        }
        readLabelAccess(ctx.labelAccess(), out.access);
        return out;
    }

    private static Statement.Forget readForget(SFMLParser.ForgetStatementContext ctx) {
        Statement.Forget f = new Statement.Forget();
        for (var l : ctx.label()) {
            f.labels.add(readLabel(l));
        }
        return f;
    }

    private static Statement.If readIf(SFMLParser.IfStatementContext ctx, CommentIndex comments) {
        Statement.If iff = new Statement.If();
        int n = ctx.boolexpr().size();
        for (int i = 0; i < n; i++) {
            Branch b = new Branch();
            b.cond = readBool(ctx.boolexpr(i));
            readBlock(ctx.block(i), b.body, comments);
            iff.branches.add(b);
        }
        // grammar: (ELSE IF ...)* (ELSE block)? — else block is the last block when
        // its count exceeds the condition count
        if (ctx.block().size() > n) {
            iff.hasElse = true;
            readBlock(ctx.block(n), iff.elseBody, comments);
        }
        return iff;
    }

    // ------------------------------------------------------------------- io

    private static void readLimitList(SFMLParser.ResourceLimitListContext ctx, List<ResourceLimit> into) {
        if (ctx == null) return;
        for (var rlctx : ctx.resourceLimit()) {
            ResourceLimit rl = new ResourceLimit();
            if (rlctx.limit() != null) {
                var limit = rlctx.limit();
                if (limit instanceof SFMLParser.QuantityRetentionLimitContext qr) {
                    readQuantity(qr.quantity(), rl);
                    readRetention(qr.retention(), rl);
                } else if (limit instanceof SFMLParser.RetentionLimitContext r) {
                    readRetention(r.retention(), rl);
                } else if (limit instanceof SFMLParser.QuantityLimitContext q) {
                    readQuantity(q.quantity(), rl);
                }
            }
            if (rlctx.resourceIdDisjunction() != null) {
                for (var rid : rlctx.resourceIdDisjunction().resourceId()) {
                    rl.resources.add(readResourceId(rid));
                }
            }
            if (rlctx.with() != null) {
                rl.with = readWith(rlctx.with());
            }
            into.add(rl);
        }
    }

    private static void readQuantity(SFMLParser.QuantityContext ctx, ResourceLimit rl) {
        if (ctx == null) return;
        rl.quantity = Long.parseLong(ctx.number().getText());
        rl.quantityEach = ctx.EACH() != null;
    }

    private static void readRetention(SFMLParser.RetentionContext ctx, ResourceLimit rl) {
        if (ctx == null) return;
        rl.retain = Long.parseLong(ctx.number().getText());
        rl.retainEach = ctx.EACH() != null;
    }

    private static void readResourceIdList(SFMLParser.ResourceIdListContext ctx, List<ResourceRef> into) {
        if (ctx == null) return;
        for (var rid : ctx.resourceId()) {
            into.add(readResourceId(rid));
        }
    }

    private static ResourceRef readResourceId(SFMLParser.ResourceIdContext ctx) {
        if (ctx instanceof SFMLParser.StringResourceContext s) {
            return ResourceRef.parse(unquote(s.getText()));
        }
        return ResourceRef.parse(rawText(ctx));
    }

    private static void readLabelAccess(SFMLParser.LabelAccessContext ctx, LabelAccess access) {
        if (ctx == null) return;
        for (var l : ctx.label()) {
            access.labels.add(readLabel(l));
        }
        if (ctx.roundrobin() != null) {
            var rr = ctx.roundrobin();
            access.roundRobin = rr.LABEL() != null
                    ? BProgram.RoundRobinMode.LABEL
                    : BProgram.RoundRobinMode.BLOCK;
        }
        if (ctx.sidequalifier() instanceof SFMLParser.ListedSidesContext sides) {
            for (var s : sides.side()) {
                access.sides.add(BProgram.Side.fromSfml(s.getText()));
            }
        } else if (ctx.sidequalifier() instanceof SFMLParser.EachSideContext) {
            access.eachSide = true;
        }
        if (ctx.slotqualifier() != null) {
            for (var r : ctx.slotqualifier().rangeset().range()) {
                access.slots.add(BProgram.SlotRange.parse(rawText(r)));
            }
        }
    }

    private static String readLabel(SFMLParser.LabelContext ctx) {
        if (ctx instanceof SFMLParser.StringLabelContext s) {
            return unquote(s.getText());
        }
        return ctx.getText(); // RawLabelContext
    }

    // ------------------------------------------------------------------ bool

    private static Bool readBool(SFMLParser.BoolexprContext ctx) {
        if (ctx instanceof SFMLParser.BooleanTrueContext) return new Bool.Const(true);
        if (ctx instanceof SFMLParser.BooleanFalseContext) return new Bool.Const(false);
        if (ctx instanceof SFMLParser.BooleanParenContext p) return readBool(p.boolexpr());
        if (ctx instanceof SFMLParser.BooleanNegationContext n) {
            Bool.Not not = new Bool.Not();
            not.inner = readBool(n.boolexpr());
            return not;
        }
        if (ctx instanceof SFMLParser.BooleanConjunctionContext c) {
            Bool.And and = new Bool.And();
            flattenBool(and.parts, c.boolexpr(0), c.boolexpr(1), SFMLParser.BooleanConjunctionContext.class);
            return and;
        }
        if (ctx instanceof SFMLParser.BooleanDisjunctionContext d) {
            Bool.Or or = new Bool.Or();
            flattenBool(or.parts, d.boolexpr(0), d.boolexpr(1), SFMLParser.BooleanDisjunctionContext.class);
            return or;
        }
        if (ctx instanceof SFMLParser.BooleanHasContext h) {
            return readBoolHas(h);
        }
        if (ctx instanceof SFMLParser.BooleanRedstoneContext r) {
            Bool.Redstone rs = new Bool.Redstone();
            if (r.comparisonOp() != null && r.number() != null) {
                rs.comparison = readComparisonOp(r.comparisonOp());
                rs.number = Long.parseLong(r.number().getText());
            }
            return rs;
        }
        return new Bool.RawBool(rawText(ctx));
    }

    private static Bool.Has readBoolHas(SFMLParser.BooleanHasContext h) {
        Bool.Has has = new Bool.Has();
        if (h.setOp() != null) has.setMode = Bool.SetMode.fromSfml(h.setOp().getText());
        readLabelAccess(h.labelAccess(), has.access);
        has.comparison = readComparisonOp(h.comparisonOp());
        has.number = Long.parseLong(h.number().getText());
        if (h.resourceIdDisjunction() != null) {
            for (var rid : h.resourceIdDisjunction().resourceId()) {
                has.resources.add(readResourceId(rid));
            }
        }
        if (h.with() != null) has.with = readWith(h.with());
        if (h.resourceIdList() != null) readResourceIdList(h.resourceIdList(), has.except);
        return has;
    }

    // ----------------------------------------------------------- with filter

    private static BProgram.WithFilter readWith(SFMLParser.WithContext ctx) {
        BProgram.WithFilter filter = new BProgram.WithFilter();
        filter.mode = ctx.WITHOUT() != null
                ? BProgram.WithFilter.Mode.WITHOUT
                : BProgram.WithFilter.Mode.WITH;
        filter.expr = readWithExpr(ctx.withClause());
        return filter;
    }

    private static BProgram.WithExpr readWithExpr(SFMLParser.WithClauseContext ctx) {
        if (ctx instanceof SFMLParser.WithParenContext paren) {
            return readWithExpr(paren.withClause());
        }
        if (ctx instanceof SFMLParser.WithNegationContext negation) {
            BProgram.WithExpr.Not not = new BProgram.WithExpr.Not();
            not.inner = readWithExpr(negation.withClause());
            return not;
        }
        if (ctx instanceof SFMLParser.WithConjunctionContext conjunction) {
            BProgram.WithExpr.And and = new BProgram.WithExpr.And();
            flattenWith(and.parts, conjunction.withClause(0), conjunction.withClause(1), true);
            return and;
        }
        if (ctx instanceof SFMLParser.WithDisjunctionContext disjunction) {
            BProgram.WithExpr.Or or = new BProgram.WithExpr.Or();
            flattenWith(or.parts, disjunction.withClause(0), disjunction.withClause(1), false);
            return or;
        }
        if (ctx instanceof SFMLParser.WithTagContext tag) {
            return new BProgram.WithExpr.Tag(rawText(tag.tagMatcher()));
        }
        return new BProgram.WithExpr.Tag("*");
    }

    private static void flattenWith(List<BProgram.WithExpr> into,
                                    SFMLParser.WithClauseContext left,
                                    SFMLParser.WithClauseContext right,
                                    boolean conjunction) {
        boolean same = conjunction
                ? left instanceof SFMLParser.WithConjunctionContext
                : left instanceof SFMLParser.WithDisjunctionContext;
        if (same) {
            if (conjunction) {
                var nested = (SFMLParser.WithConjunctionContext) left;
                flattenWith(into, nested.withClause(0), nested.withClause(1), true);
            } else {
                var nested = (SFMLParser.WithDisjunctionContext) left;
                flattenWith(into, nested.withClause(0), nested.withClause(1), false);
            }
        } else {
            into.add(readWithExpr(left));
        }
        into.add(readWithExpr(right));
    }

    /** Parse an editable resource-filter fragment without storing unvalidated text. */
    public static ResultWithFilter parseWithFilter(String text) {
        String fragment = text == null ? "" : text.trim();
        String wrapper = "every 20 ticks do\ninput * " + fragment + " from a\nend\n";
        Result result = parse(wrapper);
        if (!result.ok() || result.program() == null || result.program().triggers.isEmpty()) {
            return new ResultWithFilter(null, result.errors());
        }
        if (result.program().triggers.get(0).body.isEmpty()) {
            return new ResultWithFilter(null, List.of("没有识别到资源特征条件"));
        }
        Statement statement = result.program().triggers.get(0).body.get(0);
        if (statement instanceof Statement.Input in && !in.limits.isEmpty() && in.limits.get(0).with != null) {
            return new ResultWithFilter(in.limits.get(0).with, List.of());
        }
        return new ResultWithFilter(null, List.of("筛选条件必须以 with 或 without 开头"));
    }

    public record ResultWithFilter(BProgram.WithFilter filter, List<String> errors) {
        public boolean ok() {
            return filter != null && errors.isEmpty();
        }
    }

    /** Flattens left-recursive and/or chains: And(And(a,b),c) → [a,b,c]. */
    private static void flattenBool(List<Bool> into, SFMLParser.BoolexprContext left,
                                    SFMLParser.BoolexprContext right, Class<? extends SFMLParser.BoolexprContext> chainClass) {
        if (chainClass.isInstance(left)) {
            // left operand continues the same chain — recurse (parse tree is left-assoc)
            var sub = (SFMLParser.BoolexprContext) left;
            SFMLParser.BoolexprContext[] pair = chainOperands(sub, chainClass);
            flattenBool(into, pair[0], pair[1], chainClass);
        } else {
            into.add(readBool(left));
        }
        into.add(readBool(right));
    }

    private static SFMLParser.BoolexprContext[] chainOperands(SFMLParser.BoolexprContext ctx,
                                                              Class<? extends SFMLParser.BoolexprContext> chainClass) {
        if (chainClass == SFMLParser.BooleanConjunctionContext.class) {
            var c = (SFMLParser.BooleanConjunctionContext) ctx;
            return new SFMLParser.BoolexprContext[]{c.boolexpr(0), c.boolexpr(1)};
        }
        var d = (SFMLParser.BooleanDisjunctionContext) ctx;
        return new SFMLParser.BoolexprContext[]{d.boolexpr(0), d.boolexpr(1)};
    }

    private static Bool.Comparison readComparisonOp(SFMLParser.ComparisonOpContext ctx) {
        return Bool.Comparison.fromSfml(ctx.getText());
    }

    // ------------------------------------------------------------------ util

    private record PositionedStatement(int tokenIndex, int kindOrder, Statement statement) {
    }

    /**
     * Tracks hidden-channel comments independently of the grammar. SFM quite
     * correctly ignores them while parsing, but a visual editor must put them
     * back into the nearest editable block instead of silently deleting them.
     */
    private static final class CommentIndex {
        private final List<Token> comments;
        private final Set<Integer> used = new HashSet<>();

        private CommentIndex(List<Token> tokens) {
            comments = tokens.stream()
                    .filter(token -> token.getType() == SFMLLexer.LINE_COMMENT)
                    .sorted(Comparator.comparingInt(Token::getTokenIndex))
                    .toList();
        }

        private List<Token> takeDirectComments(SFMLParser.BlockContext block) {
            int lower = previousSiblingStop(block);
            int upper = nextSiblingStart(block);
            List<Token> result = new ArrayList<>();
            for (Token comment : comments) {
                int index = comment.getTokenIndex();
                if (!used.contains(index) && index > lower && index < upper) {
                    used.add(index);
                    result.add(comment);
                }
            }
            return result;
        }

        private List<Token> remaining() {
            return comments.stream()
                    .filter(token -> !used.contains(token.getTokenIndex()))
                    .toList();
        }
    }

    private static void readTopLevelComments(SFMLParser.ProgramContext ctx,
                                             BProgram program,
                                             CommentIndex comments) {
        List<Token> remaining = comments.remaining();
        int nameStart = ctx.name() == null ? Integer.MAX_VALUE : tokenIndex(ctx.name().getStart());
        int nameStop = ctx.name() == null ? -1 : tokenIndex(ctx.name().getStop());
        List<SFMLParser.TriggerContext> triggers = ctx.trigger();

        for (Token comment : remaining) {
            int index = comment.getTokenIndex();
            String text = stripComment(comment.getText());
            if (index < nameStart) {
                program.fileHeaderComments.add(text);
                continue;
            }
            if (triggers.isEmpty() || index < tokenIndex(triggers.get(0).getStart())) {
                // With no NAME declaration, comments before the first trigger
                // are naturally a file header rather than a post-NAME preamble.
                if (nameStop < 0) program.fileHeaderComments.add(text);
                else program.preambleComments.add(text);
                continue;
            }

            Trigger next = null;
            for (int i = 1; i < triggers.size(); i++) {
                if (index < tokenIndex(triggers.get(i).getStart())) {
                    next = program.triggers.get(i);
                    break;
                }
            }
            if (next != null) next.leadingComments.add(text);
            else program.trailingComments.add(text);
        }
    }

    private static int previousSiblingStop(ParserRuleContext context) {
        if (!(context.getParent() instanceof ParserRuleContext parent) || parent.children == null) {
            return -1;
        }
        int position = parent.children.indexOf(context);
        if (position <= 0) return tokenIndex(parent.getStart()) - 1;
        return stopTokenIndex(parent.children.get(position - 1));
    }

    private static int nextSiblingStart(ParserRuleContext context) {
        if (!(context.getParent() instanceof ParserRuleContext parent) || parent.children == null) {
            return Integer.MAX_VALUE;
        }
        int position = parent.children.indexOf(context);
        if (position < 0 || position + 1 >= parent.children.size()) {
            return tokenIndex(parent.getStop()) + 1;
        }
        return startTokenIndex(parent.children.get(position + 1));
    }

    private static int startTokenIndex(ParseTree tree) {
        if (tree instanceof TerminalNode terminal) return tokenIndex(terminal.getSymbol());
        if (tree instanceof ParserRuleContext context) return tokenIndex(context.getStart());
        return -1;
    }

    private static int stopTokenIndex(ParseTree tree) {
        if (tree instanceof TerminalNode terminal) return tokenIndex(terminal.getSymbol());
        if (tree instanceof ParserRuleContext context) return tokenIndex(context.getStop());
        return -1;
    }

    private static int tokenIndex(Token token) {
        return token == null ? -1 : token.getTokenIndex();
    }

    private static String stripComment(String source) {
        String text = source == null ? "" : source;
        if (text.startsWith("--")) text = text.substring(2);
        if (text.startsWith(" ")) text = text.substring(1);
        return text.replace("\r", "").replace("\n", "").stripTrailing();
    }

    private static String rawText(org.antlr.v4.runtime.ParserRuleContext ctx) {
        int start = ctx.getStart().getStartIndex();
        int stop = ctx.getStop().getStopIndex();
        return ctx.getStart().getInputStream().getText(new Interval(start, stop));
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"");
        }
        return s;
    }
}
