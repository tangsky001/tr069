"""一次性清理脚本：删除业务类里的 log.info / log.debug 语句。

只做机械删除，删完由编译错误驱动人工修补（空代码块、未使用变量等）。
"""
import os
import re
import sys

ROOT = r"e:\tr069\src\main\java\com\tr069\acs"
SKIP_DIRS = {"log"}

PATTERN = re.compile(r"\blog\.(debug|info)\s*\(")


def strip_statements(text: str):
    removed = 0
    out = []
    i = 0
    n = len(text)
    while i < n:
        m = PATTERN.search(text, i)
        if not m:
            out.append(text[i:])
            break
        # 判断它是不是一个独立语句的起点：前面只能是空白 / { / ; / : 
        start = m.start()
        head = text[max(0, start - 40):start]
        last_sig = ""
        for ch in reversed(head):
            if not ch.isspace():
                last_sig = ch
                break
        if last_sig not in ("", "{", ";", ":", "}"):
            out.append(text[i:m.end()])
            i = m.end()
            continue
        # 括号配对，找到语句结束的分号
        depth = 0
        k = m.end() - 1
        while k < n:
            c = text[k]
            if c == '"':
                k += 1
                while k < n and text[k] != '"':
                    if text[k] == "\\":
                        k += 1
                    k += 1
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        end = k + 1
        while end < n and text[end] in " \t":
            end += 1
        if end < n and text[end] == ";":
            end += 1
        # 向上吞掉整行的行首缩进，向下吞掉整行
        line_start = text.rfind("\n", 0, start) + 1
        if text[line_start:start].strip() == "":
            start = line_start
        line_end = text.find("\n", end)
        line_end = n if line_end == -1 else line_end
        if text[end:line_end].strip() == "":
            end = line_end + 1
        out.append(text[i:start])
        i = end
        removed += 1
    return "".join(out), removed


total_files = 0
total_stmts = 0
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
    for fn in filenames:
        if not fn.endswith(".java"):
            continue
        p = os.path.join(dirpath, fn)
        with open(p, "r", encoding="utf-8") as f:
            src = f.read()
        if not PATTERN.search(src):
            continue
        new, removed = strip_statements(src)
        if removed == 0 or new == src:
            continue
        with open(p, "w", encoding="utf-8", newline="") as f:
            f.write(new)
        total_files += 1
        total_stmts += removed
        print(f"{removed:3d}  {os.path.relpath(p, ROOT)}")

print(f"\n共处理 {total_files} 个文件，删除 {total_stmts} 条语句")
