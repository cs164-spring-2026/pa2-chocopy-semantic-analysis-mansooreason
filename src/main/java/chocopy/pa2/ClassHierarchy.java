package chocopy.pa2;

import chocopy.common.analysis.types.ClassValueType;
import chocopy.common.analysis.types.FuncType;
import chocopy.common.analysis.types.ListValueType;
import chocopy.common.analysis.types.Type;
import chocopy.common.analysis.types.ValueType;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ChocoPy class hierarchy.
 *
 * Design decisions:
 *  - bool parent = "object" (NOT "int"). bool is NOT assignable to int.
 *  - List subtyping: [T1] <= [T2] iff T1 == T2, or T1 is <None>, or T1 is <Empty>.
 *    This means [<None>] is assignable to [object] (because <None> is special),
 *    but [int] is NOT assignable to [object] (because int != object).
 *  - object.__init__(self:object) -> <None> is pre-registered so that
 *    subclass __init__ overrides can be detected.
 *  - Classes with any member declaration error are tracked in classesWithMemberErrors.
 *    TypeChecker uses this to suppress annotation of the class body and constructor calls.
 */
public class ClassHierarchy {

    private final Map<String, String> parentOf = new LinkedHashMap<>();
    private final Map<String, Map<String, Type>> membersMap = new LinkedHashMap<>();
    private final Set<String> classes = new LinkedHashSet<>();

    /** Classes where at least one member identifier has a semantic error. */
    private final Set<String> classesWithMemberErrors = new HashSet<>();

    public ClassHierarchy() {
        register("object", null);
        register("int",    "object");
        register("str",    "object");
        register("bool",   "object");   // bool is NOT a subtype of int
        register("<None>",  "object");
        register("<Empty>", "object");

        // Default object.__init__ so subclass __init__ overrides can be detected.
        putMember("object", "__init__",
                new FuncType(List.of(Type.OBJECT_TYPE), Type.NONE_TYPE));
    }

    private void register(String name, String parent) {
        classes.add(name);
        parentOf.put(name, parent);
        membersMap.put(name, new LinkedHashMap<>());
    }

    public void addClass(String name, String parent) { register(name, parent); }
    public boolean hasClass(String name) { return classes.contains(name); }
    public String getParent(String className) { return parentOf.get(className); }

    public Type getMember(String className, String memberName) {
        for (String cur = className; cur != null; cur = parentOf.get(cur)) {
            Map<String, Type> m = membersMap.get(cur);
            if (m != null && m.containsKey(memberName)) return m.get(memberName);
        }
        return null;
    }

    public void putMember(String className, String memberName, Type type) {
        Map<String, Type> m = membersMap.get(className);
        if (m != null) m.put(memberName, type);
    }

    public Set<String> directMemberNames(String className) {
        Map<String, Type> m = membersMap.get(className);
        return m == null ? Collections.emptySet()
                : Collections.unmodifiableSet(m.keySet());
    }

    public void markClassHasMemberError(String className) {
        classesWithMemberErrors.add(className);
    }

    public boolean classHasMemberErrors(String className) {
        return classesWithMemberErrors.contains(className);
    }

    /**
     * ChocoPy subtype relation.
     *
     * List subtyping rule: [T1] <= [T2] iff:
     *   - T1 == T2 (element-type equality, i.e., invariant), OR
     *   - T1 is <None> (None element is assignable to any list), OR
     *   - T1 is <Empty> (empty-list element is assignable to any list).
     *
     * This means:
     *   [<None>] <= [object]: YES  (<None> element is special)
     *   [int]    <= [object]: NO   (int != object)
     *   <None>   <= [T]:      YES  (None value is assignable to any list variable)
     *   <Empty>  <= [T]:      YES  (empty list is assignable to any list variable)
     */
    public boolean isSubtype(ValueType sub, ValueType sup) {
        if (sub == null || sup == null) return false;
        if (sub.equals(sup)) return true;

        // Target is a list type.
        if (sup instanceof ListValueType) {
            // <Empty> value and <None> value are assignable to any list variable.
            if (isEmptyType(sub) || isNoneType(sub)) return true;
            if (sub instanceof ListValueType) {
                // [T1] <= [T2]: T1 must be equal to T2, or T1 must be <None> or <Empty>.
                ValueType subElem = ((ListValueType) sub).elementType;
                ValueType supElem = ((ListValueType) sup).elementType;
                if (isNoneType(subElem) || isEmptyType(subElem)) return true;
                return subElem.equals(supElem);
            }
            return false;
        }

        // Source is a list: only assignable to object.
        if (sub instanceof ListValueType) {
            return "object".equals(sup.className());
        }

        // Both are class types.
        if (sub instanceof ClassValueType && sup instanceof ClassValueType) {
            String sn = sub.className();
            String tn = sup.className();
            // <None> is not assignable to int, bool, str.
            if ("<None>".equals(sn)) {
                if ("int".equals(tn) || "bool".equals(tn) || "str".equals(tn))
                    return false;
                return hasClass(tn);
            }
            return walkChain(sn, tn);
        }
        return false;
    }

    private boolean walkChain(String sub, String sup) {
        for (String c = sub; c != null; c = parentOf.get(c))
            if (c.equals(sup)) return true;
        return false;
    }

    public ValueType join(ValueType t1, ValueType t2) {
        if (t1 == null) return (t2 != null) ? t2 : Type.OBJECT_TYPE;
        if (t2 == null) return t1;
        if (t1.equals(t2)) return t1;

        if (t1 instanceof ListValueType && t2 instanceof ListValueType)
            return new ListValueType(join(((ListValueType) t1).elementType,
                    ((ListValueType) t2).elementType));
        if ((isEmptyType(t1) || isNoneType(t1)) && t2 instanceof ListValueType)
            return t2;
        if ((isEmptyType(t2) || isNoneType(t2)) && t1 instanceof ListValueType)
            return t1;

        if (t1 instanceof ClassValueType && t2 instanceof ClassValueType) {
            String c1 = t1.className();
            String c2 = t2.className();
            if ("<None>".equals(c1) && !isPrimitive(c2)) return t2;
            if ("<None>".equals(c2) && !isPrimitive(c1)) return t1;
            Set<String> anc1 = new LinkedHashSet<>();
            for (String c = c1; c != null; c = parentOf.get(c)) anc1.add(c);
            for (String c = c2; c != null; c = parentOf.get(c))
                if (anc1.contains(c)) return new ClassValueType(c);
        }
        return Type.OBJECT_TYPE;
    }

    private boolean isEmptyType(ValueType t) {
        return t instanceof ClassValueType && "<Empty>".equals(t.className());
    }
    private boolean isNoneType(ValueType t) {
        return t instanceof ClassValueType && "<None>".equals(t.className());
    }
    private boolean isPrimitive(String name) {
        return "int".equals(name) || "bool".equals(name) || "str".equals(name);
    }
}