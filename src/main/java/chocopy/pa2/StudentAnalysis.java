package chocopy.pa2;

import chocopy.common.analysis.SymbolTable;
import chocopy.common.analysis.types.Type;
import chocopy.common.astnodes.Program;

public class StudentAnalysis {
    public static Program process(Program program, boolean debug) {
        ClassHierarchy hierarchy = new ClassHierarchy();
        DeclarationAnalyzer declAnalyzer =
                new DeclarationAnalyzer(program.errors, hierarchy);
        program.dispatch(declAnalyzer);
        SymbolTable<Type> globalSym = declAnalyzer.getGlobals();
        TypeChecker typeChecker =
                new TypeChecker(globalSym, program.errors, hierarchy);
        program.dispatch(typeChecker);
        return program;
    }
}