#!/usr/bin/env python3
"""PPNAM handheld SOP generator (suite template).

    python docs/sop/_template/build_sop.py docs/sop/content.md [--no-pdf] [--preview DIR]

Reads a Markdown content file with YAML front matter (format: README.md next to
this script), embeds fonts, CSS and screenshots, and writes
PPNAM_<file_stem>_Handheld_SOP.html next to the content file. Unless --no-pdf
is given it then prints the PDF with headless Edge, reads back the page each
section starts on, fills those page numbers into the Contents page and prints
again. --preview DIR renders every PDF page to PNG (needs PyMuPDF) plus a
contact sheet, for checking the layout by eye.

Requirements: Python 3.10+, Pillow, PyYAML. PyMuPDF (pymupdf) is optional:
without it the Contents page has no page numbers and --preview is skipped.
"""
from __future__ import annotations

import argparse
import base64
import html
import io
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml
from PIL import Image

TEMPLATE_VERSION = "1.0"
HERE = Path(__file__).resolve().parent
EDGE = Path("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe")

# Fixed section order for every SOP. Workflow sections (key "workflow-...") go
# between "signin" and "sounds". Contents and Revision history are generated.
ORDER = ["purpose", "before", "status", "signin", "WORKFLOWS", "sounds",
         "messages", "settings", "finishing", "troubleshooting"]
OPTIONAL = {"sounds"}  # only when front matter says scan_sounds: false (the Launcher)

SHOT_WIDTH = 600       # px, embedded screenshot width (rendered at ~50 mm in print)
SHOT_QUALITY = 82      # WebP quality

ICONS = {
    "note": '<svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M8 7v4.2M8 4.6v.1" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>',
    "warning": '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.6 15 14H1z" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M8 6.2v3.6M8 11.9v.1" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>',
    "supervisor": '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.3 14 3.6v4.1c0 3.4-2.6 6-6 7-3.4-1-6-3.6-6-7V3.6z" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M5.6 8.1 7.3 9.8l3.2-3.4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>',
}
CALLOUT_DEFAULT_TITLE = {"note": "Note", "warning": "Warning", "supervisor": "Supervisor"}
SOUND_CHIPS = {"ok": ("ok", "Success chirp"), "fail": ("fail", "Fail buzz"), "none": ("none", "Silent")}


class BuildError(Exception):
    pass


# ------------------------------------------------------------------ inline markup
def inline(text: str) -> str:
    """Escape HTML, then apply `code`, **bold**, *em*, [link](url), {ok}/{fail}/{none}."""
    codes: list[str] = []

    def stash(m):
        codes.append(f"<code>{html.escape(m.group(1), quote=False)}</code>")
        return f"\x00{len(codes) - 1}\x00"

    text = re.sub(r"`([^`]+)`", stash, text)
    text = html.escape(text, quote=False)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"<em>\1</em>", text)
    text = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", r'<a href="\2">\1</a>', text)
    for key, (cls, label) in SOUND_CHIPS.items():
        text = text.replace("{" + key + "}", f'<span class="snd {cls}">{label}</span>')
    text = re.sub(r"\x00(\d+)\x00", lambda m: codes[int(m.group(1))], text)
    return text


def attr(text: str) -> str:
    return html.escape(text, quote=True)


