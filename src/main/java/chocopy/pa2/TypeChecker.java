package chocopy.pa2;

import chocopy.common.analysis.AbstractNodeAnalyzer;
import chocopy.common.analysis.SymbolTable;
import chocopy.common.analysis.types.ClassValueType;
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
import chocopy.common.astnodes.Errors;
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
import chocopy.common.astnodes.NoneLiteral;
import chocopy.common.astnodes.NonLocalDecl;
import chocopy.common.astnodes.Program;
import chocopy.common.astnodes.ReturnStmt;
import chocopy.common.astnodes.Stmt;
import chocopy.common.astnodes.StringLiteral;
import chocopy.common.astnodes.TypedVar;
import chocopy.common.astnodes.UnaryExpr;
import chocopy.common.astnodes.VarDef;
import chocopy.common.astnodes.WhileStmt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static chocopy.common.analysis.types.Type.BOOL_TYPE;
import static chocopy.common.analysis.types.Type.INT_TYPE;
import static chocopy.common.analysis.types.Type.NONE_TYPE;
import static chocopy.common.analysis.types.Type.OBJECT_TYPE;
import static chocopy.common.analysis.types.Type.STR_TYPE;

/**
 * Pass 2 of semantic analysis: type checking.
 *
 * Uses the symbol table and ClassHierarchy populated by DeclarationAnalyzer (Pass 1)
 * to annotate every expression node with its inferred ValueType. Errors are reported
 * using the most-specific-type recovery strategy described in Section 5.3.2:
 * even when a premise of a typing rule fails, we infer the most specific type possible
 * from the remaining premises so that downstream type checks stay accurate.
 *
 * Key behaviors:
 *  - Constructor CallExpr: e.function is NOT annotated (stays null).
 *    If the class has member errors, e itself is also not annotated.
 *  - MethodCallExpr error on missing method: error on the MethodCallExpr node,
 *    e.method (the MemberExpr) gets no inferredType.
 *  - Assignment errors: leftmost-erroneous-target rule (spec Section 5.3.2).
 *  - "in parameter N" index: N uses 0-offset for functions, 1-offset for methods
 *    (so self is counted as parameter 0 in the error message).
 *  - Classes with member errors (tracked in ClassHierarchy): the entire class body
 *    is skipped during type-checking, matching the reference implementation.
 */
public class TypeChecker extends AbstractNodeAnalyzer<Type> {

    /** The global symbol table from Pass 1. */
    private final SymbolTable<Type> globals;

    /** The currently active symbol table (may be a function-local scope). */
    private SymbolTable<Type> sym;

    private final Errors errors;
    private final ClassHierarchy hierarchy;

    /** Names of all module-level (top-level) variable declarations. */
    private final Set<String> moduleVarNames = new HashSet<>();

    /**
     * Names explicitly declared with "global x" in the current function.
     * Assignments to these names are allowed even though they are not in locallyDeclared.
     */
    private Set<String> currentGlobals = new HashSet<>();

    /**
     * Names explicitly declared with "nonlocal x" in the current function.
     * Assignments to these names are allowed even though they are not in locallyDeclared.
     */
    private Set<String> currentNonlocals = new HashSet<>();

    /**
     * All names declared locally in the current function (params + VarDefs + FuncDefs + global/nonlocal decls).
     * Used to enforce Rule 8: assignments to variables not locally declared are rejected.
     */
    private Set<String> locallyDeclared = null;

    /** Expected return type of the current function, or null at top level. */
    private ValueType currentReturnType = null;

    /** True when processing inside a function or method body. */
    private boolean inFunction = false;

    /**
     * True when processing inside a class body (not a method).
     * Affects where VarDef type mismatch errors are attached (on the VarDef node vs. its value).
     */
    private boolean inClassBody = false;

    /**
     * Stack of enclosing FuncDef nodes, used to resolve "nonlocal x" references
     * by walking up the nesting hierarchy.
     */
    private final List<FuncDef> functionStack = new ArrayList<>();

