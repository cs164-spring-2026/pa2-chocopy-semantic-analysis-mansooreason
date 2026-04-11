package chocopy.pa2;

import java.util.LinkedHashMap;
import java.util.Map;

import chocopy.common.analysis.SymbolTable;
import chocopy.common.analysis.types.Type;
import chocopy.common.astnodes.Errors;
import chocopy.common.astnodes.FuncDef;

public class SemanticContext {
    public final Errors errors;
    public final SymbolTable<Type> globals = new SymbolTable<>();
    public final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    public final Map<String, FuncDef> globalFunctions = new LinkedHashMap<>();

    public SemanticContext(Errors errors) {
        this.errors = errors;
    }
}