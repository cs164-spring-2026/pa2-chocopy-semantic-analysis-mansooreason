package chocopy.pa2;

import java.util.HashSet;
import java.util.Set;

import chocopy.common.analysis.SymbolTable;
import chocopy.common.analysis.types.Type;
import chocopy.common.analysis.types.ValueType;

public class Env {
    public final SymbolTable<Type> symbols;
    public final Env parent;
    public final String currentClass;
    public final ValueType currentReturnType;
    public final Set<String> explicitGlobals = new HashSet<>();
    public final Set<String> explicitNonlocals = new HashSet<>();

    public Env(SymbolTable<Type> symbols, Env parent, String currentClass, ValueType currentReturnType) {
        this.symbols = symbols;
        this.parent = parent;
        this.currentClass = currentClass;
        this.currentReturnType = currentReturnType;
    }

    public boolean hasLocal(String name) {
        return symbols.declares(name);
    }

    public Type lookup(String name) {
        Type t = symbols.get(name);
        if (t != null) return t;
        return null;
    }
}