    public TypeChecker(SymbolTable<Type> globalSymbols, Errors errors,
                       ClassHierarchy hierarchy) {
        this.globals   = globalSymbols;
        this.sym       = globalSymbols;
        this.errors    = errors;
        this.hierarchy = hierarchy;
    }

    /** Convenience wrapper for reporting a semantic error. */
    private void err(Node node, String fmt, Object... args) {
        errors.semError(node, fmt, args);
    }

    // -----------------------------------------------------------------------
    // Program
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(Program program) {
        // Collect module-level variable names so GlobalDecl resolution works in TypeChecker.
        moduleVarNames.clear();
        for (Declaration d : program.declarations) {
            if (d instanceof VarDef) {
                moduleVarNames.add(((VarDef) d).var.identifier.name);
            }
        }
        for (Declaration d : program.declarations) d.dispatch(this);
        for (Stmt s : program.statements)          s.dispatch(this);
        return null;
    }

    // -----------------------------------------------------------------------
    // Declarations
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(VarDef varDef) {
        ValueType declared = ValueType.annotationToValueType(varDef.var.type);
        Type initType = varDef.value.dispatch(this);
        if (initType instanceof ValueType) {
            if (!hierarchy.isSubtype((ValueType) initType, declared)) {
                // In a class body, attach the error to the VarDef node itself;
                // elsewhere, attach it to the initializer expression.
                Node errorNode = inClassBody ? varDef : varDef.value;
                err(errorNode, "Expected type `%s`; got type `%s`", declared, initType);
            }
        }
        return null;
    }

    /**
     * Type-checks a class body.
     *
     * If the class had any member declaration errors (tracked by ClassHierarchy),
     * the entire body is skipped. This matches the reference implementation's behavior:
     * no inferredType annotations appear inside the class, and the constructor call
     * expression is also left unannotated (handled in analyze(CallExpr)).
     */
    @Override
    public Type analyze(ClassDef classDef) {
        String className = classDef.name.name;

        // Skip body entirely if any member had a declaration error.
        if (hierarchy.classHasMemberErrors(className)) {
            return null;
        }

        SymbolTable<Type> prevSym = sym;
        sym = new SymbolTable<>(prevSym);

        // Populate the class scope with all inherited and own members so that
        // method bodies can reference self.attr and self.method() via the type checker.
        for (String c = className; c != null; c = hierarchy.getParent(c)) {
            for (String mName : hierarchy.directMemberNames(c)) {
                if (!sym.declares(mName)) {
                    sym.put(mName, hierarchy.getMember(c, mName));
                }
            }
        }

        boolean prevInClass = inClassBody;
        inClassBody = true;
        for (Declaration d : classDef.declarations) d.dispatch(this);
        inClassBody = prevInClass;

        sym = prevSym;
        return null;
    }

