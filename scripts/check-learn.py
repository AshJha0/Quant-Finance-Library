#!/usr/bin/env python3
"""Check the LEARN guide (docs/LEARN.md and the chapter files docs/learn/*.md)
against the compiled classes of both modules.

Three checks, modelled on scripts/check-cookbook.py:

1. Java snippets. Every ```java block is compiled the way check-cookbook.py
   compiles a recipe: the blocks of one ### section become one class, with
   wildcard imports of every library package, and a "cannot find symbol"
   located in the generated class itself (a placeholder variable) is
   ignored. A block that is deliberately not compilable (an illustrative
   fragment, pseudo-code) must say so: fence it as ```java illustrative.
   Formula blocks in plain ``` fences are not Java and are not compiled.
2. Class and method names. Every `ClassName` in backticks that looks like a
   library type must exist in core/target/classes or lowlatency/target/classes
   (or be a test or benchmark class, or a JDK type listed below), and every
   `ClassName.method(` must name a method present in that class file.
3. Links. Every relative link in README.md, docs/*.md and docs/learn/*.md that
   points at LEARN.md or a chapter file must name a file that exists and,
   when it has a #fragment, an anchor that exists in that file.

Usage (after ./mvnw compile):  python3 scripts/check-learn.py
Exit status 0 when every check passes, 1 otherwise.
"""
import glob
import importlib.util
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs")
LEARN_FILES = [os.path.join(DOCS, "LEARN.md")] + sorted(glob.glob(os.path.join(DOCS, "learn", "*.md")))
JDK_TYPES = {"ArrayDeque", "AtomicLong", "AtomicLongArray", "AtomicMoveNotSupportedException",
             "HashMap", "IllegalArgumentException", "IllegalStateException", "LinkedHashMap",
             "LockSupport", "MethodHandles", "SplittableRandom", "StringBuilder", "ThreadMXBean",
             "UnsupportedOperationException", "VarHandle"}
# Not Java types: ISDA C function names, FIX field names, a value.
OTHER_NAMES = {"JpmcdsCdsContingentLegPV", "JpmcdsFeeLegPV", "PossDup", "PossDupFlag", "NaN"}

spec = importlib.util.spec_from_file_location("cookbook", os.path.join(ROOT, "scripts", "check-cookbook.py"))
cookbook = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cookbook)


def rel(p):
    return os.path.relpath(p, ROOT).replace(os.sep, "/")


def strip_fences(text):
    return re.sub(r"^```.*?^```", "", text, flags=re.S | re.M)


def check_snippets():
    units = []
    for path in LEARN_FILES:
        text = open(path, encoding="utf-8").read()
        for k, sec in enumerate(re.split(r"^### ", text, flags=re.M)):
            blocks = re.findall(r"^```java[ \t]*\n(.*?)^```", sec, flags=re.S | re.M)
            if blocks:
                units.append((f"{rel(path)} section {k}", blocks))
    if not units:
        return 0, []
    imports = cookbook.library_imports()
    work = tempfile.mkdtemp(prefix="learn-")
    src, cls = os.path.join(work, "src"), os.path.join(work, "cls")
    os.makedirs(src)
    os.makedirs(cls)
    names = {}
    for n, (where, blocks) in enumerate(units):
        members, stmts, imps = cookbook.split_recipe(blocks)
        body = "\n".join(m if "static" in m.split("{")[0] else "static " + m for m in members)
        names[n] = where
        with open(os.path.join(src, f"L{n:04d}.java"), "w", encoding="utf-8") as f:
            f.write(imports + "\n".join(imps) + f"\n@SuppressWarnings(\"all\")\npublic class L{n:04d} {{\n"
                    + body + "\nstatic void run() throws Throwable {\n" + "\n".join(stmts) + "\n}\n}\n")
    with open(os.path.join(src, "Stub.java"), "w", encoding="utf-8") as f:
        f.write("public class Stub { public static <T> T any() { return null; } }\n")
    javac = os.path.join(os.environ["JAVA_HOME"], "bin", "javac") if "JAVA_HOME" in os.environ else "javac"
    files = [os.path.join(src, f) for f in sorted(os.listdir(src))]
    log = subprocess.run([javac, "-Xmaxerrs", "100000", "-proc:none", "-encoding", "UTF-8",
                          "-cp", os.pathsep.join(cookbook.CLASS_DIRS), "-d", cls] + files,
                         capture_output=True, text=True).stderr.replace("\r", "")
    shutil.rmtree(work, ignore_errors=True)
    errors = []
    for err in re.split(r"\n(?=\S*L\d{4}\.java:\d+)", log):
        if "error:" not in err:
            continue
        if "cannot find symbol" in err:
            loc = re.search(r"location: (.*)", err)
            if loc and re.match(r"class L\d+$", loc.group(1).strip()):
                continue
        m = re.search(r"L(\d{4})\.java", err)
        errors.append((names[int(m.group(1))] if m else "?") + ": " + err.strip()[:600])
    return len(units), errors


