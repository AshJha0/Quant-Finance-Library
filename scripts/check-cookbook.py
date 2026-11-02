#!/usr/bin/env python3
"""Compile every Java snippet in docs/COOKBOOK.md against the compiled
classes of both modules (core/target/classes and lowlatency/target/classes).

Each recipe's ```java blocks are wrapped into one class (declarations become
static members, statements go into a run() method) with wildcard imports of
every library package. Recipes are fragments: they use placeholder variables
such as `series` or `cov` that are never declared. A "cannot find symbol"
error located in the recipe class itself is therefore ignored. Every other
error is real: a missing or renamed library class, method or constructor, a
wrong argument type, or a syntax error.

Usage (after ./mvnw compile):  python3 scripts/check-cookbook.py
Exit status 0 when every recipe compiles, 1 otherwise.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLASS_DIRS = [os.path.join(ROOT, "core", "target", "classes"),
              os.path.join(ROOT, "lowlatency", "target", "classes")]
COOKBOOK = os.path.join(ROOT, "docs", "COOKBOOK.md")
JDK_PACKAGES = ["java.util", "java.util.function", "java.util.stream", "java.nio.file",
                "java.time", "java.io", "java.util.concurrent", "java.nio",
                "java.nio.charset", "java.util.concurrent.atomic", "java.math", "java.net"]
DECL = re.compile(r"^(?!new |try|for|if|while|return)(?=[A-Za-z])((public |final |static |"
                  r"abstract |sealed |private )*(class|record|interface|enum) \w+|(static |"
                  r"private |public )*[\w<>\[\], .?]+ \w+\([^;=]*\)\s*(throws [\w, .]+)?\s*\{)")


def library_imports():
    pkgs = set()
    for root in CLASS_DIRS:
        for d, _, files in os.walk(root):
            if d != root and any(f.endswith(".class") for f in files):
                pkgs.add(os.path.relpath(d, root).replace(os.sep, "."))
    nested = ["com.quantfinlib.pricing.BlackScholes.OptionType",
              "com.quantfinlib.execution.BenchmarkExecutor.Benchmark",
              # 3.2 replacements of deprecated same-named types: a single-type
              # import wins over the wildcard, so recipes see the new type.
              "com.quantfinlib.io.CsvBarLoader", "com.quantfinlib.io.HttpBarFetcher",
              "com.quantfinlib.io.UniverseCsvLoader", "com.quantfinlib.marketdata.AggregatedBook"]
    return "".join(f"import {n};\n" for n in nested) + "".join(
        f"import {p}.*;\n" for p in sorted(pkgs) + JDK_PACKAGES)


def split_recipe(blocks):
    members, stmts, imps = [], [], []
    for b in blocks:
        b = re.sub(r"=\s*/\*.*?\*/\s*;", "= Stub.any();", b, flags=re.S)
        imps += re.findall(r"^import .*;$", b, flags=re.M)
        lines = re.sub(r"^import .*;$", "", b, flags=re.M).split("\n")
        i = 0
        while i < len(lines):
            line = lines[i]
            if DECL.match(line) or (line.startswith("@") and i + 1 < len(lines)
                                    and DECL.match(lines[i + 1])):
                depth, chunk, started = 0, [], False
                while i < len(lines):
                    chunk.append(lines[i])
                    depth += lines[i].count("{") - lines[i].count("}")
                    started = started or "{" in lines[i]
                    i += 1
                    if started and depth <= 0:
                        break
                    if not started and lines[i - 1].rstrip().endswith(";"):
                        break
                members.append("\n".join(chunk))
            else:
                stmts.append(line)
                i += 1
    return members, stmts, imps


def main():
    if not all(os.path.isdir(c) for c in CLASS_DIRS):
        sys.exit("target/classes not found: run ./mvnw -DskipTests compile first")
    text = open(COOKBOOK, encoding="utf-8").read()
    parts = re.split(r"^## (\d+)\. ", text, flags=re.M)
    imports = library_imports()
    work = tempfile.mkdtemp(prefix="cookbook-")
    src, cls = os.path.join(work, "src"), os.path.join(work, "cls")
    os.makedirs(src)
    os.makedirs(cls)
    count = 0
    for i in range(1, len(parts), 2):
        n = int(parts[i])
        blocks = re.findall(r"```java\n(.*?)```", parts[i + 1], flags=re.S)
        if not blocks:
            continue
        count += 1
        members, stmts, imps = split_recipe(blocks)
        body = "\n".join(m if "static" in m.split("{")[0] else "static " + m for m in members)
        with open(os.path.join(src, f"R{n:03d}.java"), "w", encoding="utf-8") as f:
            f.write(imports + "\n".join(imps) + f"\n@SuppressWarnings(\"all\")\npublic class R{n:03d} {{\n"
                    + body + "\nstatic void run() throws Throwable {\n" + "\n".join(stmts) + "\n}\n}\n")
    with open(os.path.join(src, "Stub.java"), "w", encoding="utf-8") as f:
        f.write("public class Stub { public static <T> T any() { return null; } }\n")
    javac = os.path.join(os.environ["JAVA_HOME"], "bin", "javac") if "JAVA_HOME" in os.environ else "javac"
    files = [os.path.join(src, f) for f in sorted(os.listdir(src))]
    log =subprocess.run([javac, "-Xmaxerrs", "100000", "-proc:none", "-encoding", "UTF-8",
                          "-cp", os.pathsep.join(CLASS_DIRS), "-d", cls] + files,
                         capture_output=True, text=True).stderr.replace("\r", "")
    shutil.rmtree(work, ignore_errors=True)
    real, bad = [], set()
    for err in re.split(r"\n(?=\S*R\d{3}\.java:\d+)", log):
        if "error:" not in err:
            continue
        if "cannot find symbol" in err:
            loc = re.search(r"location: (.*)", err)
            if loc and re.match(r"class R\d+$", loc.group(1).strip()):
                continue
        real.append(err.strip())
        m = re.search(r"R(\d{3})\.java", err)
        if m:
            bad.add(int(m.group(1)))
    for err in real:
        print(err[:600])
        print("--")
    print(f"{count} recipes with Java; {len(bad)} failing: {sorted(bad)}")
    sys.exit(1 if real else 0)


if __name__ == "__main__":
    main()
