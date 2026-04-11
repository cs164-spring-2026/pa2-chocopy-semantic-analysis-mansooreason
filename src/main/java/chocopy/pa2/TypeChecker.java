package chocopy.pa2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import chocopy.common.analysis.AbstractNodeAnalyzer;
import chocopy.common.analysis.SymbolTable;
import chocopy.common.analysis.types.FuncType;
import chocopy.common.analysis.types.ListValueType;
import chocopy.common.analysis.types.Type;
import chocopy.common.analysis.types.ValueType;
import chocopy.common.astnodes.AssignStmt;
import chocopy.common.astnodes.BinaryExpr;
import chocopy.common.astnodes.BooleanLiteral;
import chocopy.common.astnodes.CallExpr;
import chocopy.common.astnodes.ClassDef;
import chocopy.common.astnodes.Declaration;
import chocopy.common.astnodes.Expr;
import chocopy.common.astnodes.ExprStmt;
import chocopy.common.astnodes.ForStmt;
import chocopy.common.astnodes.FuncDef;
import chocopy.common.astnodes.GlobalDecl;
import chocopy.common.astnodes.Identifier;
import chocopy.common.astnodes.IfExpr;
import chocopy.common.astnodes.IfStmt;
import chocopy.common.astnodes.IndexExpr;
import chocopy.common.astnodes.IntegerLiteral;
import chocopy.common.astnodes.ListExpr;
import chocopy.common.astnodes.MemberExpr;
import chocopy.common.astnodes.MethodCallExpr;
import chocopy.common.astnodes.Node;
import chocopy.common.astnodes.NonLocalDecl;
import chocopy.common.astnodes.NoneLiteral;
import chocopy.common.astnodes.Program;
import chocopy.common.astnodes.ReturnStmt;
import chocopy.common.astnodes.Stmt;
import chocopy.common.astnodes.StringLiteral;
import chocopy.common.astnodes.TypedVar;
import chocopy.common.astnodes.UnaryExpr;
import chocopy.common.astnodes.VarDef;
import chocopy.common.astnodes.WhileStmt;

import static chocopy.common.analysis.types.Type.BOOL_TYPE;
import static chocopy.common.analysis.types.Type.EMPTY_TYPE;
import static chocopy.common.analysis.types.Type.INT_TYPE;
import static chocopy.common.analysis.types.Type.NONE_TYPE;
import static chocopy.common.analysis.types.Type.OBJECT_TYPE;
import static chocopy.common.analysis.types.Type.STR_TYPE;

public class TypeChecker extends AbstractNodeAnalyzer<Type> {
    private final SemanticContext ctx;
    private final TypeSystem ts;

    private SymbolTable<Type> locals = null;
    private ValueType currentReturnType = null;
    private boolean insideFunction = false;
    private String currentClass = null;

    public TypeChecker(SemanticContext ctx) {
        this.ctx = ctx;
        this.ts = new TypeSystem(ctx);
    }

    private void err(Node node, String message, Object... args) {
        ctx.errors.semError(node, message, args);
    }

    private Type lookup(String name) {
        if (locals != null) {
            Type t = locals.get(name);
            if (t != null) return t;
        }
        return ctx.globals.get(name);
    }

    private ClassInfo getClassInfoFromType(Type t) {
        if (t == null || t.className() == null) return null;
        return ctx.classes.get(t.className());
    }

    private ValueType lookupAttribute(String className, String attr) {
        while (className != null) {
            ClassInfo ci = ctx.classes.get(className);
            if (ci == null) return null;
            if (ci.attributes.containsKey(attr)) return ci.attributes.get(attr);
            className = ci.superClassName;
        }
        return null;
    }

    private FuncSignature lookupMethod(String className, String method) {
        while (className != null) {
            ClassInfo ci = ctx.classes.get(className);
            if (ci == null) return null;
            if (ci.methods.containsKey(method)) return ci.methods.get(method);
            className = ci.superClassName;
        }
        return null;
    }