    @Override
    public Type analyze(FuncDef funcDef) {
        ValueType retType = ValueType.annotationToValueType(funcDef.returnType);

        // Save and update all context state for this function scope.
        ValueType         prevRet    = currentReturnType;
        boolean           prevInFun  = inFunction;
        boolean           prevInCls  = inClassBody;
        SymbolTable<Type> prevSym    = sym;
        Set<String>       prevLocals = locallyDeclared;
        Set<String>       prevGlobs  = currentGlobals;
        Set<String>       prevNl     = currentNonlocals;

        currentReturnType = retType;
        inFunction        = true;
        inClassBody       = false;
        locallyDeclared   = new HashSet<>();
        currentGlobals    = new HashSet<>();
        currentNonlocals  = new HashSet<>();

        sym = new SymbolTable<>(prevSym);
        functionStack.add(funcDef);

        // Add parameters to the local scope.
        for (TypedVar p : funcDef.params) {
            String pName = p.identifier.name;
            sym.put(pName, ValueType.annotationToValueType(p.type));
            locallyDeclared.add(pName);
        }

        // Populate the local scope with all declarations in the function body.
        for (Declaration decl : funcDef.declarations) {
            String dName = decl.getIdentifier().name;
            locallyDeclared.add(dName);

            if (decl instanceof VarDef) {
                sym.put(dName, ValueType.annotationToValueType(
                        ((VarDef) decl).var.type));
            } else if (decl instanceof FuncDef) {
                sym.put(dName, DeclarationAnalyzer.funcTypeFromFuncDef((FuncDef) decl));
            } else if (decl instanceof GlobalDecl) {
                // "global x" makes x an alias for the global variable.
                String gName = ((GlobalDecl) decl).variable.name;
                if (moduleVarNames.contains(gName)) {
                    currentGlobals.add(gName);
                    Type gType = globals.get(gName);
                    sym.put(dName, (gType != null && gType.isValueType())
                            ? gType : OBJECT_TYPE);
                } else {
                    sym.put(dName, OBJECT_TYPE); // invalid global; use object as fallback
                }
            } else if (decl instanceof NonLocalDecl) {
                // "nonlocal x" makes x an alias for a variable in an enclosing function.
                String nlName = ((NonLocalDecl) decl).variable.name;
                Type nlType   = resolveNonlocal(nlName);
                currentNonlocals.add(nlName);
                sym.put(dName, (nlType != null && nlType.isValueType())
                        ? nlType : OBJECT_TYPE);
            }
        }

        // Type-check all nested declarations then all statements.
        for (Declaration decl : funcDef.declarations) decl.dispatch(this);
        for (Stmt s : funcDef.statements) s.dispatch(this);

        // Rule 9: functions with non-None/object return type must always return.
        if (!isNoneOrObject(retType) && !bodyAlwaysReturns(funcDef.statements)) {
            err(funcDef.name,
                    "All paths in this function/method must have a " +
                            "return statement: %s", funcDef.name.name);
        }

        // Restore context state.
        functionStack.remove(functionStack.size() - 1);
        sym               = prevSym;
        currentReturnType = prevRet;
        inFunction        = prevInFun;
        inClassBody       = prevInCls;
        locallyDeclared   = prevLocals;
        currentGlobals    = prevGlobs;
        currentNonlocals  = prevNl;
        return null;
    }

    /**
     * Resolves a nonlocal variable name by walking the functionStack from innermost
     * to outermost, checking params then VarDefs in each enclosing function.
     * Returns the declared type, or null if not found.
     */
    private Type resolveNonlocal(String nlName) {
        for (int i = functionStack.size() - 1; i >= 0; i--) {
            FuncDef enc = functionStack.get(i);
            for (TypedVar p : enc.params) {
                if (nlName.equals(p.identifier.name)) {
                    return ValueType.annotationToValueType(p.type);
                }
            }
            for (Declaration d : enc.declarations) {
                if (d instanceof VarDef
                        && nlName.equals(((VarDef) d).var.identifier.name)) {
                    return ValueType.annotationToValueType(((VarDef) d).var.type);
                }
            }
        }
        return null;
    }

    @Override public Type analyze(GlobalDecl d)   { return null; }
    @Override public Type analyze(NonLocalDecl d) { return null; }

    // -----------------------------------------------------------------------
    // Statements
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(ExprStmt s) { s.expr.dispatch(this); return null; }