def class_index():
    blobs, known = {}, set(JDK_TYPES | OTHER_NAMES)
    for root in cookbook.CLASS_DIRS:
        for d, _, files in os.walk(root):
            for f in files:
                if f.endswith(".class"):
                    parts = f[:-6].split("$")
                    data = open(os.path.join(d, f), "rb").read()
                    for p in parts:
                        known.add(p)
                        blobs[p] = blobs.get(p, b"") + data
    for p in glob.glob(os.path.join(ROOT, "**", "src", "*", "java", "**", "*.java"), recursive=True):
        known.add(os.path.basename(p)[:-5])
    return known, blobs


def check_names():
    known, blobs = class_index()
    errors = []
    for path in LEARN_FILES:
        text = open(path, encoding="utf-8").read()
        for m in re.finditer(r"`([A-Z][a-z]\w*[A-Z]\w*)(?=[.(`])", text):
            if m.group(1) not in known:
                errors.append(f"{rel(path)}:{text.count(chr(10), 0, m.start()) + 1}: "
                              f"unknown class `{m.group(1)}`")
        for m in re.finditer(r"`([A-Z]\w*)\.([a-z]\w*)\(", text):
            c, meth = m.groups()
            if c in blobs and meth.encode() not in blobs[c]:
                errors.append(f"{rel(path)}:{text.count(chr(10), 0, m.start()) + 1}: "
                              f"`{c}` has no method `{meth}`")
    return errors


def github_slugs(path):
    seen, out = {}, set()
    text = strip_fences(open(path, encoding="utf-8").read())
    for h in re.findall(r"^#{1,6} (.*)$", text, flags=re.M):
        h = re.sub(r"</?[A-Za-z][\w-]*(\s[^>]*)?>", "", h)
        h = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", h)
        base = re.sub(r"[^\w\- ]", "", h.strip().lower()).replace(" ", "-")
        n = seen.get(base)
        slug = base if n is None else f"{base}-{n}"
        seen[base] = (n or 0) + 1
        out.add(slug)
    out.update(re.findall(r'<a id="([^"]+)"', text))
    return out


def check_links():
    sources = [os.path.join(ROOT, "README.md")] + sorted(glob.glob(os.path.join(DOCS, "*.md"))) \
        + sorted(glob.glob(os.path.join(DOCS, "learn", "*.md")))
    cache, errors, count = {}, [], 0
    for src in sources:
        text = strip_fences(open(src, encoding="utf-8").read())
        for m in re.finditer(r"\]\((?!https?:|mailto:)([^)\s]*)\)", text):
            target = m.group(1)
            file_part, _, frag = target.partition("#")
            dest = os.path.normpath(os.path.join(os.path.dirname(src), file_part)) if file_part else src
            if not re.search(r"(^|[\\/])(LEARN\.md|learn[\\/][^\\/]+\.md)$", dest):
                continue
            count += 1
            where = f"{rel(src)}: ({target})"
            if not os.path.isfile(dest):
                errors.append(f"{where}: no such file")
                continue
            if frag:
                slugs = cache.setdefault(dest, github_slugs(dest))
                if frag not in slugs:
                    errors.append(f"{where}: no anchor #{frag}")
    return count, errors


def main():
    if not all(os.path.isdir(c) for c in cookbook.CLASS_DIRS):
        sys.exit("target/classes not found: run ./mvnw -DskipTests compile first")
    units, snippet_errors = check_snippets()
    name_errors = check_names()
    links, link_errors = check_links()
    for e in snippet_errors + name_errors + link_errors:
        print(e)
        print("--")
    print(f"{len(LEARN_FILES)} LEARN files; {units} sections with Java snippets, "
          f"{len(snippet_errors)} snippet errors; {len(name_errors)} class/method name errors; "
          f"{links} links into LEARN, {len(link_errors)} broken")
    sys.exit(1 if snippet_errors or name_errors or link_errors else 0)


if __name__ == "__main__":
    main()
