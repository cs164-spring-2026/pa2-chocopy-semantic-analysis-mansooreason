package chocopy.pa2;

import chocopy.common.analysis.AbstractNodeAnalyzer;
import chocopy.common.analysis.SymbolTable;
import chocopy.common.analysis.types.ClassValueType;
import chocopy.common.analysis.types.FuncType;
import chocopy.common.analysis.types.Type;
import chocopy.common.analysis.types.ValueType;
import chocopy.common.astnodes.ClassDef;
import chocopy.common.astnodes.ClassType;
import chocopy.common.astnodes.Declaration;
import chocopy.common.astnodes.Errors;
import chocopy.common.astnodes.FuncDef;
import chocopy.common.astnodes.GlobalDecl;
import chocopy.common.astnodes.Identifier;
import chocopy.common.astnodes.ListType;
import chocopy.common.astnodes.NonLocalDecl;
import chocopy.common.astnodes.Program;
import chocopy.common.astnodes.TypeAnnotation;
import chocopy.common.astnodes.TypedVar;
import chocopy.common.astnodes.VarDef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pass 1 of semantic analysis: declaration analysis.
 *
 * This pass traverses the AST top-down and enforces all eleven semantic rules
 * from Section 5.2 of the PA2 spec:
 *   Rule 1  - no duplicate declarations in the same scope
 *   Rule 2  - variables and functions may not shadow class names
 *   Rule 3  - nonlocal/global declarations must reference valid variables
 *   Rule 4  - super-class must be a defined, non-special class
 *   Rule 5  - attributes may not override inherited attributes or methods
 *   Rule 6  - methods must have at least one parameter typed as the enclosing class
 *   Rule 7  - method overrides must match the inherited signature
 *   Rule 8  - implicit variable binding is detected (reported by TypeChecker)
 *   Rule 9  - missing return paths are detected (reported by TypeChecker)
 *   Rule 11 - type annotations must reference defined class names
 *
 * Two pre-scans are performed before the main processing loop:
 *   Pre-scan 1: collect all user-defined class names so that forward class-name
 *               references can be detected in shadow checks.
 *   Pre-scan 2: collect all global variable names and types so that a "global x"
 *               declaration inside a function defined before "x:T=v" still resolves x's type.
 *
 * The result is a populated SymbolTable<Type> (globals) and a ClassHierarchy
 * that TypeChecker uses in Pass 2.
 */
public class DeclarationAnalyzer extends AbstractNodeAnalyzer<Type> {

    /** The global symbol table, shared with TypeChecker after Pass 1. */
    private final SymbolTable<Type> globals = new SymbolTable<>();

    /** Current active symbol table (may be a function-local child of globals). */
    private SymbolTable<Type> sym = globals;

    /** Name of the class currently being processed, or null if not inside a class. */
    private String currentClass = null;

    private final Errors errors;
    private final ClassHierarchy hierarchy;

    /**
     * All class names (built-in + user-defined), used to detect class-name shadowing.
     * Populated by pre-scan 1.
     */
    private final Set<String> allClassNames = new HashSet<>();

    /**
     * Maps global variable names to their declared types.
     * Populated by pre-scan 2 so that forward "global x" references inside functions
     * can resolve x's type even when the VarDef for x appears later in the file.
     */
    private final Map<String, ValueType> globalVarTypes = new HashMap<>();

    /** Names of all top-level variable declarations (used to validate "global x"). */
    final Set<String> globalVarNames = new HashSet<>();

