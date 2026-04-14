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
 * Stores the ChocoPy class hierarchy and implements subtype / join operations.
 *
 * Key design decisions encoded here:
 *
 * 1. bool's parent is "object" (NOT "int").
 *    In ChocoPy, bool is not a numeric type: True/False cannot be assigned to int variables.
 *
 * 2. Lists are invariant in general.
 *    [T1] is a subtype of [T2] iff T1 == T2 (element equality),
 *    OR T1 is the special type <None> (None element is compatible with any list),
 *    OR T1 is the special type <Empty> (empty-list element compatible with any list).
 *    This means [int] is NOT assignable to [object], but [<None>] IS assignable to [object].
 *
 * 3. object.__init__(self:object) -> <None> is pre-registered so that subclass __init__
 *    override detection works correctly even when no explicit __init__ is declared.
 *
 * 4. Classes with member declaration errors are tracked in classesWithMemberErrors.
 *    TypeChecker uses this flag to suppress inferredType annotation for the class body
 *    and for constructor call expressions targeting these classes.
 */
public class ClassHierarchy {

    /** Maps each class name to its direct parent class name (null for "object"). */
    private final Map<String, String> parentOf = new LinkedHashMap<>();

    /** Maps each class name to a map of member name -> member Type. */
    private final Map<String, Map<String, Type>> membersMap = new LinkedHashMap<>();

    /** All registered class names (insertion-ordered). */
    private final Set<String> classes = new LinkedHashSet<>();

    /**
     * Classes that have at least one member whose declaration produced a semantic error.
     * When a class is in this set, TypeChecker skips its body and leaves the constructor
     * CallExpr unannotated, matching the reference implementation's behavior.
     */
    private final Set<String> classesWithMemberErrors = new HashSet<>();

    /** Registers all built-in ChocoPy types and pre-populates object.__init__. */
    public ClassHierarchy() {
        register("object", null);
        register("int",    "object");
        register("str",    "object");
        register("bool",   "object");   // bool is a subtype of object, NOT int
        register("<None>",  "object");
        register("<Empty>", "object");

        // Pre-register object.__init__ so subclass override detection works correctly.
        // Without this, a subclass __init__ would appear to have no inherited signature
        // to match against, and override checking would be skipped.
        putMember("object", "__init__",
                new FuncType(List.of(Type.OBJECT_TYPE), Type.NONE_TYPE));
    }

    /** Registers a new class with a given parent (internal helper). */
    private void register(String name, String parent) {
        classes.add(name);
        parentOf.put(name, parent);
        membersMap.put(name, new LinkedHashMap<>());
    }

    /** Registers a user-defined class with the given parent class name. */
    public void addClass(String name, String parent) { register(name, parent); }

    /** Returns true iff the given class name has been registered. */
    public boolean hasClass(String name) { return classes.contains(name); }

    /** Returns the direct parent class name of the given class, or null for "object". */
    public String getParent(String className) { return parentOf.get(className); }

    /**
     * Looks up a member (attribute or method) by walking the inheritance chain.
     * Returns the first match found, or null if not found in any ancestor.
     */
    public Type getMember(String className, String memberName) {
        for (String cur = className; cur != null; cur = parentOf.get(cur)) {
            Map<String, Type> m = membersMap.get(cur);
            if (m != null && m.containsKey(memberName)) return m.get(memberName);
        }
        return null;
    }

    /** Stores a member type directly on the given class (no inheritance walk). */
    public void putMember(String className, String memberName, Type type) {
        Map<String, Type> m = membersMap.get(className);
        if (m != null) m.put(memberName, type);
    }

    /** Returns the set of members declared directly on the given class (not inherited). */
    public Set<String> directMemberNames(String className) {
        Map<String, Type> m = membersMap.get(className);
        return m == null ? Collections.emptySet()
                : Collections.unmodifiableSet(m.keySet());
    }

    /** Marks a class as having at least one member with a declaration error. */
    public void markClassHasMemberError(String className) {
        classesWithMemberErrors.add(className);
    }

    /**
     * Returns true iff this class has any member with a declaration error.
     * TypeChecker uses this to decide whether to skip the class body and
     * suppress inferredType on constructor calls.
     */
    public boolean classHasMemberErrors(String className) {
        return classesWithMemberErrors.contains(className);
    }

