package chocopy.pa2;

import chocopy.common.analysis.AbstractNodeAnalyzer;
import chocopy.common.astnodes.ClassDef;
import chocopy.common.astnodes.Declaration;
import chocopy.common.astnodes.Program;

public class ClassNameCollector extends AbstractNodeAnalyzer<Void> {
    private final SemanticContext ctx;
    private final TypeSystem ts;

    public ClassNameCollector(SemanticContext ctx) {
        this.ctx = ctx;
        this.ts = new TypeSystem(ctx);
    }

    @Override
    public Void analyze(Program program) {
        for (Declaration decl : program.declarations) {
            if (decl instanceof ClassDef) {
                decl.dispatch(this);
            }
        }
        return null;
    }

    @Override
    public Void analyze(ClassDef c) {
        String name = c.name.name;

        if (ctx.classes.containsKey(name)) {
            ctx.errors.semError(c.name,
                    "Duplicate declaration of identifier in same scope: %s",
                    name);
            return null;
        }

        ctx.classes.put(name, new ClassInfo(name, c.superClass.name));
        return null;
    }
}