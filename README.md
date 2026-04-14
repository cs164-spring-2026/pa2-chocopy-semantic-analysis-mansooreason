# PA2 - ChocoPy Semantic Analysis

## Team Members
- Mansoor Mamnoon
- Eason Wei

## Acknowledgements
No outside help beyond the course handout, starter code, sample tests, and ChocoPy reference materials.

## Late Hours Consumed
21

---

## Writeup Questions

### 1. How many passes does your semantic analysis perform over the AST?

**Pass 1: `DeclarationAnalyzer`** (`chocopy.pa2.DeclarationAnalyzer`): This pass builds the global symbol table and populates the `ClassHierarchy` data structure. It enforces all eleven semantic rules from Section 5.2: duplicate declarations, class-name shadowing, nonlocal/global validity, superclass validity, attribute re-definition, method first-parameter constraints, override signature matching, implicit variable assignment detection, missing return path detection, top-level return detection, and invalid type annotations. Before the main processing loop, two pre-scans run over the top-level declarations: one collects all user-defined class names (so forward-declared class names are available for shadow checks), and one collects global variable name-to-type mappings (so a `global x` inside a function defined before `x:T=v` can still resolve x's type correctly). The result of this pass is a fully populated `SymbolTable<Type>` and `ClassHierarchy` that Pass 2 consumes.

**Pass 2: `TypeChecker`** (`chocopy.pa2.TypeChecker`): This pass uses the symbol table and `ClassHierarchy` from Pass 1 to annotate every expression node with its inferred `ValueType`. It handles all expression kinds (literals, identifiers, unary/binary operators, if-expressions, list expressions, index expressions, member access, call expressions, and method call expressions) and all statement kinds (assignments, return, if/while/for). For each expression, it infers the most specific type possible even when a type error occurs, following the recovery strategy in Section 5.3.2. Assignment conformance is checked with a list-invariant subtype relation. Classes whose members had declaration errors are skipped entirely during body type-checking, and their constructor calls are left unannotated, matching the reference implementation used by us to check our submission.

### 2. What was the hardest component to implement in this assignment? Why was it challenging?

The hardest component was getting **list subtyping** and **class member error recovery** to match the reference implementation exactly.

For list subtyping, ChocoPy lists are invariant in general: `[int]` is NOT a subtype of `[object]`, even though `int` is a subtype of `object`. The only exceptions are the special types `<None>` and `<Empty>`: a `<None>` value or a list whose element type is `<None>` is assignable to any list variable, and `<Empty>` (the empty list literal `[]`) is assignable to any list variable. Getting all these cases right required careful layering in `ClassHierarchy.isSubtype`: first check whether the source value itself is `<None>` or `<Empty>`, then for two list types check whether the source's element type is `<None>` or `<Empty>`, and only then require strict element-type equality. A single missed case caused tests to fail in ways that were hard to trace.

For class member error recovery, the reference implementation completely skips type-checking the body of a class whenever any of its member declarations had a semantic error (e.g., a method with the wrong first-parameter type). This meant no `inferredType` on any node inside the class body, and no `inferredType` on the constructor call expression. Discovering this behavior required careful comparison against the reference output. Implementing it required tracking the flag in `ClassHierarchy`, checking it at the start of `TypeChecker.analyze(ClassDef)`, and checking it again in `TypeChecker.analyze(CallExpr)`. A further subtlety was that the error message for both empty-parameter methods and wrong-first-parameter methods must be identical (`"First parameter of the following method must be of the enclosing class: X"`), which caused a test failure until we unified the two cases.

### 3. When type checking ill-typed expressions, why is it important to recover by inferring the most specific type? What is the problem if we simply infer the type `object` for every ill-typed expression?

It is important because downstream type checks depend on the inferred type of sub-expressions. If we always infer `object` after an error, we lose information and cause cascading false positives that hide the real root cause.

**Example 1 — return type mismatch (`bad_types.py`, function `bar`):**

```python
def bar() -> int:
    return "oops"   # Error: Expected type `int`; got type `str`
```

Even though the return is ill-typed, `bar`'s declared return type is `int`. If any code later called `bar()` and used the result in an int context, inferring `object` for the `bar()` call expression would produce a spurious second error there. By always inferring the declared return type of a function — even when an argument or return type error occurs: we confine errors to their actual sites.

**Example 2 — wrong argument type (`bad_types.py`, call `foo(1, 2)`):**

```python
def foo(p:int, q:str) -> bool:
    return True
 
foo(1, 2)   # Error: Expected type `str`; got type `int` in parameter 1
```

Even though argument 2 has the wrong type, the call expression `foo(1, 2)` still gets `inferredType = bool` (the declared return type of `foo`). If we instead inferred `object`, any subsequent code using the result as a `bool` would produce a false type error. By inferring the most specific applicable type — the declared return type — we report only the real error and let the rest of the program check correctly.

**Example 3 — arithmetic type error (`bad_types.py`, `x = y + 1`):**

```python
x = y + 1   # Error: cannot apply operator `+` on types `bool` and `int`
```

The spec says: for an ill-typed `+`, if at least one operand is `int`, infer `int`. Inferring `object` instead would make any subsequent use of `x` (which is declared as `int`) appear to get an `object` assigned to it — generating a second spurious assignment error. Inferring `int` correctly recovers the most specific type and avoids the false downstream error.

So in all three cases, recovering with the most specific type preserves information that downstream checks depend on, allowing the compiler to find all genuine errors without drowning them in cascading noise from over-general `object` recovery.
 
---