    /**
     * ChocoPy subtype relation: returns true iff {@code sub} is a subtype of {@code sup}.
     *
     * Rules:
     *  - Any type is a subtype of itself.
     *  - <None> and <Empty> are subtypes of any list type [T].
     *  - [T1] is a subtype of [T2] iff T1 == T2, or T1 is <None>, or T1 is <Empty>.
     *    (Lists are invariant; the only exceptions are the special element types.)
     *  - Any list type is a subtype of "object".
     *  - <None> is a subtype of any non-primitive class type (not int, bool, or str).
     *  - For user-defined class types, subtyping follows the inheritance chain.
     */
    public boolean isSubtype(ValueType sub, ValueType sup) {
        if (sub == null || sup == null) return false;
        if (sub.equals(sup)) return true;

        // Case: target is a list type.
        if (sup instanceof ListValueType) {
            // The <None> value and <Empty> value are assignable to any list variable.
            if (isEmptyType(sub) || isNoneType(sub)) return true;
            if (sub instanceof ListValueType) {
                // List invariance: [T1] <= [T2] iff T1 == T2,
                // or the element type T1 is <None> or <Empty>.
                ValueType subElem = ((ListValueType) sub).elementType;
                ValueType supElem = ((ListValueType) sup).elementType;
                if (isNoneType(subElem) || isEmptyType(subElem)) return true;
                return subElem.equals(supElem);
            }
            return false;
        }

        // Case: source is a list type — only assignable to "object".
        if (sub instanceof ListValueType) {
            return "object".equals(sup.className());
        }

        // Case: both are class value types.
        if (sub instanceof ClassValueType && sup instanceof ClassValueType) {
            String sn = sub.className();
            String tn = sup.className();
            // <None> is NOT assignable to primitive types (int, bool, str).
            if ("<None>".equals(sn)) {
                if ("int".equals(tn) || "bool".equals(tn) || "str".equals(tn))
                    return false;
                return hasClass(tn);
            }
            // Walk the inheritance chain to check subclass relationship.
            return walkChain(sn, tn);
        }
        return false;
    }

    /**
     * Returns true iff {@code sub} is equal to or an ancestor of {@code sup}
     * by walking the parentOf chain.
     */
    private boolean walkChain(String sub, String sup) {
        for (String c = sub; c != null; c = parentOf.get(c))
            if (c.equals(sup)) return true;
        return false;
    }

    /**
     * Computes the least upper bound (join) of two value types.
     *
     * Used to infer the type of if-expressions and list literals with mixed element types.
     * For lists, the join recurses on element types.
     * For class types, it finds the nearest common ancestor in the hierarchy.
     */
    public ValueType join(ValueType t1, ValueType t2) {
        if (t1 == null) return (t2 != null) ? t2 : Type.OBJECT_TYPE;
        if (t2 == null) return t1;
        if (t1.equals(t2)) return t1;

        // Both are list types: join their element types recursively.
        if (t1 instanceof ListValueType && t2 instanceof ListValueType)
            return new ListValueType(join(((ListValueType) t1).elementType,
                    ((ListValueType) t2).elementType));

        // <Empty>/<None> value joined with a list type yields the list type.
        if ((isEmptyType(t1) || isNoneType(t1)) && t2 instanceof ListValueType) return t2;
        if ((isEmptyType(t2) || isNoneType(t2)) && t1 instanceof ListValueType) return t1;

        // Both are class types: find nearest common ancestor.
        if (t1 instanceof ClassValueType && t2 instanceof ClassValueType) {
            String c1 = t1.className();
            String c2 = t2.className();
            // <None> joined with a non-primitive class yields the class type.
            if ("<None>".equals(c1) && !isPrimitive(c2)) return t2;
            if ("<None>".equals(c2) && !isPrimitive(c1)) return t1;
            // Build the ancestor set for c1 and walk c2's chain to find the first match.
            Set<String> anc1 = new LinkedHashSet<>();
            for (String c = c1; c != null; c = parentOf.get(c)) anc1.add(c);
            for (String c = c2; c != null; c = parentOf.get(c))
                if (anc1.contains(c)) return new ClassValueType(c);
        }
        return Type.OBJECT_TYPE;
    }

    // ---- Private helpers ----

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