    /**
     * Type-checks an assignment statement (possibly multi-target).
     *
     * Error attachment rules (spec Section 5.3.2):
     *  - If a target is not a valid assignable location, attach the error to that target node.
     *  - If the RHS type doesn't conform to one or more targets, attach the error for the
     *    LEFTMOST erroneous target to the AssignStmt node itself.
     */
    @Override
    public Type analyze(AssignStmt s) {
        Type rhsType = s.value.dispatch(this);

        String errorMsg  = null;
        Node   errorNode = null;

        for (Expr target : s.targets) {
            if (target instanceof Identifier) {
                Identifier id      = (Identifier) target;
                String     varName = id.name;
                Type       varType = sym.get(varName);

                if (varType == null || !varType.isValueType()) {
                    // Target identifier does not refer to a variable.
                    if (!id.hasError()) err(id, "Not a variable: %s", varName);
                    id.setInferredType(OBJECT_TYPE);
                } else {
                    if (inFunction && locallyDeclared != null
                            && !locallyDeclared.contains(varName)
                            && !currentGlobals.contains(varName)
                            && !currentNonlocals.contains(varName)) {
                        // Rule 8: assigning to an implicitly inherited binding is forbidden.
                        if (!id.hasError()) {
                            err(id,
                                    "Cannot assign to variable that is not " +
                                            "explicitly declared in this scope: %s", varName);
                        }
                        id.setInferredType((ValueType) varType);
                    } else {
                        id.setInferredType((ValueType) varType);
                        // Record leftmost type mismatch for the AssignStmt error.
                        if (rhsType instanceof ValueType && errorMsg == null) {
                            if (!hierarchy.isSubtype(
                                    (ValueType) rhsType, (ValueType) varType)) {
                                errorMsg = String.format(
                                        "Expected type `%s`; got type `%s`",
                                        varType, rhsType);
                                errorNode = s;
                            }
                        }
                    }
                }
            } else if (target instanceof MemberExpr) {
                Type memberType = target.dispatch(this);
                if (memberType instanceof ValueType
                        && rhsType instanceof ValueType && errorMsg == null) {
                    if (!hierarchy.isSubtype(
                            (ValueType) rhsType, (ValueType) memberType)) {
                        errorMsg = String.format(
                                "Expected type `%s`; got type `%s`",
                                memberType, rhsType);
                        errorNode = s;
                    }
                }
            } else if (target instanceof IndexExpr) {
                IndexExpr ie       = (IndexExpr) target;
                Type      listType = ie.list.dispatch(this);
                if (STR_TYPE.equals(listType)) {
                    // Strings are not mutable sequences; indexing them as an assignment target is invalid.
                    if (!target.hasError()) err(target, "`str` is not a list type");
                    ie.index.dispatch(this);
                    target.setInferredType(OBJECT_TYPE);
                } else {
                    Type elemType = target.dispatch(this);
                    if (elemType instanceof ValueType
                            && rhsType instanceof ValueType && errorMsg == null) {
                        if (!hierarchy.isSubtype(
                                (ValueType) rhsType, (ValueType) elemType)) {
                            errorMsg = String.format(
                                    "Expected type `%s`; got type `%s`",
                                    elemType, rhsType);
                            errorNode = s;
                        }
                    }
                }
            } else {
                if (!target.hasError()) err(target, "Cannot assign to expression");
                target.dispatch(this);
            }
        }

        // Attach the leftmost conformance error to the AssignStmt node.
        if (errorMsg != null && !s.hasError()) {
            err(errorNode, errorMsg);
        }
        return null;
    }

    @Override
    public Type analyze(ReturnStmt s) {
        if (!inFunction) {
            // Rule 10: return at the top level.
            err(s, "Return statement cannot appear at the top level");
            if (s.value != null) s.value.dispatch(this);
            return null;
        }
        if (s.value == null) {
            // Bare return: valid only when the return type is <None> or object.
            if (currentReturnType != null && !isNoneOrObject(currentReturnType)) {
                err(s, "Expected type `%s`; got `None`", currentReturnType);
            }
        } else {
            Type t = s.value.dispatch(this);
            if (t instanceof ValueType && currentReturnType != null) {
                if (!hierarchy.isSubtype((ValueType) t, currentReturnType)) {
                    err(s, "Expected type `%s`; got type `%s`", currentReturnType, t);
                }
            }
        }
        return null;
    }

    @Override
    public Type analyze(IfStmt s) {
        s.condition.dispatch(this);
        for (Stmt st : s.thenBody) st.dispatch(this);
        for (Stmt st : s.elseBody) st.dispatch(this);
        return null;
    }