# ------------------------------------------------------------------ images
class Images:
    def __init__(self, base: Path, crop: dict):
        self.base = base
        self.crop_top = int(crop.get("top", 0))
        self.crop_bottom = int(crop.get("bottom", 0))
        self.used: set[str] = set()

    def screenshot(self, name: str, crop: bool = True) -> str:
        path = self.base / name
        if not path.is_file():
            raise BuildError(f"screenshot not found: {path}")
        self.used.add(path.name)
        im = Image.open(path).convert("RGB")
        if crop and (self.crop_top or self.crop_bottom):
            w, h = im.size
            im = im.crop((0, self.crop_top, w, h - self.crop_bottom))
        if im.width > SHOT_WIDTH:
            im = im.resize((SHOT_WIDTH, round(im.height * SHOT_WIDTH / im.width)), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "WEBP", quality=SHOT_QUALITY, method=6)
        return "data:image/webp;base64," + base64.b64encode(buf.getvalue()).decode()

    @staticmethod
    def png(path: Path, size: int | None = None) -> str:
        if not path.is_file():
            raise BuildError(f"image not found: {path}")
        im = Image.open(path).convert("RGBA")
        if size:
            im = im.resize((size, round(im.height * size / im.width)), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "PNG", optimize=True)
        return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def figure(src: str, caption: str, alt: str) -> str:
    cap = f"<figcaption>{inline(caption)}</figcaption>" if caption else ""
    return (f'<figure class="shot"><div class="phone"><img src="{src}" alt="{attr(alt or caption)}">'
            f"</div>{cap}</figure>")


# ------------------------------------------------------------------ block parser
FENCE_RE = re.compile(r"^:::\s*(step|shots|pills)\b\s*(.*)$")
STEP_ATTR_RE = re.compile(r"^(shot|caption|alt|label|crop):\s*(.*)$")
CALLOUT_RE = re.compile(r"^\[!(NOTE|WARNING|SUPERVISOR)\]\s*(.*)$")
LIST_RE = re.compile(r"^(\s*)([-*]|\d+\.)\s+(.*)$")
TONES = {"go", "warn", "stop", "neutral"}


