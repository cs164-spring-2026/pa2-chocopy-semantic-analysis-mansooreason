# bad_semantic.py - Programs with semantic (non-type) errors.
# Covers all 11 semantic rules from the spec.

# ---- Rule 1: Duplicate declarations in same scope ----
x:int = 0
x:int = 1          # Error: Duplicate declaration of identifier in same scope: x

def foo() -> object:
    pass

def foo() -> object:  # Error: Duplicate declaration of identifier in same scope: foo
    pass

# ---- Rule 2: Cannot shadow class names ----
class MyClass(object):
    pass

def bar(int:str) -> object:    # Error: Cannot shadow class name: int
    MyClass:int = 5             # Error: Cannot shadow class name: MyClass
    pass

# ---- Rule 3: Nonlocal/global must refer to proper variables ----
aa:int = 0
bb:int = 0

def outer() -> object:
    cc:bool = True

    def inner() -> object:
        global aa              # OK: aa is a global variable
        global zzz             # Error: Not a global variable: zzz
        nonlocal cc            # OK: cc is a local variable in outer
        nonlocal bb
        pass

    pass

# ---- Rule 4: Super-class must be defined and be a class ----
qq:int = 0

class BadSuper(qq):        # Error: Super-class must be a class: qq
    pass

class Missing(NotDefined): # Error: Super-class not defined: NotDefined
    pass

# ---- Rule 5: Cannot re-define inherited attributes ----
class Base(object):
    attr:int = 1

    def method(self:"Base") -> int:
        return 0

class Child(Base):
    attr:int = 2       # Error: Cannot re-define attribute: attr
    method:str = ""    # Error: Cannot re-define attribute: method

# ---- Rule 6: Method first param must be of enclosing class ----
class BadMethod(object):
    def wrong(self:int) -> object:   # Error: First parameter of the following method...
        pass

    def also_wrong() -> object:      # Error: First parameter of the following method...
        pass

# ---- Rule 7: Override must have matching signature ----
class Parent(object):
    def compute(self:"Parent", val:int) -> int:
        return val

class Override(Parent):
    def compute(self:"Override", val:str) -> int:  # Error: Method overridden with different type signature
        return 0

# ---- Rule 8: Cannot assign to implicitly inherited variable ----
gg:int = 0

def no_global() -> object:
    gg = 5              # Error: Cannot assign to variable that is not explicitly declared in this scope: gg
    pass

# ---- Rule 9: Missing return on non-None return type ----
def missing_return() -> int:
    pass               # Error: All paths in this function/method must have a return statement: missing_return

# ---- Rule 11: Invalid type annotation ----
def typed_wrong(v:UndefinedClass) -> object:  # Error: Invalid type annotation; there is no class named: UndefinedClass
    pass

# ---- Additional: Duplicate class ----
class Dup(object):
    pass

class Dup(object):     # Error: Duplicate declaration of identifier in same scope: Dup
    pass

# ---- Additional: Cannot extend special classes ----
class ExtendInt(int):  # Error: Cannot extend special class: int
    pass

# ---- Statements ----
# Call foo() with no args (foo takes no params after duplicate error, original foo is used)
foo()
# Call bar() with a str arg since bar's first param `int` shadows the int class name
bar("hello")