    @Override
    public Type analyze(WhileStmt s) {
        s.condition.dispatch(this);
        for (Stmt st : s.body) st.dispatch(this);
        return null;
    }

    @Override
    public Type analyze(ForStmt s) {
        Type iterType = s.iterable.dispatch(this);
        ValueType elemType = null;
        if (iterType instanceof ListValueType) {
            elemType = ((ListValueType) iterType).elementType;
        } else if (STR_TYPE.equals(iterType)) {
            elemType = STR_TYPE; // iterating over a string yields single-character strings
        } else if (iterType != null && !s.iterable.hasError()) {
            err(s.iterable, "Cannot iterate over value of type `%s`", iterType);
        }

        // The loop variable must be a previously declared variable in scope.
        String varName = s.identifier.name;
        Type   varType = sym.get(varName);
        if (varType instanceof ValueType) {
            s.identifier.setInferredType((ValueType) varType);
            if (elemType != null
                    && !hierarchy.isSubtype(elemType, (ValueType) varType)) {
                err(s.identifier, "Expected type `%s`; got type `%s`", varType, elemType);
            }
        }
        for (Stmt st : s.body) st.dispatch(this);
        return null;
    }

    // -----------------------------------------------------------------------
    // Literals — each simply sets and returns the literal's known type.
    // -----------------------------------------------------------------------

    @Override public Type analyze(IntegerLiteral i) { return i.setInferredType(INT_TYPE);  }
    @Override public Type analyze(BooleanLiteral b) { return b.setInferredType(BOOL_TYPE); }
    @Override public Type analyze(StringLiteral sl)  { return sl.setInferredType(STR_TYPE); }
    @Override public Type analyze(NoneLiteral n)     { return n.setInferredType(NONE_TYPE); }

    // -----------------------------------------------------------------------
    // Identifier
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(Identifier id) {
        String name = id.name;
        Type   type = sym.get(name);
        if (type == null) {
            if (!id.hasError()) err(id, "Not a variable: %s", name);
            return id.setInferredType(OBJECT_TYPE);
        }
        // FuncType identifiers (e.g., a function name used as a value) get annotated with FuncType.
        if (type instanceof FuncType) return id.setInferredType(type);
        if (!type.isValueType()) {
            if (!id.hasError()) err(id, "Not a variable: %s", name);
            return id.setInferredType(OBJECT_TYPE);
        }
        return id.setInferredType((ValueType) type);
    }

    // -----------------------------------------------------------------------
    // Unary expressions
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(UnaryExpr e) {
        Type t = e.operand.dispatch(this);
        switch (e.operator) {
            case "-":
                // Unary minus is only defined on int; recover by inferring int.
                if (INT_TYPE.equals(t)) return e.setInferredType(INT_TYPE);
                err(e, "Cannot apply operator `%s` on type `%s`", e.operator, t);
                return e.setInferredType(INT_TYPE);
            case "not":
                // Logical not is only defined on bool; recover by inferring bool.
                if (BOOL_TYPE.equals(t)) return e.setInferredType(BOOL_TYPE);
                err(e, "Cannot apply operator `%s` on type `%s`", e.operator, t);
                return e.setInferredType(BOOL_TYPE);
            default:
                return e.setInferredType(OBJECT_TYPE);
        }
    }