    @Override
    public Type analyze(Program program) {
        for (Declaration decl : program.declarations) {
            decl.dispatch(this);
        }
        for (Stmt stmt : program.statements) {
            stmt.dispatch(this);
        }
        return null;
    }

    @Override
    public Type analyze(ClassDef c) {
        String oldClass = currentClass;
        currentClass = c.name.name;

        for (Declaration d : c.declarations) {
            if (d instanceof VarDef) {
                d.dispatch(this);
            } else if (d instanceof FuncDef) {
                analyzeFunctionBody((FuncDef) d, c.name.name);
            }
        }

        currentClass = oldClass;
        return null;
    }

    @Override
    public Type analyze(FuncDef f) {
        analyzeFunctionBody(f, null);
        return null;
    }

    private void analyzeFunctionBody(FuncDef f, String className) {
        SymbolTable<Type> oldLocals = locals;
        ValueType oldReturnType = currentReturnType;
        boolean oldInsideFunction = insideFunction;
        String oldClass = currentClass;

        locals = new SymbolTable<>(ctx.globals);
        currentReturnType = ts.fromAnnotation(f.returnType);
        insideFunction = true;
        currentClass = className;

        Set<String> declared = new HashSet<>();

        for (TypedVar param : f.params) {
            String name = param.identifier.name;
            if (ctx.classes.containsKey(name)) {
                err(param.identifier, "Cannot shadow class name: %s", name);
            }
            if (!declared.add(name)) {
                err(param.identifier, "Duplicate declaration of identifier in same scope: %s", name);
            } else {
                locals.put(name, ts.fromAnnotation(param.type));
            }
        }

        for (Declaration d : f.declarations) {
            if (d instanceof VarDef) {
                VarDef vd = (VarDef) d;
                String name = vd.var.identifier.name;

                if (ctx.classes.containsKey(name)) {
                    err(vd.var.identifier, "Cannot shadow class name: %s", name);
                }

                if (!declared.add(name)) {
                    err(vd.var.identifier, "Duplicate declaration of identifier in same scope: %s", name);
                } else {
                    locals.put(name, ts.fromAnnotation(vd.var.type));
                }
            } else if (d instanceof GlobalDecl) {
                GlobalDecl gd = (GlobalDecl) d;
                if (ctx.globals.get(gd.variable.name) == null) {
                    err(gd.variable, "Not a global variable: %s", gd.variable.name);
                }
            } else if (d instanceof NonLocalDecl) {
                NonLocalDecl nd = (NonLocalDecl) d;
                err(nd.variable, "Not a nonlocal variable: %s", nd.variable.name);
            } else if (d instanceof FuncDef) {
                FuncDef nested = (FuncDef) d;
                String name = nested.name.name;
                if (!declared.add(name)) {
                    err(nested.name, "Duplicate declaration of identifier in same scope: %s", name);
                } else {
                    List<ValueType> paramTypes = new ArrayList<>();
                    for (TypedVar p : nested.params) {
                        paramTypes.add(ts.fromAnnotation(p.type));
                    }
                    locals.put(name, new FuncType(paramTypes, ts.fromAnnotation(nested.returnType)));
                }
            }
        }

        for (Declaration d : f.declarations) {
            if (!(d instanceof FuncDef)) {
                d.dispatch(this);
            }
        }
        for (Stmt s : f.statements) {
            s.dispatch(this);
        }

        for (Declaration d : f.declarations) {
            if (d instanceof FuncDef) {
                analyzeFunctionBody((FuncDef) d, className);
            }
        }

        if (currentReturnType != null && currentReturnType.isSpecialType()) {
            if (!alwaysReturns(f.statements)) {
                err(f.name, "All paths in this function/method must have a return statement: %s", f.name.name);
            }
        }

        locals = oldLocals;
        currentReturnType = oldReturnType;
        insideFunction = oldInsideFunction;
        currentClass = oldClass;
    }

