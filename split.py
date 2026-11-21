import pathlib, re

# তোমার ফাইলটা খুঁজো
target = None
for f in pathlib.Path(".").rglob("*.kt"):
    if "MainActivity" in f.name:
        target = f
        break

print(f"ফাইল পাওয়া গেছে: {target}")
code = target.read_text(encoding="utf-8", errors="ignore")
print(f"মোট সাইজ: {len(code)} অক্ষর, লাইন: {code.count(chr(10))}")

out = pathlib.Path("split_output")
out.mkdir(exist_ok=True)

# ১. package আর import আলাদা করে রাখো
header = ""
lines = code.splitlines()
for line in lines:
    if line.startswith("package ") or line.startswith("import "):
        header += line + "\n"
    if line.startswith("import ") == False and "package" not in line and line.strip() != "":
        break
# header এর পরে বাকি কোড
# ২. বড় বড় fun / class / object দিয়ে ভাঙো
# প্যাটার্ন: @Composable fun X, fun X, class X, object X, data class X
pattern = r"(?m)^(?=@Composable|@OptIn|fun |private fun |suspend fun |class |data class |object |sealed |enum class |@Entity)"
parts = re.split(pattern, code)

count = 0
for part in parts:
    part = part.strip()
    if len(part) < 200:
        continue
    # নাম বের করো
    m = re.search(r"(fun\s+([A-Za-z0-9_]+)|class\s+([A-Za-z0-9_]+)|object\s+([A-Za-z0-9_]+))", part)
    name = "Part"
    if m:
        name = m.group(2) or m.group(3) or m.group(4) or "Part"
    fname = f"{name}.kt"
    # একই নাম ২ বার আসলে 2 লাগাও
    if (out / fname).exists():
        fname = f"{name}_2.kt"
    (out / fname).write_text(header + "\n" + part, encoding="utf-8")
    print(f"বানানো: {fname} - {len(part)} অক্ষর")
    count += 1

print(f"মোট {count} টা ফাইল বানানো শেষ!")
