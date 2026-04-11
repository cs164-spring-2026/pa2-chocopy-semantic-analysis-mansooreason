package chocopy.pa2;

import java.util.ArrayList;
import java.util.List;

import chocopy.common.analysis.AbstractNodeAnalyzer;
import chocopy.common.analysis.types.FuncType;
import chocopy.common.analysis.types.ValueType;
import chocopy.common.astnodes.ClassDef;
import chocopy.common.astnodes.Declaration;
import chocopy.common.astnodes.FuncDef;
import chocopy.common.astnodes.Identifier;
import chocopy.common.astnodes.Program;
import chocopy.common.astnodes.TypedVar;
import chocopy.common.astnodes.VarDef;

public class GlobalDeclarationAnalyzer extends AbstractNodeAnalyzer<Void> {
    private final SemanticContext ctx;
    private final TypeSystem ts;

    public GlobalDeclarationAnalyzer(SemanticContext ctx) {
        this.ctx = ctx;
        this.ts = new TypeSystem(ctx);
    }

    private void err(Identifier id, String msg, Object... args) {
        ctx.errors.semError(id, msg, args);
    }

    @Override
    public Void analyze(Program program) {
        for (Declaration decl : program.declarations) {
            decl.dispatch(this);
        }
        return null;
    }

    @Override
    public Void analyze(ClassDef classDef) {
        return null;
    }

    @Override
    public Void analyze(VarDef varDef) {
        String name = varDef.var.identifier.name;

        if (ctx.classes.containsKey(name)) {
            err(varDef.var.identifier, "Cannot shadow class name: %s", name);
            return null;
        }

        if (ctx.globals.declares(name) || ctx.globalFunctions.containsKey(name)) {
            err(varDef.var.identifier,
                    "Duplicate declaration of identifier in same scope: %s",
                    name);
            return null;
        }

        ValueType vt = ts.fromAnnotation(varDef.var.type);
        ctx.globals.put(name, vt);
        return null;
    }

    @Override
    public Void analyze(FuncDef funcDef) {
        String name = funcDef.name.name;

        if (ctx.classes.containsKey(name)) {
            err(funcDef.name, "Cannot shadow class name: %s", name);
            return null;
        }

        if (ctx.globals.declares(name) || ctx.globalFunctions.containsKey(name)) {
            err(funcDef.name,
                    "Duplicate declaration of identifier in same scope: %s",
                    name);
            return null;
        }

        List<ValueType> params = new ArrayList<>();
        for (TypedVar param : funcDef.params) {
            params.add(ts.fromAnnotation(param.type));
        }

        ValueType ret = ts.fromAnnotation(funcDef.returnType);
        ctx.globals.put(name, new FuncType(params, ret));
        ctx.globalFunctions.put(name, funcDef);
        return null;
    }
}