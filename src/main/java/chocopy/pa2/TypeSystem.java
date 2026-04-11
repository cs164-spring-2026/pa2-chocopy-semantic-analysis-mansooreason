package chocopy.pa2;

import chocopy.common.analysis.types.ClassValueType;
import chocopy.common.analysis.types.ListValueType;
import chocopy.common.analysis.types.Type;
import chocopy.common.analysis.types.ValueType;
import chocopy.common.astnodes.ClassType;
import chocopy.common.astnodes.ListType;
import chocopy.common.astnodes.TypeAnnotation;

public class TypeSystem {
    private final SemanticContext ctx;

    public TypeSystem(SemanticContext ctx) {
        this.ctx = ctx;
    }

    public boolean isBuiltinClassName(String name) {
        return name.equals("object")
                || name.equals("int")
                || name.equals("bool")
                || name.equals("str");
    }

    public boolean isDefinedClassName(String name) {
        return isBuiltinClassName(name) || ctx.classes.containsKey(name);
    }

    public ValueType fromAnnotation(TypeAnnotation ann) {
        if (ann instanceof ClassType) {
            return new ClassValueType(((ClassType) ann).className);
        } else {
            ListType lt = (ListType) ann;
            return new ListValueType(fromAnnotation(lt.elementType));
        }
    }

    public boolean isSubtype(ValueType a, ValueType b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;

        if (a.equals(Type.NONE_TYPE)) {
            return !b.isSpecialType();
        }

        if (a.equals(Type.EMPTY_TYPE) && b.isListType()) {
            return true;
        }

        if (a instanceof ListValueType && b instanceof ListValueType) {
            return ((ListValueType) a).elementType.equals(((ListValueType) b).elementType);
        }

        if (a instanceof ClassValueType && b instanceof ClassValueType) {
            String cur = ((ClassValueType) a).className();
            String target = ((ClassValueType) b).className();

            while (cur != null) {
                if (cur.equals(target)) return true;
                if (isBuiltinClassName(cur)) {
                    if (cur.equals("object")) break;
                    cur = "object";
                    continue;
                }
                ClassInfo info = ctx.classes.get(cur);
                if (info == null) break;
                cur = info.superClassName;
            }
        }

        return false;
    }

    public ValueType join(ValueType a, ValueType b) {
        if (a == null || b == null) return Type.OBJECT_TYPE;
        if (isSubtype(a, b)) return b;
        if (isSubtype(b, a)) return a;
        return Type.OBJECT_TYPE;
    }

    public boolean isAssignable(ValueType src, ValueType dest) {
        return isSubtype(src, dest);
    }
}