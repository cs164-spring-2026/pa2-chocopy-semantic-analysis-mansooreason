package chocopy.pa2;

import chocopy.common.analysis.AbstractNodeAnalyzer;
import chocopy.common.astnodes.ClassDef;
import chocopy.common.astnodes.ClassType;
import chocopy.common.astnodes.Declaration;
import chocopy.common.astnodes.FuncDef;
import chocopy.common.astnodes.ListType;
import chocopy.common.astnodes.Program;
import chocopy.common.astnodes.TypeAnnotation;
import chocopy.common.astnodes.TypedVar;
import chocopy.common.astnodes.VarDef;

public class AnnotationValidator extends AbstractNodeAnalyzer<Void> {
    private final SemanticContext ctx;
    private final TypeSystem ts;

    public AnnotationValidator(SemanticContext ctx) {
        this.ctx = ctx;
        this.ts = new TypeSystem(ctx);
    }

    private void validate(TypeAnnotation ann) {
        if (ann instanceof ClassType) {
            String name = ((ClassType) ann).className;
            if (!ts.isDefinedClassName(name)) {
                ctx.errors.semError(ann,
                        "Invalid type annotation; there is no class named: %s",
                        name);
            }
        } else {
            validate(((ListType) ann).elementType);
        }
    }

    @Override
    public Void analyze(Program program) {
        for (Declaration decl : program.declarations) {
            decl.dispatch(this);
        }
        return null;
    }

    @Override
    public Void analyze(VarDef varDef) {
        validate(varDef.var.type);
        return null;
    }

    @Override
    public Void analyze(FuncDef funcDef) {
        for (TypedVar p : funcDef.params) {
            validate(p.type);
        }
        validate(funcDef.returnType);

        for (Declaration d : funcDef.declarations) {
            d.dispatch(this);
        }
        return null;
    }

    @Override
    public Void analyze(ClassDef classDef) {
        for (Declaration d : classDef.declarations) {
            d.dispatch(this);
        }
        return null;
    }
}