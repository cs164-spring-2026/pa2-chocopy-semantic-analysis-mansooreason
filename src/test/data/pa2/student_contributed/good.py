# good.py - Well-typed ChocoPy program covering many typing rules.
# ALL declarations (VarDefs, FuncDefs, ClassDefs) appear before any statements.

# ---- Global variable declarations ----
count:int = 0
flag:bool = True
msg:str = "hello"
items:[int] = None
data:[object] = None
result:int = 0
a:int = 0
b:int = 0
x:int = 0
y:bool = False
z:str = "no"
obj:object = None
p:bool = True
q:bool = False
r:bool = True
c:bool = False
counter:int = 0
ch:str = ""
val:int = 10
line:str = ""
first:int = 0
s:str = ""
total:int = 0

# ---- Class definitions ----

class Animal(object):
    name:str = "unknown"
    age:int = 0

    def __init__(self:"Animal"):
        pass

    def get_name(self:"Animal") -> str:
        return self.name

    def get_age(self:"Animal") -> int:
        return self.age

    def is_old(self:"Animal") -> bool:
        if self.age > 10:
            return True
        else:
            return False

class Dog(Animal):
    breed:str = "mixed"

    def get_breed(self:"Dog") -> str:
        return self.breed

# ---- Object variable declarations (after class defs so types are known) ----
animal:Animal = None
dog:Dog = None

# ---- Function definitions ----

def increment() -> int:
    global count
    count = count + 1
    return count

def add(x:int, y:int) -> int:
    return x + y

def greet(name:str, formal:bool) -> str:
    if formal:
        return "Hello, " + name
    else:
        return "Hey " + name

def make_counter() -> int:
    def helper() -> int:
        nonlocal n
        n = n + 1
        return n
    n:int = 0
    helper()
    helper()
    return n

# In ChocoPy, the for-loop variable must be a previously declared local variable.
def sum_list(lst:[int]) -> int:
    total:int = 0
    i:int = 0
    for i in lst:
        total = total + i
    return total

# ---- Statements ----

# None assignment to list variables (None is assignable to any list type)
items = None
data = None
items = [1, 2, 3]

# [None] is assignable to [object] (None element is special)
data = [None]

# Multi-target assignment
a = b = 5

# If expression
x = 1 if flag else 0

# Object assignment (Dog is a subtype of object)
dog = Dog()
obj = dog

# Calling methods
animal = Animal()
animal.get_name()
animal.is_old()

# Calling functions
result = add(3, 4)
result = increment()

# String operations
s = greet("Alice", True)
s = "Hello" + " " + "World"

# len and print
print(len(items))
print(result)

# Boolean operations
r = p and q
r = p or q
r = not p

# Comparison
c = result > 0
c = result == 5
c = result != 3

# is operator (comparing object reference types)
c = dog is None

# While loop
counter = 0
while counter < 5:
    counter = counter + 1

# For loop over string
for ch in "abc":
    print(ch)

# For loop over list (x is already declared as int above)
for x in items:
    total = total + x

# Nested if-else
val = 10
if val > 5:
    if val > 8:
        print(val)
    else:
        print(0)
else:
    print(val)

# Input
line = input()

# List element assignment
items[0] = 42
items[1] = items[0] + 1

# Index expression
first = items[0]

# make_counter uses nonlocal
result = make_counter()

# sum_list uses for loop over list param
result = sum_list(items)

# String index
ch = "hello"[0]