class Parser:
    def __init__(self, images: Images, where: str):
        self.images = images
        self.where = where

    def err(self, msg: str):
        raise BuildError(f"{self.where}: {msg}")

    def blocks(self, lines: list[str], lede: bool = False) -> str:
        out: list[str] = []
        i = 0
        first_para = lede
        while i < len(lines):
            line = lines[i]
            s = line.strip()
            if not s:
                i += 1
                continue
            m = FENCE_RE.match(s)
            if m:
                j = i + 1
                while j < len(lines) and lines[j].strip() != ":::":
                    j += 1
                if j >= len(lines):
                    self.err(f"unclosed '::: {m.group(1)}' block")
                out.append(getattr(self, "fence_" + m.group(1))(m.group(2).strip(), lines[i + 1:j]))
                i = j + 1
                first_para = False
                continue
            if s.startswith(">"):
                j = i
                quoted = []
                while j < len(lines) and lines[j].strip().startswith(">"):
                    quoted.append(re.sub(r"^\s*>\s?", "", lines[j]))
                    j += 1
                out.append(self.callout(quoted))
                i = j
                first_para = False
                continue
            if s.startswith("### "):
                out.append(f"<h4>{inline(s[4:])}</h4>")
                i += 1
                continue
            if s.startswith("## "):
                out.append(f"<h3>{inline(s[3:])}</h3>")
                i += 1
                continue
            if s.startswith("# "):
                self.err(f"unexpected section heading inside a block: {s}")
            if s.startswith("|") or s.startswith("Table:"):
                j = i
                caption = None
                if s.startswith("Table:"):
                    caption = s[6:].strip()
                    j += 1
                rows = []
                while j < len(lines) and lines[j].strip().startswith("|"):
                    rows.append(lines[j].strip())
                    j += 1
                out.append(self.table(rows, caption))
                i = j
                first_para = False
                continue
            if LIST_RE.match(line):
                j = i
                items: list[list[str]] = []
                ordered = None
                while j < len(lines):
                    lm = LIST_RE.match(lines[j])
                    if lm and not lm.group(1):
                        kind = lm.group(2)[0].isdigit()
                        if ordered is None:
                            ordered = kind
                        elif ordered != kind:
                            break
                        items.append([lm.group(3)])
                        j += 1
                    elif lines[j].startswith("  ") and lines[j].strip() and items:
                        items[-1].append(lines[j].strip())
                        j += 1
                    else:
                        break
                out.append(self.listing(items, ordered))
                i = j
                first_para = False
                continue
            # paragraph
            j = i
            para = []
            while j < len(lines) and lines[j].strip() and not self.starts_block(lines[j]):
                para.append(lines[j].strip())
                j += 1
            cls = ' class="lede"' if first_para else ""
            out.append(f"<p{cls}>{inline(' '.join(para))}</p>")
            first_para = False
            i = j
        return "\n".join(out)

    @staticmethod
    def starts_block(line: str) -> bool:
        s = line.strip()
        return bool(FENCE_RE.match(s) or s.startswith((">", "#", "|", "Table:")) or LIST_RE.match(line))

    def listing(self, items, ordered) -> str:
        if items and all(it[0].startswith("[ ] ") for it in items):
            lis = "".join(f"<li>{inline(' '.join(it)[4:])}</li>" for it in items)
            return f'<ul class="check">{lis}</ul>'
        tag = "ol" if ordered else "ul"
        lis = "".join(f"<li>{inline(' '.join(it))}</li>" for it in items)
        return f"<{tag}>{lis}</{tag}>"

    def table(self, rows: list[str], caption: str | None) -> str:
        if len(rows) < 2 or not re.match(r"^\|[\s:|-]+\|$", rows[1]):
            self.err("a table needs a header row and a |---| separator row")

        def cells(r):
            return [c.strip() for c in r.strip().strip("|").split("|")]

        head = cells(rows[0])
        body = [cells(r) for r in rows[2:]]
        for r in body:
            if len(r) != len(head):
                self.err(f"table row has {len(r)} cells, header has {len(head)}: {r}")
        cap = f"<caption>{inline(caption)}</caption>" if caption else ""
        th = "".join(f"<th>{inline(c)}</th>" for c in head)
        trs = "".join("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>" for r in body)
        return f'<div class="tablewrap"><table>{cap}<thead><tr>{th}</tr></thead><tbody>{trs}</tbody></table></div>'

    def callout(self, quoted: list[str]) -> str:
        m = CALLOUT_RE.match(quoted[0].strip()) if quoted else None
        if not m:
            self.err("a '>' block must start with [!NOTE], [!WARNING] or [!SUPERVISOR] (the only callouts)")
        kind = m.group(1).lower()
        title = m.group(2).strip() or CALLOUT_DEFAULT_TITLE[kind]
        body = self.blocks(quoted[1:])
        return (f'<div class="callout {kind}"><p class="tag">{ICONS[kind]}<span>{inline(title)}</span></p>'
                f"{body}</div>")

    def fence_step(self, title: str, lines: list[str]) -> str:
        attrs = {}
        k = 0
        while k < len(lines) and STEP_ATTR_RE.match(lines[k].strip()):
            am = STEP_ATTR_RE.match(lines[k].strip())
            attrs[am.group(1)] = am.group(2).strip()
            k += 1
        body = self.blocks(lines[k:])
        head = ""
        if title:
            label = attrs.get("label")
            sn = f'<span class="sn">{inline(label)}</span>' if label else ""
            head = f'<p class="step-title">{sn}<span>{inline(title)}</span></p>'
        shot = ""
        if attrs.get("shot"):
            src = self.images.screenshot(attrs["shot"], crop=attrs.get("crop", "yes") != "none")
            shot = figure(src, attrs.get("caption", ""), attrs.get("alt", ""))
        cls = "step has-shot" if shot else "step"
        return f'<div class="{cls}"><div class="step-body">{head}{body}</div>{shot}</div>'

    def fence_shots(self, _arg: str, lines: list[str]) -> str:
        figs = []
        for ln in lines:
            if not ln.strip():
                continue
            parts = [p.strip() for p in ln.split("|")]
            name, caption, alt = (parts + ["", ""])[:3]
            figs.append(figure(self.images.screenshot(name), caption, alt))
        if not 1 <= len(figs) <= 3:
            self.err("'::: shots' takes one to three screenshots (one print row)")
        return f'<div class="shots">{"".join(figs)}</div>'

    def fence_pills(self, _arg: str, lines: list[str]) -> str:
        rows = []
        for ln in lines:
            if not ln.strip():
                continue
            parts = [p.strip() for p in ln.lstrip("- ").split("|")]
            if len(parts) != 4 or parts[0] not in TONES:
                self.err(f"pill rows are 'tone | Label | meaning | what to do' with tone in {sorted(TONES)}: {ln}")
            tone, label, meaning, action = parts
            rows.append(f'<div class="pill-row"><span class="pill {tone}"><span class="dot"></span>{inline(label)}</span>'
                        f'<p>{inline(meaning)}</p><p class="doact"><b>What to do:</b>{inline(action)}</p></div>')
        return f'<div class="pills">{"".join(rows)}</div>'


