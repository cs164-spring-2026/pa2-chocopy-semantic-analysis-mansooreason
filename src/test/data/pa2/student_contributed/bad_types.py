# bad_types.py - Programs with type checking errors.
# All declarations are at the top; statements follow.

# ---- Global variable declarations ----
x:int = 1
y:bool = True
z:str = "hello"
lst:[int] = None
lst2:[object] = None
obj:object = None
a:int = 0

# ---- Class definitions ----

class A(object):
    val:int = 0

    def get(self:"A") -> int:
        return self.val

# ---- Function definitions (declared before statements) ----

def foo(p:int, q:str) -> bool:
    return True

def bar() -> int:
    return "oops"  # Error: Expected type `int`; got type `str`

def baz() -> str:
    return         # Error: Expected type `str`; got `None` (bare return)

def qux() -> bool:
    return 1       # Error: Expected type `bool`; got type `int`

# ---- Declarations for objects ----
myA:A = None

# ---- Statements (type errors below) ----

# int and bool are not interchangeable
x = True           # Error: Expected type `int`; got type `bool`
y = 1              # Error: Expected type `bool`; got type `int`

# Arithmetic only works on ints: bool + int not valid
x = y + 1          # Error: cannot apply operator `+` on types `bool` and `int`

# Comparison operators require both ints: int > bool not valid
z = x > y          # Error: cannot apply operator `>` on types `int` and `bool`

# String concat requires both strings: str + int not valid
x = z + 1          # Error: cannot apply operator `+` on types `str` and `int`

# `not` requires bool
x = not x          # Error: cannot apply operator `not` on type `int`

# Unary minus requires int
y = -y             # Error: cannot apply operator `-` on type `bool`

# List invariance: [int] is NOT a subtype of [object]
lst2 = lst         # Error: Expected type `[object]`; got type `[int]`

# Cannot assign None to int
x = None           # Error: Expected type `int`; got type `<None>`

# Cannot assign int to list type
lst = 5            # Error: Expected type `[int]`; got type `int`

# Function call with wrong arg types
foo(1, 2)          # Error: Expected type `str`; got type `int` in parameter 1
foo("a", "b")      # Error: Expected type `int`; got type `str` in parameter 0

# Wrong number of arguments to method
myA = A()
myA.get(1)         # Error: Expected 0 arguments; got 1

# Multi-target assignment: error attaches to AssignStmt for leftmost mismatch
x = y = 1          # Error: Expected type `bool`; got type `int` (on AssignStmt)

# == operator requires matching types
z = (x == y)       # Error: cannot apply operator `==` on types `int` and `bool`