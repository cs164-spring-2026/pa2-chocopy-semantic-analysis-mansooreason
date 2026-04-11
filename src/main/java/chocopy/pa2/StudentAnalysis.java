package chocopy.pa2;

import chocopy.common.astnodes.Program;

public class StudentAnalysis {

    public static Program process(Program program, boolean debug) {
        if (program.hasErrors()) {
            return program;
        }

        SemanticContext ctx = new SemanticContext(program.errors);
        seedBuiltins(ctx);

        program.dispatch(new ClassNameCollector(ctx));
        program.dispatch(new GlobalDeclarationAnalyzer(ctx));
        program.dispatch(new AnnotationValidator(ctx));
        program.dispatch(new ClassAnalyzer(ctx));
        program.dispatch(new TypeChecker(ctx));

        return program;
    }

    private static void seedBuiltins(SemanticContext ctx) {
        ctx.classes.put("object", new ClassInfo("object", null));
        ctx.classes.put("int", new ClassInfo("int", "object"));
        ctx.classes.put("bool", new ClassInfo("bool", "object"));
        ctx.classes.put("str", new ClassInfo("str", "object"));
    }
}