# ------------------------------------------------------------------ document
def split_front_matter(text: str) -> tuple[dict, str]:
    m = re.match(r"^---\s*\n(.*?)\n---\s*\n(.*)$", text, re.S)
    if not m:
        raise BuildError("content file must start with a '---' YAML front matter block")
    return yaml.safe_load(m.group(1)) or {}, m.group(2)


REQUIRED_META = ["app_name", "file_stem", "station", "mark", "accent", "accent_tint", "icon",
                 "version", "date", "device", "standfirst", "revisions"]


def parse_sections(body: str) -> list[dict]:
    body = re.sub(r"<!--.*?-->", "", body, flags=re.S)
    sections = []
    cur = None
    for line in body.splitlines():
        m = re.match(r"^#\s+(.+?)\s*\{#([a-z0-9-]+)\}\s*$", line)
        if m:
            cur = {"title": m.group(1), "key": m.group(2), "lines": []}
            sections.append(cur)
        elif line.startswith("# "):
            raise BuildError(f"section heading needs a key, e.g. '# Signing in {{#signin}}': {line}")
        elif cur is None:
            if line.strip():
                raise BuildError(f"text before the first section: {line}")
        else:
            cur["lines"].append(line)
    return sections


def check_order(sections: list[dict], scan_sounds: bool):
    keys = [s["key"] for s in sections]
    expected_fixed = [k for k in ORDER if k != "WORKFLOWS" and (scan_sounds or k not in OPTIONAL)]
    fixed = [k for k in keys if not k.startswith("workflow-")]
    if fixed != expected_fixed:
        raise BuildError("sections out of order or missing.\n  expected: " + ", ".join(expected_fixed)
                         + "\n  found:    " + ", ".join(fixed))
    wf = [i for i, k in enumerate(keys) if k.startswith("workflow-")]
    if not wf:
        raise BuildError("at least one '{#workflow-...}' section is required")
    lo, hi = keys.index("signin"), keys.index("sounds" if scan_sounds else "messages")
    if not all(lo < i < hi for i in wf):
        raise BuildError("workflow sections must sit between 'signin' and the next fixed section")


def fonts_css() -> str:
    faces = [("Archivo", "500 700", "Archivo-500700.woff2"),
             ("IBM Plex Sans", "400 600", "IBMPlexSans-400600.woff2"),
             ("IBM Plex Mono", "400", "IBMPlexMono-400.woff2"),
             ("IBM Plex Mono", "500", "IBMPlexMono-500.woff2"),
             ("IBM Plex Mono", "600", "IBMPlexMono-600.woff2")]
    out = []
    for fam, weight, fn in faces:
        data = base64.b64encode((HERE / "fonts" / fn).read_bytes()).decode()
        out.append(f"@font-face{{font-family:'{fam}';font-style:normal;font-weight:{weight};font-display:block;"
                   f"src:url(data:font/woff2;base64,{data}) format('woff2');}}")
    return "\n".join(out)