    /** Initializes the global scope with ChocoPy's built-in names. */
    public DeclarationAnalyzer(Errors errors, ClassHierarchy hierarchy) {
        this.errors    = errors;
        this.hierarchy = hierarchy;

        // Built-in class types — these appear in the symbol table as ValueTypes
        // so that type annotations can be looked up by name.
        globals.put("int",    Type.INT_TYPE);
        globals.put("str",    Type.STR_TYPE);
        globals.put("bool",   Type.BOOL_TYPE);
        globals.put("object", Type.OBJECT_TYPE);

        // Built-in functions
        globals.put("print",  new FuncType(List.of(Type.OBJECT_TYPE), Type.NONE_TYPE));
        globals.put("input",  new FuncType(List.of(), Type.STR_TYPE));
        globals.put("len",    new FuncType(List.of(Type.OBJECT_TYPE), Type.INT_TYPE));

        // Seed allClassNames with built-in class names so shadow checks work immediately.
        allClassNames.add("int");
        allClassNames.add("str");
        allClassNames.add("bool");
        allClassNames.add("object");
    }

    /** Returns the populated global symbol table (called by StudentAnalysis after Pass 1). */
    public SymbolTable<Type> getGlobals() { return globals; }

    /**
     * Builds a FuncType from a FuncDef's declared parameter and return types.
     * Used by TypeChecker to populate function types in nested scopes.
     */
    public static FuncType funcTypeFromFuncDef(FuncDef funcDef) {
        List<ValueType> params = new ArrayList<>();
        for (TypedVar p : funcDef.params) {
            params.add(ValueType.annotationToValueType(p.type));
        }
        return new FuncType(params, ValueType.annotationToValueType(funcDef.returnType));
    }

