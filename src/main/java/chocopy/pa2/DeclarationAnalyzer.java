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

public class DeclarationAnalyzer extends AbstractNodeAnalyzer<Type> {

    private final SymbolTable<Type> globals = new SymbolTable<>();
    private SymbolTable<Type> sym = globals;
    private String currentClass = null;
    private final Errors errors;
    private final ClassHierarchy hierarchy;

    private final Set<String> allClassNames   = new HashSet<>();
    private final Map<String, ValueType> globalVarTypes = new HashMap<>();
    final Set<String> globalVarNames = new HashSet<>();

    public DeclarationAnalyzer(Errors errors, ClassHierarchy hierarchy) {
        this.errors    = errors;
        this.hierarchy = hierarchy;

        globals.put("int",    Type.INT_TYPE);
        globals.put("str",    Type.STR_TYPE);
        globals.put("bool",   Type.BOOL_TYPE);
        globals.put("object", Type.OBJECT_TYPE);
        globals.put("print",  new FuncType(List.of(Type.OBJECT_TYPE), Type.NONE_TYPE));
        globals.put("input",  new FuncType(List.of(), Type.STR_TYPE));
        globals.put("len",    new FuncType(List.of(Type.OBJECT_TYPE), Type.INT_TYPE));

        allClassNames.add("int");
        allClassNames.add("str");
        allClassNames.add("bool");
        allClassNames.add("object");
    }

    public SymbolTable<Type> getGlobals() { return globals; }

    public static FuncType funcTypeFromFuncDef(FuncDef funcDef) {
        List<ValueType> params = new ArrayList<>();
        for (TypedVar p : funcDef.params) {
            params.add(ValueType.annotationToValueType(p.type));
        }
        return new FuncType(params, ValueType.annotationToValueType(funcDef.returnType));
    }

    @Override
    public Type analyze(Program program) {
        // Pre-scan 1: all user-defined class names.
        for (Declaration d : program.declarations) {
            if (d instanceof ClassDef) {
                allClassNames.add(((ClassDef) d).name.name);
            }
        }
        // Pre-scan 2: global VarDef name -> type (for forward global references).
        for (Declaration d : program.declarations) {
            if (d instanceof VarDef) {
                VarDef vd = (VarDef) d;
                String name = vd.var.identifier.name;
                globalVarNames.add(name);
                globalVarTypes.put(name, ValueType.annotationToValueType(vd.var.type));
            }
        }

        for (Declaration decl : program.declarations) {
            Identifier id   = decl.getIdentifier();
            String     name = id.name;
            Type type = decl.dispatch(this);
            if (type == null) continue;
            if (isBuiltinClass(name) && !(decl instanceof ClassDef)) {
                errors.semError(id, "Cannot shadow class name: %s", name);
                continue;
            }
            if (globals.declares(name)) {
                errors.semError(id,
                        "Duplicate declaration of identifier in same scope: %s", name);
            } else {
                globals.put(name, type);
            }
        }
        return null;
    }

    @Override
    public Type analyze(VarDef varDef) {
        validateAnnotation(varDef.var.type);
        return ValueType.annotationToValueType(varDef.var.type);
    }

    @Override public Type analyze(GlobalDecl decl) { return new GlobalMarker(decl.variable.name); }
    @Override public Type analyze(NonLocalDecl decl) { return new NonlocalMarker(decl.variable.name); }