    // -----------------------------------------------------------------------
    // Binary expressions
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(BinaryExpr e) {
        Type t1 = e.left.dispatch(this);
        Type t2 = e.right.dispatch(this);
        switch (e.operator) {
            case "-": case "*": case "//": case "%":
                if (INT_TYPE.equals(t1) && INT_TYPE.equals(t2))
                    return e.setInferredType(INT_TYPE);
                err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                        e.operator, t1, t2);
                return e.setInferredType(INT_TYPE); // recover: arithmetic always yields int
            case "+":
                if (INT_TYPE.equals(t1) && INT_TYPE.equals(t2))
                    return e.setInferredType(INT_TYPE);
                if (STR_TYPE.equals(t1) && STR_TYPE.equals(t2))
                    return e.setInferredType(STR_TYPE);
                if (t1 instanceof ListValueType && t2 instanceof ListValueType) {
                    ValueType elem = listJoin(((ListValueType) t1).elementType,
                            ((ListValueType) t2).elementType);
                    return e.setInferredType(new ListValueType(elem));
                }
                err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                        e.operator, t1, t2);
                // Recovery for ill-typed +: int if at least one operand is int; else object.
                if (t1.isListType() || t2.isListType()
                        || Type.EMPTY_TYPE.equals(t1) || Type.EMPTY_TYPE.equals(t2))
                    return e.setInferredType(OBJECT_TYPE);
                return e.setInferredType(INT_TYPE);
            case "<": case "<=": case ">": case ">=":
                if (INT_TYPE.equals(t1) && INT_TYPE.equals(t2))
                    return e.setInferredType(BOOL_TYPE);
                err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                        e.operator, t1, t2);
                return e.setInferredType(BOOL_TYPE); // recover: comparisons always yield bool
            case "==": case "!=":
                // Equality requires both operands to have the same non-None type.
                if (t1 != null && t1.equals(t2) && !NONE_TYPE.equals(t1))
                    return e.setInferredType(BOOL_TYPE);
                err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                        e.operator, t1, t2);
                return e.setInferredType(BOOL_TYPE);
            case "and": case "or":
                if (BOOL_TYPE.equals(t1) && BOOL_TYPE.equals(t2))
                    return e.setInferredType(BOOL_TYPE);
                err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                        e.operator, t1, t2);
                return e.setInferredType(BOOL_TYPE);
            case "is":
                // "is" requires both operands to be non-special (non-primitive) types.
                if (t1 != null && t2 != null
                        && !t1.isSpecialType() && !t2.isSpecialType())
                    return e.setInferredType(BOOL_TYPE);
                err(e, "Cannot apply operator `%s` on types `%s` and `%s`",
                        e.operator, t1, t2);
                return e.setInferredType(BOOL_TYPE);
            default:
                return e.setInferredType(OBJECT_TYPE);
        }
    }

    /**
     * Computes the joined element type for a list concatenation (+) expression.
     * Uses strict equality (not the full hierarchy join) because list concatenation
     * in ChocoPy requires both operands to have the same element type.
     */
    private ValueType listJoin(ValueType e1, ValueType e2) {
        if (e1.equals(e2)) return e1;
        if (e1 instanceof ListValueType && e2 instanceof ListValueType) {
            return new ListValueType(listJoin(
                    ((ListValueType) e1).elementType,
                    ((ListValueType) e2).elementType));
        }
        return OBJECT_TYPE;
    }

    // -----------------------------------------------------------------------
    // If-expression
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(IfExpr e) {
        e.condition.dispatch(this);
        Type thenT = e.thenExpr.dispatch(this);
        Type elseT = e.elseExpr.dispatch(this);
        // The inferred type is the join (least upper bound) of the two branch types.
        if (thenT instanceof ValueType && elseT instanceof ValueType) {
            return e.setInferredType(
                    hierarchy.join((ValueType) thenT, (ValueType) elseT));
        }
        return e.setInferredType(OBJECT_TYPE);
    }

    // -----------------------------------------------------------------------
    // List expression
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(ListExpr e) {
        if (e.elements.isEmpty()) {
            // The empty list literal has the special type <Empty>.
            return e.setInferredType(Type.EMPTY_TYPE);
        }
        // Infer the element type by joining all element types left-to-right.
        Type elemType = e.elements.get(0).dispatch(this);
        for (int i = 1; i < e.elements.size(); i++) {
            Type t = e.elements.get(i).dispatch(this);
            if (elemType instanceof ValueType && t instanceof ValueType) {
                elemType = listJoin((ValueType) elemType, (ValueType) t);
            } else {
                elemType = OBJECT_TYPE;
            }
        }
        return e.setInferredType(new ListValueType(
                (elemType instanceof ValueType) ? (ValueType) elemType : OBJECT_TYPE));
    }

    // -----------------------------------------------------------------------
    // Index expression
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(IndexExpr e) {
        Type listType  = e.list.dispatch(this);
        Type indexType = e.index.dispatch(this);
        if (!INT_TYPE.equals(indexType) && !e.hasError()) {
            err(e, "Index is of non-integer type `%s`", indexType);
        }
        if (listType instanceof ListValueType) {
            // list-select: infer the element type even when the index type was wrong.
            return e.setInferredType(((ListValueType) listType).elementType);
        }
        if (STR_TYPE.equals(listType)) {
            return e.setInferredType(STR_TYPE); // string indexing yields a single-char string
        }
        if (listType != null && !e.hasError()) {
            err(e, "Cannot index into type `%s`", listType);
        }
        return e.setInferredType(OBJECT_TYPE);
    }

    // -----------------------------------------------------------------------
    // Member expression
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(MemberExpr e) {
        Type   objType = e.object.dispatch(this);
        String mName   = e.member.name;
        if (objType instanceof ClassValueType) {
            String className = objType.className();
            Type   mType     = hierarchy.getMember(className, mName);
            if (mType == null) {
                err(e, "There is no attribute named `%s` in class `%s`", mName, className);
                return e.setInferredType(OBJECT_TYPE);
            }
            return e.setInferredType(mType);
        }
        if (objType != null && !e.hasError()) {
            err(e, "There is no attribute named `%s` in class `%s`", mName, objType);
        }
        return e.setInferredType(OBJECT_TYPE);
    }

    // -----------------------------------------------------------------------
    // Call expression (free function or constructor)
    //
    // Constructor calls (funcType is ClassValueType):
    //   - e.function is NOT annotated (stays null), per reference output.
    //   - If the class has member errors, e is also NOT annotated.
    //
    // Function calls (funcType is FuncType):
    //   - e.function IS annotated with the FuncType.
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(CallExpr e) {
        String funcName = e.function.name;
        Type   funcType = sym.get(funcName);

        // Evaluate arguments regardless of whether the callee resolves correctly.
        List<Type> argTypes = new ArrayList<>();
        for (Expr arg : e.args) argTypes.add(arg.dispatch(this));

        if (funcType == null) {
            if (!e.function.hasError())
                err(e.function, "Not a function or class: %s", funcName);
            return e.setInferredType(OBJECT_TYPE);
        }

        if (funcType instanceof ClassValueType) {
            // Constructor call: do NOT annotate e.function (reference behavior).
            String className = funcType.className();

            if (hierarchy.classHasMemberErrors(className)) {
                // Class had member errors: do NOT annotate the CallExpr either.
                Type initType = hierarchy.getMember(className, "__init__");
                if (initType instanceof FuncType) {
                    FuncType ft = (FuncType) initType;
                    checkArgTypes(e, ft.parameters.subList(1, ft.parameters.size()),
                            argTypes, 1);
                }
                return OBJECT_TYPE; // intentionally omit e.setInferredType
            }

            Type initType = hierarchy.getMember(className, "__init__");
            if (initType instanceof FuncType) {
                FuncType ft = (FuncType) initType;
                // Skip self (index 0) when checking user-supplied arguments.
                checkArgTypes(e, ft.parameters.subList(1, ft.parameters.size()),
                        argTypes, 1);
            } else if (!e.args.isEmpty()) {
                err(e, "Too many arguments for constructor of `%s`", className);
            }
            return e.setInferredType(new ClassValueType(className));
        }

        if (funcType instanceof FuncType) {
            e.function.setInferredType(funcType); // annotate the function identifier
            FuncType ft = (FuncType) funcType;
            checkArgTypes(e, ft.parameters, argTypes, 0);
            return e.setInferredType(ft.returnType);
        }

        if (!e.function.hasError())
            err(e.function, "Not a function or class: %s", funcName);
        return e.setInferredType(OBJECT_TYPE);
    }

    // -----------------------------------------------------------------------
    // Method call expression
    //
    // When method NOT found: error on the MethodCallExpr node; e.method gets NO inferredType.
    // When method IS found: e.method gets inferredType = FuncType.
    // Argument offset = 1 so that "in parameter N" counts self as parameter 0.
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(MethodCallExpr e) {
        Type   objType = e.method.object.dispatch(this);
        String mName   = e.method.member.name;

        List<Type> argTypes = new ArrayList<>();
        for (Expr arg : e.args) argTypes.add(arg.dispatch(this));

        if (!(objType instanceof ClassValueType)) {
            // Cannot call a method on a non-class type.
            err(e, "There is no method named `%s` in type `%s`", mName, objType);
            return e.setInferredType(OBJECT_TYPE);
        }

        String className = objType.className();
        Type   mType     = hierarchy.getMember(className, mName);

        if (!(mType instanceof FuncType)) {
            // Method not found: error on MethodCallExpr, e.method stays unannotated.
            err(e, "There is no method named `%s` in class `%s`", mName, className);
            return e.setInferredType(OBJECT_TYPE);
        }

        FuncType ft = (FuncType) mType;
        e.method.setInferredType(ft);
        // Skip self (index 0) when checking user-supplied arguments; offset=1 for error msg.
        checkArgTypes(e, ft.parameters.subList(1, ft.parameters.size()), argTypes, 1);
        return e.setInferredType(ft.returnType);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Validates argument count and types against the expected parameter list.
     *
     * @param callNode  the call node to attach errors to
     * @param expected  the expected parameter types (self already excluded for methods)
     * @param actual    the inferred argument types
     * @param offset    0 for function calls, 1 for method calls. Added to the loop index
     *                  so that "in parameter N" correctly counts from the full parameter list
     *                  (including self for methods).
     */
    private void checkArgTypes(Node callNode, List<ValueType> expected,
                               List<Type> actual, int offset) {
        if (expected.size() != actual.size()) {
            err(callNode, "Expected %d arguments; got %d",
                    expected.size(), actual.size());
            return;
        }
        for (int i = 0; i < expected.size(); i++) {
            Type at = actual.get(i);
            if (at instanceof ValueType) {
                if (!hierarchy.isSubtype((ValueType) at, expected.get(i))) {
                    err(callNode,
                            "Expected type `%s`; got type `%s` in parameter %d",
                            expected.get(i), at, i + offset);
                }
            }
        }
    }

    /**
     * Returns true iff the given return type is <None> or object,
     * meaning an explicit return statement is not required.
     */
    private boolean isNoneOrObject(ValueType t) {
        return NONE_TYPE.equals(t) || OBJECT_TYPE.equals(t);
    }

    /**
     * Returns true iff the given statement list always terminates with a return statement.
     *
     * Used for Rule 9 (missing return). The analysis is intentionally conservative:
     * only a trailing ReturnStmt or an IfStmt whose both branches always return counts.
     */
    private boolean bodyAlwaysReturns(List<Stmt> stmts) {
        if (stmts.isEmpty()) return false;
        Stmt last = stmts.get(stmts.size() - 1);
        if (last instanceof ReturnStmt) return true;
        if (last instanceof IfStmt) {
            IfStmt ifs = (IfStmt) last;
            if (!ifs.elseBody.isEmpty()) {
                return bodyAlwaysReturns(ifs.thenBody)
                        && bodyAlwaysReturns(ifs.elseBody);
            }
        }
        // As a fallback, scan for any return statement in the list.
        for (Stmt s : stmts) {
            if (s instanceof ReturnStmt) return true;
        }
        return false;
    }
}