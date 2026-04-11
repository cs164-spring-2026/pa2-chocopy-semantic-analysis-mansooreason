package chocopy.pa2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import chocopy.common.analysis.AbstractNodeAnalyzer;
import chocopy.common.analysis.types.Type;
import chocopy.common.analysis.types.ValueType;
import chocopy.common.astnodes.ClassDef;
import chocopy.common.astnodes.ClassType;
import chocopy.common.astnodes.Declaration;
import chocopy.common.astnodes.FuncDef;
import chocopy.common.astnodes.Program;
import chocopy.common.astnodes.TypedVar;
import chocopy.common.astnodes.VarDef;

public class ClassAnalyzer extends AbstractNodeAnalyzer<Void> {
    private final SemanticContext ctx;
    private final TypeSystem ts;
    private final Set<String> classesSeenInOrder = new HashSet<>();

    public ClassAnalyzer(SemanticContext ctx) {
        this.ctx = ctx;
        this.ts = new TypeSystem(ctx);
        classesSeenInOrder.add("object");
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
        ClassInfo info = ctx.classes.get(c.name.name);
        if (info == null) return null;

        String superName = c.superClass.name;
        boolean validSuper =
                superName.equals("object") || classesSeenInOrder.contains(superName);

        if (!validSuper) {
            ctx.errors.semError(c.superClass,
                    "Super-class not defined: %s", superName);
        }

        Set<String> seenMembers = new HashSet<>();

        for (Declaration d : c.declarations) {
            String nm = d.getIdentifier().name;
            if (!seenMembers.add(nm)) {
                ctx.errors.semError(d.getIdentifier(),
                        "Duplicate declaration of identifier in same scope: %s", nm);
                continue;
            }

            if (d instanceof VarDef) {
                VarDef vd = (VarDef) d;

                if (hasInheritedAttr(superName, nm) || hasInheritedMethod(superName, nm)) {
                    ctx.errors.semError(vd.var.identifier,
                            "Cannot re-define attribute: %s", nm);
                }

                info.attributes.put(nm, ts.fromAnnotation(vd.var.type));
            } else if (d instanceof FuncDef) {
                FuncDef fd = (FuncDef) d;

                if (fd.params.isEmpty()) {
                    ctx.errors.semError(fd.name,
                            "First parameter of the following method must be of the enclosing class: %s",
                            fd.name.name);
                } else {
                    TypedVar first = fd.params.get(0);
                    if (!(first.type instanceof ClassType)
                            || !((ClassType) first.type).className.equals(c.name.name)) {
                        ctx.errors.semError(first.identifier,
                                "First parameter of the following method must be of the enclosing class: %s",
                                fd.name.name);
                    }
                }

                FuncSignature sig = signatureOf(fd);

                if (fd.name.name.equals("__init__") && !sig.returnType.equals(Type.NONE_TYPE)) {
                    ctx.errors.semError(fd.name,
                            "Method overridden with different type signature: %s", fd.name.name);
                }

                if (hasInheritedAttr(superName, fd.name.name)) {
                    ctx.errors.semError(fd.name,
                            "Cannot re-define attribute: %s", fd.name.name);
                }

                FuncSignature inherited = inheritedMethod(superName, fd.name.name);
                if (inherited != null && !sameSignature(sig, inherited)) {
                    ctx.errors.semError(fd.name,
                            "Method overridden with different type signature: %s", fd.name.name);
                }

                info.methods.put(fd.name.name, sig);
            }
        }

        classesSeenInOrder.add(c.name.name);
        return null;
    }

    private FuncSignature signatureOf(FuncDef fd) {
        List<ValueType> params = new ArrayList<>();
        for (TypedVar p : fd.params) {
            params.add(ts.fromAnnotation(p.type));
        }
        return new FuncSignature(params, ts.fromAnnotation(fd.returnType));
    }

    private boolean sameSignature(FuncSignature a, FuncSignature b) {
        if (a.params.size() != b.params.size()) return false;
        for (int i = 0; i < a.params.size(); i++) {
            if (!a.params.get(i).equals(b.params.get(i))) return false;
        }
        return a.returnType.equals(b.returnType);
    }

    private boolean hasInheritedAttr(String className, String attr) {
        while (className != null) {
            ClassInfo ci = ctx.classes.get(className);
            if (ci == null) return false;
            if (ci.attributes.containsKey(attr)) return true;
            className = ci.superClassName;
        }
        return false;
    }

    private boolean hasInheritedMethod(String className, String method) {
        while (className != null) {
            ClassInfo ci = ctx.classes.get(className);
            if (ci == null) return false;
            if (ci.methods.containsKey(method)) return true;
            className = ci.superClassName;
        }
        return false;
    }

    private FuncSignature inheritedMethod(String className, String method) {
        while (className != null) {
            ClassInfo ci = ctx.classes.get(className);
            if (ci == null) return null;
            if (ci.methods.containsKey(method)) return ci.methods.get(method);
            className = ci.superClassName;
        }
        return null;
    }
}