    @Override
    public Type analyze(ClassDef classDef) {
        String className = classDef.name.name;
        String superName = classDef.superClass.name;

        if (!hierarchy.hasClass(superName)) {
            if (globalVarNames.contains(superName) || globals.declares(superName)) {
                errors.semError(classDef.superClass,
                        "Super-class must be a class: %s", superName);
            } else {
                errors.semError(classDef.superClass,
                        "Super-class not defined: %s", superName);
            }
            superName = "object";
        } else if ("int".equals(superName) || "str".equals(superName)
                || "bool".equals(superName)) {
            errors.semError(classDef.superClass,
                    "Cannot extend special class: %s", superName);
            superName = "object";
        }

        hierarchy.addClass(className, superName);

        String            prevClass = currentClass;
        SymbolTable<Type> prevSym   = sym;
        currentClass = className;
        sym = new SymbolTable<>(globals);

        for (Declaration decl : classDef.declarations) {
            Identifier memberId   = decl.getIdentifier();
            String     memberName = memberId.name;

            Type memberType = decl.dispatch(this);
            if (memberType == null) continue;

            // If the member's identifier has a semantic error, mark this class
            // so TypeChecker skips the body and constructor call annotation.
            if (memberId.hasError()) {
                hierarchy.markClassHasMemberError(className);
            }

            if (sym.declares(memberName)) {
                errors.semError(memberId,
                        "Duplicate declaration of identifier in same scope: %s", memberName);
                continue;
            }

            Type inherited = hierarchy.getMember(superName, memberName);
            if (inherited != null) {
                if (decl instanceof VarDef) {
                    errors.semError(memberId, "Cannot re-define attribute: %s", memberName);
                    hierarchy.markClassHasMemberError(className);
                } else if (decl instanceof FuncDef) {
                    if (inherited instanceof FuncType) {
                        if (!signaturesMatch((FuncType) inherited, (FuncType) memberType)) {
                            errors.semError(memberId,
                                    "Method overridden with different type signature: %s",
                                    memberName);
                            hierarchy.markClassHasMemberError(className);
                        }
                    } else {
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

    @Override
    public Type analyze(FuncDef funcDef) {
        String funcName = funcDef.name.name;

        List<ValueType> paramTypes = new ArrayList<>();
        for (TypedVar param : funcDef.params) {
            validateAnnotation(param.type);
            paramTypes.add(ValueType.annotationToValueType(param.type));
        }
        validateAnnotation(funcDef.returnType);
        ValueType retType  = ValueType.annotationToValueType(funcDef.returnType);
        FuncType  funcType = new FuncType(paramTypes, retType);

        // Rule 6: methods must have at least one param, and the first param must be
        // typed as the enclosing class. BOTH cases produce the SAME error message,
        // which is what the reference implementation does.
        if (currentClass != null) {
            boolean firstParamOk = false;
            if (!funcDef.params.isEmpty()) {
                TypedVar first = funcDef.params.get(0);
                firstParamOk = (first.type instanceof ClassType)
                        && currentClass.equals(((ClassType) first.type).className);
            }
            // Whether params are empty OR first param has wrong type, same message.
            if (!firstParamOk) {
                errors.semError(funcDef.name,
                        "First parameter of the following method must " +
                                "be of the enclosing class: %s", funcName);
            }
        }

        String            prevClass = currentClass;
        SymbolTable<Type> prevSym   = sym;
        currentClass = null;
        sym = new SymbolTable<>(prevSym);

        Set<String> paramNames = new HashSet<>();
        for (TypedVar param : funcDef.params) {
            String pName = param.identifier.name;
            if (allClassNames.contains(pName)) {
                errors.semError(param.identifier, "Cannot shadow class name: %s", pName);
                sym.put(pName, ValueType.annotationToValueType(param.type));
                paramNames.add(pName);
            } else if (paramNames.contains(pName)) {
                errors.semError(param.identifier,
                        "Duplicate declaration of identifier in same scope: %s", pName);
            } else {
                sym.put(pName, ValueType.annotationToValueType(param.type));
                paramNames.add(pName);
            }
        }

        // Pre-scan local VarDef names+types for forward nonlocal references.
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

        Set<String> seenDecls = new HashSet<>(paramNames);

        for (Declaration decl : funcDef.declarations) {
            Identifier declId   = decl.getIdentifier();
            String     declName = declId.name;

            if (decl instanceof VarDef) {
                validateAnnotation(((VarDef) decl).var.type);
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

                if (seenDecls.contains(gName) || localVarNames.contains(gName)) {
                    errors.semError(declId,
                            "Duplicate declaration of identifier in same scope: %s", gName);
                    continue;
                }

                if (!globalVarNames.contains(gName)) {
                    errors.semError(declId, "Not a global variable: %s", gName);
                } else {
                    ValueType gType = globalVarTypes.get(gName);
                    sym.put(declName, gType != null ? gType : Type.OBJECT_TYPE);
                    seenDecls.add(gName);
                }
                continue;
            }

            if (declType instanceof NonlocalMarker) {
                String nlName = ((NonlocalMarker) declType).varName;

                if (seenDecls.contains(nlName) || localVarNames.contains(nlName)
                        || paramNames.contains(nlName)) {
                    errors.semError(declId,
                            "Duplicate declaration of identifier in same scope: %s", nlName);
                    continue;
                }

                Type nlType  = prevSym.get(nlName);
                boolean isGlobal = globals.declares(nlName)
                        && nlType != null && nlType.equals(globals.get(nlName));
                if (nlType == null || !nlType.isValueType() || isGlobal) {
                    errors.semError(declId, "Not a nonlocal variable: %s", nlName);
                    // Do NOT put anything in sym on error — avoids polluting the scope.
                } else {
                    sym.put(declName, nlType);
                    seenDecls.add(nlName);
                }
                continue;
            }

            // Nested FuncDef.
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

    void validateAnnotation(TypeAnnotation ann) {
        if (ann instanceof ClassType) {
            String name = ((ClassType) ann).className;
            if ("<None>".equals(name)) return;
            if (!hierarchy.hasClass(name) && !globals.declares(name)) {
                errors.semError(ann,
                        "Invalid type annotation; there is no class named: %s", name);
            }
        } else if (ann instanceof ListType) {
            validateAnnotation(((ListType) ann).elementType);
        }
    }

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

    private boolean isBuiltinClass(String name) {
        return "int".equals(name) || "str".equals(name)
                || "bool".equals(name) || "object".equals(name);
    }

    static final class GlobalMarker extends Type {
        final String varName;
        GlobalMarker(String v) { varName = v; }
        @Override public boolean isValueType() { return false; }
        @Override public String  toString()    { return "global:" + varName; }
    }

    static final class NonlocalMarker extends Type {
        final String varName;
        NonlocalMarker(String v) { varName = v; }
        @Override public boolean isValueType() { return false; }
        @Override public String  toString()    { return "nonlocal:" + varName; }
    }
}