def build_html(content: Path, page_numbers: dict[str, int] | None = None) -> tuple[str, dict, list[dict]]:
    meta, body = split_front_matter(content.read_text(encoding="utf-8"))
    missing = [k for k in REQUIRED_META if k not in meta]
    if missing:
        raise BuildError("front matter is missing: " + ", ".join(missing))
    scan_sounds = meta.get("scan_sounds", True)
    sections = parse_sections(body)
    check_order(sections, scan_sounds)

    base = content.parent
    images = Images(base / meta.get("images_dir", "images"), meta.get("crop") or {})
    sec_html = []
    toc_side = []
    numbered = sections + [{"title": "Revision history", "key": "revisions", "lines": None}]

    # Contents page (unnumbered)
    rows = []
    for n, s in enumerate(numbered, 1):
        pg = str(page_numbers.get(s["key"], "")) if page_numbers else ""
        rows.append(f'<li><a href="#{s["key"]}"><span class="n">{n:02d}</span>'
                    f'<span class="t">{inline(s["title"])}</span><span class="pg">{pg}</span></a></li>')
        toc_side.append(f'      <li><a href="#{s["key"]}"><span class="n">{n:02d}</span><span>{inline(s["title"])}</span></a></li>')
    sec_html.append('<section class="sec" id="contents"><div class="sec-head">'
                    '<h2 class="sec-title">Contents</h2></div>'
                    f'<ol class="contents">{"".join(rows)}</ol></section>')

    for n, s in enumerate(numbered, 1):
        head = (f'<div class="sec-head"><span class="sec-num">{n:02d}</span>'
                f'<h2 class="sec-title">{inline(s["title"])}</h2></div>')
        if s["lines"] is None:
            revs = meta["revisions"]
            trs = "".join(f"<tr><td><code>{attr(str(r['version']))}</code></td><td>{attr(str(r['date']))}</td>"
                          f"<td>{inline(str(r['change']))}</td></tr>" for r in revs)
            inner = ('<div class="tablewrap"><table class="rev"><thead><tr><th>Version</th><th>Date</th><th>Change</th></tr></thead>'
                     f"<tbody>{trs}</tbody></table></div>")
        else:
            inner = Parser(images, f"section '{s['title']}'").blocks(s["lines"], lede=True)
        sec_html.append(f'<section class="sec" id="{s["key"]}">{head}\n{inner}\n</section>')

    footer_left = f"PPNAM {meta['app_name']} · Handheld SOP {meta['version']}"
    css = (HERE / "sop.css").read_text(encoding="utf-8").replace("__FOOTER_LEFT__", footer_left.replace('"', "'"))
    values = {
        "description": attr(meta.get("description", f"Standard operating procedure for the PPNAM {meta['app_name']} handheld ({meta['device']}).")),
        "template_version": TEMPLATE_VERSION,
        "doc_title": attr(f"{meta['app_name']} Handheld SOP {meta['version']}"),
        "fonts_css": fonts_css(),
        "sop_css": css,
        "accent": meta["accent"], "accent_tint": meta["accent_tint"],
        "mark": attr(str(meta["mark"])),
        "ppnam_logo": Images.png(HERE / "assets" / "ppnam-logo.png"),
        "icon": Images.png(base / meta["icon"], 256),
        "app_name": inline(meta["app_name"]),
        "standfirst": inline(meta["standfirst"]),
        "version": attr(str(meta["version"])), "date": attr(str(meta["date"])),
        "device": attr(meta["device"]), "station": inline(meta["station"]),
        "toc_side": "\n".join(toc_side),
        "sections": "\n\n".join(sec_html),
        "footer_left": attr(footer_left),
    }
    tpl = (HERE / "template.html").read_text(encoding="utf-8")
    out = re.sub(r"\{\{(\w+)\}\}", lambda m: values[m.group(1)], tpl)

    unused = sorted(p.name for p in images.base.glob("*.png") if p.name not in images.used
                    and (base / meta["icon"]).resolve() != p.resolve())
    if unused:
        print("note: screenshots not used in the document: " + ", ".join(unused))
    return out, meta, numbered


