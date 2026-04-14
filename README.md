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

Our semantic analysis uses two passes over the AST.

The first pass is `DeclarationAnalyzer`. This pass builds the global symbol table and builds a `ClassHierarchy` that stores inheritance, class members, and method information. It also handles the declaration-level semantic checks, such as duplicate declarations, shadowing class names, checking superclass validity, checking method signatures, checking `global` and `nonlocal` declarations, and checking type annotations. Before the main declaration pass, we do two small pre-scans of the top-level declarations. One pre-scan collects all user-defined class names, and the other collects global variable names and types. This helps us handle forward references correctly.

The second pass is `TypeChecker`. This pass uses the symbol table and class hierarchy from the first pass to infer and annotate expression types. It checks literals, identifiers, unary and binary expressions, if-expressions, list expressions, index expressions, member expressions, function calls, method calls, assignments, returns, and control-flow statements like `if`, `while`, and `for`. When an expression is wrong, it still tries to infer the most specific type possible so that later checks can continue.

### 2. What was the hardest component to implement in this assignment? Why was it challenging?

The hardest part was getting list subtyping and class error recovery to match the reference implementation.

Lists were tricky because ChocoPy does not treat them like normal class subtyping. In general, lists are invariant, so `[int]` is not a subtype of `[object]`, even though `int` is a subtype of `object`. The special cases are `<None>` and `<Empty>`. A `<None>` value can be assigned to list variables, and `<Empty>` is used for the empty list literal `[]`. Handling these cases correctly took a lot of careful logic in the subtype checks.

The other hard part was error recovery for classes with bad members. We found that if a class has a member declaration error, the reference implementation skips type-checking the rest of that class body. That means expressions inside that class do not get annotated, and constructor calls for that class also stay unannotated. We had to track which classes had member errors and then use that information during type checking.

Another small but annoying part was matching error messages exactly. For example, methods with no parameters and methods with the wrong first parameter both need to use the same first-parameter error message in order to match the reference behavior.

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