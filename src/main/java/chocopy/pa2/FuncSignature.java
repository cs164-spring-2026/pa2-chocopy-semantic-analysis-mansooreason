package chocopy.pa2;

import java.util.List;
import chocopy.common.analysis.types.ValueType;

public class FuncSignature {
    public final List<ValueType> params;
    public final ValueType returnType;

    public FuncSignature(List<ValueType> params, ValueType returnType) {
        this.params = params;
        this.returnType = returnType;
    }
}