# PA2 - ChocoPy Semantic Analysis

## Team Members
- Mansoor Mamnoon
- Eason Wei

## Acknowledgements
No outside help beyond the course handout, starter code, sample tests, and ChocoPy reference materials.

## Late Hours Consumed
10

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

It is important because later type checks depend on the inferred types of earlier expressions. If we always recover with `object`, then we lose useful information and create extra errors that are not really the main problem.

For example, suppose we have:

```python
def bar() -> int:
    return "oops"
```
The return statement is wrong, because the function says it returns int but the actual value is str. Even so, it is better to keep the function’s declared return type as int during recovery. If we changed everything to object, then later uses of bar() could cause extra fake errors that only happen because we threw away too much type information.

The same idea applies to function calls. If a function call has bad argument types, but the callee is still known, it is better to recover using the function’s declared return type. That way, later code can still be checked in a useful way. If we always used object, then one bad argument could cause a lot of unrelated follow-up errors.

In general, inferring the most specific type after an error helps reduce cascading errors and makes the remaining error messages more accurate and more useful.
    
### 2. What was the hardest component to implement in this assignment? Why was it challenging?

The hardest part was getting the class hierarchy and subtype logic to work correctly together with error recovery.

Classes affect a lot of other parts of the analysis at once: member lookup, method calls, inheritance, override checking, and joins for inferred types. Small mistakes in the hierarchy logic caused many unrelated tests to fail later in type checking. It was also tricky to recover cleanly after declaration errors, because we still needed enough class information to keep checking the rest of the program.

Another difficult part was list typing. Lists are not handled the same way as simple class types, so we had to be careful about element types, empty lists, and how list concatenation should infer a result type.

### 3. When type checking ill-typed expressions, why is it important to recover by inferring the most specific type? What is the problem if we simply infer the type object for every ill-typed expression?

It is important because later checks depend on the inferred type of earlier expressions. If we always infer `object` after an error, then we lose useful information and create extra cascading errors.

For example, in `bad types.py`, the expression `y + 1` is ill-typed because `y` is `bool`. But the `+` expression is still closer to an `int` result than to a totally unknown value. If we infer `object`, later code that uses that result may fail in ways that are not the real root problem. If we infer the most specific type we can, we report the original error while still letting the rest of the program be checked more accurately.

The same idea applies to function calls. If the argument types are wrong but the callee is still a known function, it is better to infer the declared return type of that function than to collapse the whole expression to `object`. That keeps later type checking much more precise.