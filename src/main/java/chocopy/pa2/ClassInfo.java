package chocopy.pa2;

import java.util.LinkedHashMap;
import java.util.Map;

import chocopy.common.analysis.types.ValueType;

public class ClassInfo {
    public final String name;
    public final String superClassName;
    public final Map<String, ValueType> attributes = new LinkedHashMap<>();
    public final Map<String, FuncSignature> methods = new LinkedHashMap<>();

    public ClassInfo(String name, String superClassName) {
        this.name = name;
        this.superClassName = superClassName;
    }
}