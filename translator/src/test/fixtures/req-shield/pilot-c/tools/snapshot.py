"""Pilot spec snapshot: turns Markdown files and Kotlin KDoc into blocks with stable anchors and fingerprints.

Usage: python3 -I snapshot.py OUT_DIR NAME=PATH...   (a .md path is Markdown; a .kt path contributes its KDoc)

Writes OUT_DIR/SOURCES.md (what an LLM reads and quotes) and OUT_DIR/sources.json (anchor -> text, lines, sha256).
An anchor is SOURCE#section-slug/N: N counts the blocks of that section (a top-level bullet with everything indented
under it, a paragraph, or a fenced block). A KDoc block is SOURCE#declaration-name.
The fingerprint rule is the one of `kv` reviewed notes: whitespace runs become one space, trim, SHA-256, 16 hex.
"""
import hashlib
import json
import re
import sys


def fp(text):
    return hashlib.sha256(re.sub(r"\s+", " ", text).strip().encode("utf-8")).hexdigest()[:16]


def slug(title):
    return re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")


def markdown_blocks(name, lines):
    """Yields (anchor, heading, first line, last line, text) per block."""
    section, heading, counter = "top", "(top)", 0
    i = 0
    while i < len(lines):
        line = lines[i]
        m = re.match(r"^(#{1,6})\s+(.*)$", line)
        if m:
            section, heading, counter = slug(m.group(2)), m.group(2).strip(), 0
            i += 1
            continue
        if not line.strip():
            i += 1
            continue
        start = i
        if re.match(r"^\s*```", line):
            i += 1
            while i < len(lines) and not re.match(r"^\s*```", lines[i]):
                i += 1
            i += 1
        elif re.match(r"^- |^\d+\. ", line):
            # A top-level list item: everything up to the next top-level item, heading or unindented paragraph.
            i += 1
            while i < len(lines):
                nxt = lines[i]
                if re.match(r"^- |^\d+\. |^#", nxt):
                    break
                if nxt.strip() and not nxt.startswith(" "):
                    break
                i += 1
        else:
            i += 1
            while i < len(lines) and lines[i].strip() and not re.match(r"^- |^\d+\. |^#|^\s*```", lines[i]):
                i += 1
        while i > start and not lines[i - 1].strip():
            i -= 1
        counter += 1
        text = "\n".join(lines[start:i])
        yield "%s#%s/%d" % (name, section, counter), heading, start + 1, i, text


def kdoc_blocks(name, lines):
    """Yields one block per KDoc comment (license header excluded): its text and the declaration after it."""
    i = 0
    while i < len(lines):
        if lines[i].strip().startswith("/**"):
            start = i
            while "*/" not in lines[i]:
                i += 1
            end = i
            body = []
            for l in lines[start:end + 1]:
                l = l.strip()
                l = re.sub(r"^/\*\*\s?", "", l)
                l = re.sub(r"\s?\*/$", "", l)
                l = re.sub(r"^\*\s?", "", l)
                body.append(l)
            decl_line = i + 1
            while decl_line < len(lines) and not lines[decl_line].strip():
                decl_line += 1
            decl = lines[decl_line].strip() if decl_line < len(lines) else ""
            m = re.search(r"(?:fun|interface|class)\s+(\w+)", decl)
            who = m.group(1) if m else "decl%d" % (start + 1)
            text = "\n".join(b for b in body if b is not None).strip()
            yield "%s#%s" % (name, who), decl, start + 1, end + 1, text
        i += 1


def main():
    out = sys.argv[1]
    blocks = []
    md = ["# Sources\n",
          "This is the team's specification text, split into blocks. Each block starts with its anchor in square",
          "brackets and its line numbers. When you cite a block, give its anchor and quote its words exactly as written",
          "here (a quote may be part of a block; it must not join text from two blocks).\n"]
    for arg in sys.argv[2:]:
        name, path = arg.split("=", 1)
        lines = open(path, encoding="utf-8").read().splitlines()
        md.append("\n## %s\n" % name)
        gen = kdoc_blocks(name, lines) if path.endswith(".kt") else markdown_blocks(name, lines)
        last_heading = None
        for anchor, heading, first, last, text in gen:
            if path.endswith(".kt"):
                md.append("[%s] (lines %d-%d, KDoc of: %s)\n%s\n" % (anchor, first, last, heading, text))
            else:
                if heading != last_heading:
                    md.append("### %s\n" % heading)
                    last_heading = heading
                md.append("[%s] (lines %d-%d)\n%s\n" % (anchor, first, last, text))
            blocks.append({"anchor": anchor, "source": name, "path": path, "first": first, "last": last,
                           "text": text, "sha256": fp(text)})
    with open(out + "/SOURCES.md", "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    with open(out + "/sources.json", "w", encoding="utf-8") as f:
        json.dump(blocks, f, indent=1, ensure_ascii=False)
    print("%d blocks" % len(blocks))


if __name__ == "__main__":
    main()