# ------------------------------------------------------------------ PDF
def print_pdf(html_path: Path, pdf_path: Path):
    if not EDGE.is_file():
        raise BuildError(f"Edge not found at {EDGE}; use --no-pdf")
    with tempfile.TemporaryDirectory(prefix="sop-edge-") as profile:
        cmd = [str(EDGE), "--headless", "--disable-gpu", "--no-pdf-header-footer", "--no-first-run",
               f"--user-data-dir={profile}", f"--print-to-pdf={pdf_path}", html_path.resolve().as_uri()]
        subprocess.run(cmd, check=True, capture_output=True, timeout=180)
    if not pdf_path.is_file() or pdf_path.stat().st_size == 0:
        raise BuildError("Edge did not write the PDF")


def section_pages(pdf_path: Path, numbered: list[dict]) -> dict[str, int] | None:
    try:
        import pymupdf
    except ImportError:
        try:
            import fitz as pymupdf  # older PyMuPDF
        except ImportError:
            return None
    doc = pymupdf.open(pdf_path)
    found: dict[str, int] = {}
    start = 2  # page 1 is the cover, page 2 the contents
    for n, s in enumerate(numbered, 1):
        label = f"{n:02d}"
        title = re.sub(r"\s+", " ", re.sub(r"[`*]", "", s["title"]))
        for p in range(start, doc.page_count):
            # the page's margin-box footer text may come first, so look for "NN Title" near the top
            head = " ".join(doc[p].get_text().split()[:40])
            if re.search(rf"(?:^|\s){label}\s+{re.escape(title.split()[0])}", head):
                found[s["key"]] = p + 1
                start = p + 1
                break
    doc.close()
    return found


def preview(pdf_path: Path, out_dir: Path):
    try:
        import pymupdf
    except ImportError:
        import fitz as pymupdf
    out_dir.mkdir(parents=True, exist_ok=True)
    doc = pymupdf.open(pdf_path)
    thumbs = []
    for i, page in enumerate(doc):
        pix = page.get_pixmap(dpi=70)
        p = out_dir / f"page-{i + 1:02d}.png"
        pix.save(p)
        thumbs.append(Image.open(p).convert("RGB"))
    cols = 6
    w, h = thumbs[0].size
    rows = (len(thumbs) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * (w + 12) + 12, rows * (h + 12) + 12), (90, 96, 104))
    for i, t in enumerate(thumbs):
        sheet.paste(t, (12 + (i % cols) * (w + 12), 12 + (i // cols) * (h + 12)))
    sheet.save(out_dir / "contact-sheet.png")
    print(f"preview: {len(thumbs)} pages -> {out_dir}")
    doc.close()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("content", type=Path, help="path to docs/sop/content.md")
    ap.add_argument("--no-pdf", action="store_true", help="write the HTML only")
    ap.add_argument("--preview", type=Path, help="render PDF pages to PNG in this folder")
    a = ap.parse_args(argv)
    content = a.content.resolve()
    try:
        page_html, meta, numbered = build_html(content)
        stem = f"PPNAM_{meta['file_stem']}_Handheld_SOP"
        html_path = content.parent / f"{stem}.html"
        html_path.write_text(page_html, encoding="utf-8")
        print(f"wrote {html_path} ({html_path.stat().st_size // 1024} KB)")
        if a.no_pdf:
            return 0
        pdf_path = content.parent / f"{stem}.pdf"
        print_pdf(html_path, pdf_path)
        pages = section_pages(pdf_path, numbered)
        if pages is not None:
            missing = [s["title"] for s in numbered if s["key"] not in pages]
            if missing:
                print("warning: could not find the start page of: " + ", ".join(missing))
            page_html, _, _ = build_html(content, pages)
            html_path.write_text(page_html, encoding="utf-8")
            print_pdf(html_path, pdf_path)
            again = section_pages(pdf_path, numbered)
            if again != pages:
                print("warning: page numbers moved on the second pass; check the Contents page")
        else:
            print("note: PyMuPDF not installed, so the Contents page has no page numbers")
        try:
            import pymupdf  # noqa: F401
            n = pymupdf.open(pdf_path).page_count
        except Exception:
            n = "?"
        print(f"wrote {pdf_path} ({pdf_path.stat().st_size // 1024} KB, {n} pages)")
        if a.preview:
            preview(pdf_path, a.preview)
    except BuildError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