    private boolean alwaysReturns(List<Stmt> stmts) {
        for (Stmt s : stmts) {
            if (s instanceof ReturnStmt) {
                return true;
            }
            if (s instanceof IfStmt) {
                IfStmt ifs = (IfStmt) s;
                if (alwaysReturns(ifs.thenBody) && alwaysReturns(ifs.elseBody)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Type analyze(GlobalDecl d) {
        return null;
    }

    @Override
    public Type analyze(NonLocalDecl d) {
        return null;
    }

    @Override
    public Type analyze(VarDef varDef) {
        Type declared = ts.fromAnnotation(varDef.var.type);
        Type valueType = varDef.value.dispatch(this);

        if (declared instanceof ValueType && valueType instanceof ValueType) {
            if (!ts.isAssignable((ValueType) valueType, (ValueType) declared)) {
                err(varDef, "Expected type `%s`; got type `%s`", declared, valueType);
            }
        }

        return null;
    }

    @Override
    public Type analyze(ExprStmt s) {
        s.expr.dispatch(this);
        return null;
    }

    @Override
    public Type analyze(IntegerLiteral i) {
        return i.setInferredType(INT_TYPE);
    }

    @Override
    public Type analyze(BooleanLiteral b) {
        return b.setInferredType(BOOL_TYPE);
    }

    @Override
    public Type analyze(StringLiteral s) {
        return s.setInferredType(STR_TYPE);
    }

    @Override
    public Type analyze(NoneLiteral n) {
        return n.setInferredType(NONE_TYPE);
    }

    @Override
    public Type analyze(Identifier id) {
        String varName = id.name;
        Type varType = lookup(varName);

        if (varType != null && varType.isValueType()) {
            return id.setInferredType(varType);
        }

        err(id, "Not a variable: %s", varName);
        return id.setInferredType(OBJECT_TYPE);
    }

    @Override
    public Type analyze(UnaryExpr e) {
        Type operandType = e.operand.dispatch(this);

        switch (e.operator) {
            case "-":
                if (INT_TYPE.equals(operandType)) {
                    return e.setInferredType(INT_TYPE);
                } else {
                    err(e, "Cannot apply operator `%s` on type `%s`", e.operator, operandType);
                    return e.setInferredType(INT_TYPE);
                }
            case "not":
                if (BOOL_TYPE.equals(operandType)) {
                    return e.setInferredType(BOOL_TYPE);
                } else {
                    err(e, "Cannot apply operator `%s` on type `%s`", e.operator, operandType);
                    return e.setInferredType(BOOL_TYPE);
                }
            default:
                return e.setInferredType(OBJECT_TYPE);
        }
    }

    @Override
    public Type analyze(BinaryExpr e) {
        Type t1 = e.left.dispatch(this);
        Type t2 = e.right.dispatch(this);

        switch (e.operator) {
            case "-":
            case "*":
            case "//":
            case "%":
                if (INT_TYPE.equals(t1) && INT_TYPE.equals(t2)) {
                    return e.setInferredType(INT_TYPE);
                } else {
                    err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                            e.operator, t1, t2);
                    return e.setInferredType(INT_TYPE);
                }

            case "+":
                if (INT_TYPE.equals(t1) && INT_TYPE.equals(t2)) {
                    return e.setInferredType(INT_TYPE);
                } else if (STR_TYPE.equals(t1) && STR_TYPE.equals(t2)) {
                    return e.setInferredType(STR_TYPE);
                } else if (t1.equals(EMPTY_TYPE) && t2 instanceof ListValueType) {
                    return e.setInferredType(t2);
                } else if (t2.equals(EMPTY_TYPE) && t1 instanceof ListValueType) {
                    return e.setInferredType(t1);
                } else if (t1 instanceof ListValueType && t2 instanceof ListValueType) {
                    ValueType j = ts.join(((ListValueType) t1).elementType, ((ListValueType) t2).elementType);
                    return e.setInferredType(new ListValueType(j));
                } else {
                    err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                            e.operator, t1, t2);
                    if (INT_TYPE.equals(t1) || INT_TYPE.equals(t2)) {
                        return e.setInferredType(INT_TYPE);
                    }
                    return e.setInferredType(OBJECT_TYPE);
                }

            case "and":
            case "or":
                if (BOOL_TYPE.equals(t1) && BOOL_TYPE.equals(t2)) {
                    return e.setInferredType(BOOL_TYPE);
                } else {
                    err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                            e.operator, t1, t2);
                    return e.setInferredType(BOOL_TYPE);
                }

            case "<":
            case "<=":
            case ">":
            case ">=":
                if ((INT_TYPE.equals(t1) && INT_TYPE.equals(t2))
                        || (STR_TYPE.equals(t1) && STR_TYPE.equals(t2))) {
                    return e.setInferredType(BOOL_TYPE);
                } else {
                    err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                            e.operator, t1, t2);
                    return e.setInferredType(BOOL_TYPE);
                }

            case "==":
            case "!=":
                if ((INT_TYPE.equals(t1) && INT_TYPE.equals(t2))
                        || (BOOL_TYPE.equals(t1) && BOOL_TYPE.equals(t2))
                        || (STR_TYPE.equals(t1) && STR_TYPE.equals(t2))
                        || (NONE_TYPE.equals(t1) && NONE_TYPE.equals(t2))
                        || (t1 instanceof ListValueType && t2 instanceof ListValueType)) {
                    return e.setInferredType(BOOL_TYPE);
                } else {
                    err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                            e.operator, t1, t2);
                    return e.setInferredType(BOOL_TYPE);
                }

            case "is":
                return e.setInferredType(BOOL_TYPE);

            default:
                return e.setInferredType(OBJECT_TYPE);
        }
    }

    @Override
    public Type analyze(IfExpr e) {
        Type condType = e.condition.dispatch(this);
        Type thenType = e.thenExpr.dispatch(this);
        Type elseType = e.elseExpr.dispatch(this);

        if (!BOOL_TYPE.equals(condType)) {
            err(e.condition, "Condition expression cannot be of type `%s`", condType);
        }

        if (thenType instanceof ValueType && elseType instanceof ValueType) {
            return e.setInferredType(ts.join((ValueType) thenType, (ValueType) elseType));
        }

        return e.setInferredType(OBJECT_TYPE);
    }

    @Override
    public Type analyze(ListExpr e) {
        List<Expr> elems = e.elements;

        if (elems.isEmpty()) {
            return e.setInferredType(EMPTY_TYPE);
        }

        ValueType cur = null;
        for (Expr elem : elems) {
            Type t = elem.dispatch(this);
            if (t instanceof ValueType) {
                if (cur == null) cur = (ValueType) t;
                else cur = ts.join(cur, (ValueType) t);
            }
        }

        if (cur == null) cur = OBJECT_TYPE;
        return e.setInferredType(new ListValueType(cur));
    }

    @Override
    public Type analyze(IndexExpr e) {
        Type listType = e.list.dispatch(this);
        Type indexType = e.index.dispatch(this);

        if (!INT_TYPE.equals(indexType)) {
            err(e.index, "Index is of non-integer type `%s`", indexType);
        }

        if (listType instanceof ListValueType) {
            return e.setInferredType(((ListValueType) listType).elementType);
        } else if (STR_TYPE.equals(listType)) {
            return e.setInferredType(STR_TYPE);
        } else {
            err(e.list, "Cannot index into type `%s`", listType);
            return e.setInferredType(OBJECT_TYPE);
        }
    }

    @Override
    public Type analyze(CallExpr e) {
        for (Expr arg : e.args) {
            arg.dispatch(this);
        }

        if (ctx.classes.containsKey(e.function.name)) {
            ClassInfo ci = ctx.classes.get(e.function.name);
            FuncSignature initSig = lookupMethod(ci.name, "__init__");
            int expected = 0;
            if (initSig != null) expected = Math.max(0, initSig.params.size() - 1);

            if (e.args.size() != expected) {
                err(e, "Expected %d arguments; got %d", expected, e.args.size());
            } else if (initSig != null) {
                for (int i = 0; i < e.args.size(); i++) {
                    Type argType = e.args.get(i).getInferredType();
                    ValueType paramType = initSig.params.get(i + 1);
                    if (!(argType instanceof ValueType) || !ts.isAssignable((ValueType) argType, paramType)) {
                        err(e, "Expected type `%s`; got type `%s` in parameter %d",
                                paramType, argType, i);
                        break;
                    }
                }
            }

            return e.setInferredType(ts.fromAnnotation(
                    new chocopy.common.astnodes.ClassType(null, null, e.function.name)
            ));
        }

        Type fnType = lookup(e.function.name);

        if (!(fnType instanceof FuncType)) {
            err(e.function, "Not a function: %s", e.function.name);
            return e.setInferredType(OBJECT_TYPE);
        }

        FuncType ft = (FuncType) fnType;

        if (e.args.size() != ft.parameters.size()) {
            err(e, "Expected %d arguments; got %d", ft.parameters.size(), e.args.size());
            return e.setInferredType(ft.returnType);
        }

        for (int i = 0; i < e.args.size(); i++) {
            Type argType = e.args.get(i).getInferredType();
            ValueType paramType = ft.parameters.get(i);

            if (!(argType instanceof ValueType) || !ts.isAssignable((ValueType) argType, paramType)) {
                err(e, "Expected type `%s`; got type `%s` in parameter %d",
                        paramType, argType, i);
                return e.setInferredType(ft.returnType);
            }
        }

        return e.setInferredType(ft.returnType);
    }

    @Override
    public Type analyze(MemberExpr e) {
        Type objType = e.object.dispatch(this);
        ClassInfo ci = getClassInfoFromType(objType);

        if (ci == null) {
            err(e.member, "There is no attribute named `%s` in class `%s`",
                    e.member.name, objType);
            return e.setInferredType(OBJECT_TYPE);
        }

        ValueType attrType = lookupAttribute(ci.name, e.member.name);
        if (attrType != null) {
            return e.setInferredType(attrType);
        }

        err(e.member, "There is no attribute named `%s` in class `%s`",
                e.member.name, ci.name);
        return e.setInferredType(OBJECT_TYPE);
    }

    @Override
    public Type analyze(MethodCallExpr e) {
        Type objType = e.method.object.dispatch(this);
        ClassInfo ci = getClassInfoFromType(objType);

        for (Expr arg : e.args) {
            arg.dispatch(this);
        }

        if (ci == null) {
            err(e.method.member, "There is no method named `%s` in class `%s`",
                    e.method.member.name, objType);
            return e.setInferredType(OBJECT_TYPE);
        }

        FuncSignature sig = lookupMethod(ci.name, e.method.member.name);
        if (sig == null) {
            err(e.method.member, "There is no method named `%s` in class `%s`",
                    e.method.member.name, ci.name);
            return e.setInferredType(OBJECT_TYPE);
        }

        int expected = Math.max(0, sig.params.size() - 1);
        if (e.args.size() != expected) {
            err(e, "Expected %d arguments; got %d", expected, e.args.size());
            return e.setInferredType(sig.returnType);
        }

        for (int i = 0; i < e.args.size(); i++) {
            Type argType = e.args.get(i).getInferredType();
            ValueType paramType = sig.params.get(i + 1);
            if (!(argType instanceof ValueType) || !ts.isAssignable((ValueType) argType, paramType)) {
                err(e, "Expected type `%s`; got type `%s` in parameter %d",
                        paramType, argType, i);
                return e.setInferredType(sig.returnType);
            }
        }

        return e.setInferredType(sig.returnType);
    }

    @Override
    public Type analyze(AssignStmt stmt) {
        Type valueType = stmt.value.dispatch(this);

        for (Expr target : stmt.targets) {
            Type targetType = null;

            if (target instanceof Identifier) {
                Identifier id = (Identifier) target;
                targetType = lookup(id.name);
                if (targetType == null || !targetType.isValueType()) {
                    err(target, "Not a variable: %s", id.name);
                    continue;
                }
            } else if (target instanceof IndexExpr) {
                IndexExpr ie = (IndexExpr) target;
                Type baseType = ie.list.dispatch(this);
                Type idxType = ie.index.dispatch(this);

                if (!INT_TYPE.equals(idxType)) {
                    err(ie.index, "Index is of non-integer type `%s`", idxType);
                }

                if (baseType instanceof ListValueType) {
                    targetType = ((ListValueType) baseType).elementType;
                } else {
                    err(target, "Not a variable: %s", target);
                    continue;
                }
            } else if (target instanceof MemberExpr) {
                targetType = target.dispatch(this);
            } else {
                err(target, "Not a variable: %s", target);
                continue;
            }

            target.setInferredType(targetType);

            if (targetType instanceof ValueType && valueType instanceof ValueType) {
                if (!ts.isAssignable((ValueType) valueType, (ValueType) targetType)) {
                    err(stmt, "Expected type `%s`; got type `%s`", targetType, valueType);
                    break;
                }
            }
        }

        return null;
    }

    @Override
    public Type analyze(ReturnStmt stmt) {
        if (!insideFunction) {
            err(stmt, "Return statement cannot appear at the top level");
            return null;
        }

        Type actual = (stmt.value == null) ? NONE_TYPE : stmt.value.dispatch(this);

        if (currentReturnType != null && actual instanceof ValueType) {
            if (!ts.isAssignable((ValueType) actual, currentReturnType)) {
                err(stmt, "Expected type `%s`; got type `%s`", currentReturnType, actual);
            }
        }

        return null;
    }

    @Override
    public Type analyze(IfStmt stmt) {
        Type condType = stmt.condition.dispatch(this);
        if (!BOOL_TYPE.equals(condType)) {
            err(stmt.condition, "Condition expression cannot be of type `%s`", condType);
        }

        for (Stmt s : stmt.thenBody) {
            s.dispatch(this);
        }
        for (Stmt s : stmt.elseBody) {
            s.dispatch(this);
        }

        return null;
    }

    @Override
    public Type analyze(WhileStmt stmt) {
        Type condType = stmt.condition.dispatch(this);
        if (!BOOL_TYPE.equals(condType)) {
            err(stmt.condition, "Condition expression cannot be of type `%s`", condType);
        }

        for (Stmt s : stmt.body) {
            s.dispatch(this);
        }

        return null;
    }

    @Override
    public Type analyze(ForStmt stmt) {
        Type iterableType = stmt.iterable.dispatch(this);
        Type idType = lookup(stmt.identifier.name);

        if (STR_TYPE.equals(iterableType)) {
            if (!STR_TYPE.equals(idType)) {
                err(stmt.identifier, "Expected type `%s`; got type `%s`", STR_TYPE, idType);
            }
        } else if (iterableType instanceof ListValueType) {
            ValueType elemType = ((ListValueType) iterableType).elementType;
            if (!(idType instanceof ValueType) || !ts.isAssignable(elemType, (ValueType) idType)) {
                err(stmt.identifier, "Expected type `%s`; got type `%s`", elemType, idType);
            }
        } else {
            err(stmt.iterable, "Cannot iterate over value of type `%s`", iterableType);
        }

        for (Stmt s : stmt.body) {
            s.dispatch(this);
        }

        return null;
    }
}