    // -----------------------------------------------------------------------
    // Top-level program
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(Program program) {
        // Pre-scan 1: collect all user-defined class names before processing any declarations.
        // This lets us detect shadowing of a forward-declared class name (e.g., a function
        // parameter named "Dog" when class Dog is declared later in the file).
        for (Declaration d : program.declarations) {
            if (d instanceof ClassDef) {
                allClassNames.add(((ClassDef) d).name.name);
            }
        }

        // Pre-scan 2: collect global variable name -> type mappings before processing.
        // This allows a function that uses "global x" to resolve x's type even when
        // the VarDef for x appears after the function definition in the source.
        for (Declaration d : program.declarations) {
            if (d instanceof VarDef) {
                VarDef vd = (VarDef) d;
                String name = vd.var.identifier.name;
                globalVarNames.add(name);
                globalVarTypes.put(name, ValueType.annotationToValueType(vd.var.type));
            }
        }

        // Main processing loop: visit each top-level declaration.
        for (Declaration decl : program.declarations) {
            Identifier id   = decl.getIdentifier();
            String     name = id.name;
            Type type = decl.dispatch(this);
            if (type == null) continue;

            // Rule 2: top-level variable/function definitions may not shadow built-in class names.
            if (isBuiltinClass(name) && !(decl instanceof ClassDef)) {
                errors.semError(id, "Cannot shadow class name: %s", name);
                continue;
            }

            // Rule 1: no duplicate declarations in the global scope.
            if (globals.declares(name)) {
                errors.semError(id,
                        "Duplicate declaration of identifier in same scope: %s", name);
            } else {
                globals.put(name, type);
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Variable definitions
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(VarDef varDef) {
        // Rule 11: validate that the declared type annotation names a known class.
        validateAnnotation(varDef.var.type);
        return ValueType.annotationToValueType(varDef.var.type);
    }

    // GlobalDecl and NonLocalDecl return marker sentinels so analyze(FuncDef)
    // can distinguish them from real variable/function types.
    @Override public Type analyze(GlobalDecl decl)   { return new GlobalMarker(decl.variable.name); }
    @Override public Type analyze(NonLocalDecl decl) { return new NonlocalMarker(decl.variable.name); }

    // -----------------------------------------------------------------------
    // Class definitions
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(ClassDef classDef) {
        String className = classDef.name.name;
        String superName = classDef.superClass.name;

        // Rule 4: super-class must be a defined class and not a special built-in.
        if (!hierarchy.hasClass(superName)) {
            if (globalVarNames.contains(superName) || globals.declares(superName)) {
                // The super-class name exists but refers to a variable, not a class.
                errors.semError(classDef.superClass,
                        "Super-class must be a class: %s", superName);
            } else {
                errors.semError(classDef.superClass,
                        "Super-class not defined: %s", superName);
            }
            superName = "object"; // recover by treating super as object
        } else if ("int".equals(superName) || "str".equals(superName)
                || "bool".equals(superName)) {
            errors.semError(classDef.superClass,
                    "Cannot extend special class: %s", superName);
            superName = "object"; // recover by treating super as object
        }

        hierarchy.addClass(className, superName);

        // Enter a fresh class-level scope for member processing.
        String            prevClass = currentClass;
        SymbolTable<Type> prevSym   = sym;
        currentClass = className;
        sym = new SymbolTable<>(globals); // class members are checked against the global scope

        for (Declaration decl : classDef.declarations) {
            Identifier memberId   = decl.getIdentifier();
            String     memberName = memberId.name;

            Type memberType = decl.dispatch(this);
            if (memberType == null) continue;

            // If this member's identifier was marked with an error (e.g. wrong self type),
            // flag the class so TypeChecker can skip its body and constructor annotation.
            if (memberId.hasError()) {
                hierarchy.markClassHasMemberError(className);
            }

            // Rule 1: no duplicate member names within the same class body.
            if (sym.declares(memberName)) {
                errors.semError(memberId,
                        "Duplicate declaration of identifier in same scope: %s", memberName);
                continue;
            }

            // Rules 5 & 7: check against inherited members.
            Type inherited = hierarchy.getMember(superName, memberName);
            if (inherited != null) {
                if (decl instanceof VarDef) {
                    // Rule 5: attributes may not override inherited attributes or methods.
                    errors.semError(memberId, "Cannot re-define attribute: %s", memberName);
                    hierarchy.markClassHasMemberError(className);
                } else if (decl instanceof FuncDef) {
                    if (inherited instanceof FuncType) {
                        // Rule 7: method override must match the inherited signature.
                        if (!signaturesMatch((FuncType) inherited, (FuncType) memberType)) {
                            errors.semError(memberId,
                                    "Method overridden with different type signature: %s",
                                    memberName);
                            hierarchy.markClassHasMemberError(className);
                        }
                    } else {
                        // Rule 5: method may not override an inherited attribute.
                        errors.semError(memberId, "Cannot re-define attribute: %s", memberName);
                        hierarchy.markClassHasMemberError(className);
                    }
                }
            }

            sym.put(memberName, memberType);
            hierarchy.putMember(className, memberName, memberType);
        }

        sym          = prevSym;
        currentClass = prevClass;
        return new ClassValueType(className);
    }

    // -----------------------------------------------------------------------
    // Function definitions
    // -----------------------------------------------------------------------

    @Override
    public Type analyze(FuncDef funcDef) {
        String funcName = funcDef.name.name;

        // Build the FuncType from declared parameter and return type annotations.
        List<ValueType> paramTypes = new ArrayList<>();
        for (TypedVar param : funcDef.params) {
            validateAnnotation(param.type); // Rule 11
            paramTypes.add(ValueType.annotationToValueType(param.type));
        }
        validateAnnotation(funcDef.returnType); // Rule 11
        ValueType retType  = ValueType.annotationToValueType(funcDef.returnType);
        FuncType  funcType = new FuncType(paramTypes, retType);

        // Rule 6: methods must have a first parameter typed as the enclosing class.
        // Both the "no params" case and the "wrong first-param type" case produce the
        // same error message, matching the reference implementation exactly.
        if (currentClass != null) {
            boolean firstParamOk = false;
            if (!funcDef.params.isEmpty()) {
                TypedVar first = funcDef.params.get(0);
                firstParamOk = (first.type instanceof ClassType)
                        && currentClass.equals(((ClassType) first.type).className);
            }
            if (!firstParamOk) {
                errors.semError(funcDef.name,
                        "First parameter of the following method must " +
                                "be of the enclosing class: %s", funcName);
            }
        }

        // Enter a new scope for the function body.
        String            prevClass = currentClass;
        SymbolTable<Type> prevSym   = sym;
        currentClass = null; // nested functions are not class methods
        sym = new SymbolTable<>(prevSym);

        // Process parameters: check for class-name shadowing and duplicates.
        Set<String> paramNames = new HashSet<>();
        for (TypedVar param : funcDef.params) {
            String pName = param.identifier.name;
            if (allClassNames.contains(pName)) {
                // Rule 2: param name shadows a class name.
                errors.semError(param.identifier, "Cannot shadow class name: %s", pName);
                sym.put(pName, ValueType.annotationToValueType(param.type));
                paramNames.add(pName);
            } else if (paramNames.contains(pName)) {
                // Rule 1: duplicate parameter name.
                errors.semError(param.identifier,
                        "Duplicate declaration of identifier in same scope: %s", pName);
            } else {
                sym.put(pName, ValueType.annotationToValueType(param.type));
                paramNames.add(pName);
            }
        }

        // Pre-scan local VarDef names so forward "nonlocal x" references in nested
        // functions can resolve x's type even when x's VarDef appears after the nested function.
        Set<String> localVarNames = new HashSet<>();
        for (Declaration decl : funcDef.declarations) {
            if (decl instanceof VarDef) {
                String dName = decl.getIdentifier().name;
                localVarNames.add(dName);
                if (!sym.declares(dName)) {
                    sym.put(dName, ValueType.annotationToValueType(
                            ((VarDef) decl).var.type));
                }
            }
        }

        // Track all declarations seen in this scope (params + local decls) for duplicate detection.
        Set<String> seenDecls = new HashSet<>(paramNames);

        for (Declaration decl : funcDef.declarations) {
            Identifier declId   = decl.getIdentifier();
            String     declName = declId.name;

            if (decl instanceof VarDef) {
                validateAnnotation(((VarDef) decl).var.type); // Rule 11
                // Rules 1 & 2: check local variable declarations.
                if (seenDecls.contains(declName)) {
                    errors.semError(declId,
                            "Duplicate declaration of identifier in same scope: %s", declName);
                } else if (allClassNames.contains(declName)) {
                    errors.semError(declId, "Cannot shadow class name: %s", declName);
                } else {
                    seenDecls.add(declName);
                }
                continue;
            }

            Type declType = decl.dispatch(this);
            if (declType == null) continue;

            if (declType instanceof GlobalMarker) {
                String gName = ((GlobalMarker) declType).varName;

                // Rule 1: "global x" when x is already declared in this scope.
                if (seenDecls.contains(gName) || localVarNames.contains(gName)) {
                    errors.semError(declId,
                            "Duplicate declaration of identifier in same scope: %s", gName);
                    continue;
                }

                // Rule 3: "global x" when x is not a global variable.
                if (!globalVarNames.contains(gName)) {
                    errors.semError(declId, "Not a global variable: %s", gName);
                    // Do NOT add to sym on error — avoids giving a wrong type to TypeChecker.
                } else {
                    ValueType gType = globalVarTypes.get(gName);
                    sym.put(declName, gType != null ? gType : Type.OBJECT_TYPE);
                    seenDecls.add(gName);
                }
                continue;
            }

            if (declType instanceof NonlocalMarker) {
                String nlName = ((NonlocalMarker) declType).varName;

                // Rule 1: "nonlocal x" when x is already declared in this scope.
                if (seenDecls.contains(nlName) || localVarNames.contains(nlName)
                        || paramNames.contains(nlName)) {
                    errors.semError(declId,
                            "Duplicate declaration of identifier in same scope: %s", nlName);
                    continue;
                }

                // Rule 3: "nonlocal x" requires x to be a local variable in an enclosing
                // function scope — not a global. We look up prevSym (the enclosing scope's
                // symbol table), then check whether the found type belongs to the global scope.
                // If the name resolves to a global, it's not a valid nonlocal target.
                Type nlType  = prevSym.get(nlName);
                boolean isGlobal = globals.declares(nlName)
                        && nlType != null && nlType.equals(globals.get(nlName));
                if (nlType == null || !nlType.isValueType() || isGlobal) {
                    errors.semError(declId, "Not a nonlocal variable: %s", nlName);
                    // Do NOT add to sym on error — avoids polluting the scope with a wrong type.
                } else {
                    sym.put(declName, nlType);
                    seenDecls.add(nlName);
                }
                continue;
            }

            // Nested FuncDef: check for class-name shadowing and duplicates.
            if (allClassNames.contains(declName)) {
                errors.semError(declId, "Cannot shadow class name: %s", declName);
                continue;
            }
            if (seenDecls.contains(declName) || sym.declares(declName)) {
                errors.semError(declId,
                        "Duplicate declaration of identifier in same scope: %s", declName);
            } else {
                sym.put(declName, declType);
                seenDecls.add(declName);
            }
        }

        sym          = prevSym;
        currentClass = prevClass;
        return funcType;
    }

    // -----------------------------------------------------------------------
    // Type annotation validation (Rule 11)
    // -----------------------------------------------------------------------

    /**
     * Reports an error if the annotation references an undefined class name.
     * Recurses into ListType elements. <None> is always valid as a type annotation.
     */
    void validateAnnotation(TypeAnnotation ann) {
        if (ann instanceof ClassType) {
            String name = ((ClassType) ann).className;
            if ("<None>".equals(name)) return; // <None> is always a valid annotation
            if (!hierarchy.hasClass(name) && !globals.declares(name)) {
                errors.semError(ann,
                        "Invalid type annotation; there is no class named: %s", name);
            }
        } else if (ann instanceof ListType) {
            validateAnnotation(((ListType) ann).elementType);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Checks whether an overriding method's signature is compatible with the inherited one.
     * Signatures match iff return types are equal and all parameters after the first (self)
     * have equal types. The first parameter (self) type is intentionally excluded.
     */
    private boolean signaturesMatch(FuncType inherited, FuncType overriding) {
        if (!inherited.returnType.equals(overriding.returnType)) return false;
        List<ValueType> ip = inherited.parameters;
        List<ValueType> op = overriding.parameters;
        if (ip.size() != op.size()) return false;
        for (int i = 1; i < ip.size(); i++) {
            if (!ip.get(i).equals(op.get(i))) return false;
        }
        return true;
    }

    /** Returns true iff the name is a built-in ChocoPy class that cannot be shadowed. */
    private boolean isBuiltinClass(String name) {
        return "int".equals(name) || "str".equals(name)
                || "bool".equals(name) || "object".equals(name);
    }

    // -----------------------------------------------------------------------
    // Sentinel types for global/nonlocal declarations
    // -----------------------------------------------------------------------

    /**
     * Sentinel returned by analyze(GlobalDecl) so the enclosing analyze(FuncDef)
     * can distinguish a "global x" declaration from a real value type.
     */
    static final class GlobalMarker extends Type {
        final String varName;
        GlobalMarker(String v) { varName = v; }
        @Override public boolean isValueType() { return false; }
        @Override public String  toString()    { return "global:" + varName; }
    }

    /**
     * Sentinel returned by analyze(NonLocalDecl) so the enclosing analyze(FuncDef)
     * can distinguish a "nonlocal x" declaration from a real value type.
     */
    static final class NonlocalMarker extends Type {
        final String varName;
        NonlocalMarker(String v) { varName = v; }
        @Override public boolean isValueType() { return false; }
        @Override public String  toString()    { return "nonlocal:" + varName; }
    }
}