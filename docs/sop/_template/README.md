# PPNAM handheld SOP template (1.0)

One layout and one generator for the six handheld SOPs (Station 1 to 5 and the Launcher). Each
SOP is a single self-contained HTML file (fonts, CSS, icon and screenshots embedded) plus an A4
PDF printed from it. Every SOP looks the same: only the accent colour, the cover mark and the
content change.

Reference builds: Station 4 (`PPNAM_Station_4_AA/docs/sop/`) and Station 5
(`PPNAM_Station_5_AA/docs/sop/`). Copy their `content.md` as your starting point.

## Files

| File | What it is |
|---|---|
| `build_sop.py` | The generator. Python 3.10+, needs Pillow and PyYAML; PyMuPDF (`pymupdf`) is optional but wanted (Contents page numbers, `--preview`). |
| `template.html` | The page skeleton: cover, side contents (screen only), sections, footer. |
| `sop.css` | The one stylesheet: screen layout, A4 print rules, page numbers. |
| `fonts/` | Archivo, IBM Plex Sans and IBM Plex Mono (WOFF2), embedded into every SOP. |
| `assets/ppnam-logo.png` | The PPNAM mark on the cover. |

Do not fork these per app. If the layout needs to change, change it here, bump
`TEMPLATE_VERSION` in `build_sop.py`, then re-vendor and rebuild every app.

## How a repo uses it (the rule)

**Vendor an exact copy into the app repo at `docs/sop/_template/`** and commit it. The repo can
then rebuild its SOP on any PC without this folder. Copy all of it:

```bash
# from the app repo root (Git Bash)
mkdir -p docs/sop/_template
cp -r /c/Dev/Clients/PPNAM/UI_Design/sop-template/{build_sop.py,template.html,sop.css,fonts,assets,README.md} docs/sop/_template/
```

Never edit the vendored copy. Check it matches with
`diff -r --strip-trailing-cr /c/Dev/Clients/PPNAM/UI_Design/sop-template docs/sop/_template` (git may convert line endings on Windows) before you commit.

Each app repo then has:

```
docs/sop/
  _template/                         vendored copy of this folder (do not edit)
  content.md                         the SOP text (you write this)
  images/icon.png                    the app's 512 px icon from the icon pack
  images/NN_name.png                 screenshots, full 1080x1920 emulator captures
  PPNAM_<App>_Handheld_SOP.html      generated
  PPNAM_<App>_Handheld_SOP.pdf       generated
```

Build (from the repo root):

```bash
python docs/sop/_template/build_sop.py docs/sop/content.md --preview <scratch dir>
```

It writes the HTML, prints the PDF with headless Edge
(`C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe`, its own temporary profile),
finds the page each section starts on, writes those numbers into the Contents page, and prints
again. `--preview DIR` renders every page to PNG plus `contact-sheet.png`: **open it and look**
before you ship. `--no-pdf` writes the HTML only. Keep previews out of the repo.

## Per-app identity

| App | `accent` | `accent_tint` | `mark` | `icon` source (copy to `images/icon.png`) |
|---|---|---|---|---|
| Station 1 | `#1B4DA0` | `#9FBBE6` | `"1"` | `Android app logo directions/android/app1-blue/play-store-icon-512.png` |
| Station 2 | `#1D6B45` | `#A2CBB4` | `"2"` | `.../app2-green/play-store-icon-512.png` |
| Station 3 | `#7B1418` | `#E3B9BA` | `"3"` | `.../app3-crimson/play-store-icon-512.png` |
| Station 4 | `#55368C` | `#BCAAD9` | `"4"` | `.../app4-violet/play-store-icon-512.png` |
| Station 5 | `#A85F14` | `#E8C89D` | `"5"` | `.../app5-amber/play-store-icon-512.png` |
| Launcher | `#3A4655` | `#AEB8C4` | `"L"` | no pack icon yet (open decision): use the Launcher's own `ic_launcher` 512 px or the slate field with the PPNAM mark, and say so in the PR |

The accent colours headings' rules, section numbers, step badges, Note callouts and the cover
band. The tint is only used for the large cover mark. Status colours (green, amber, red) are
fixed and never take the accent.

## content.md

YAML front matter, then the sections as Markdown. Every key below is required except
`description`, `crop`, `images_dir` and `scan_sounds`.

```yaml
---
app_name: Station 4                 # cover title and footer
file_stem: Station_4                # output PPNAM_Station_4_Handheld_SOP.html / .pdf
station: Station 4 wastage          # cover fact "Station"
mark: "4"                           # the big cover character
accent: "#55368C"
accent_tint: "#BCAAD9"
icon: images/icon.png
version: 1.5.0
date: 2026-11-06
device: Chainway C72
standfirst: >-
  One or two sentences: what this SOP gets the operator through.
description: Standard operating procedure for ... (HTML meta description)
crop: {top: 72, bottom: 126}        # px cut from every screenshot (status bar, nav bar)
scan_sounds: true                   # false only for the Launcher (drops the sounds section)
revisions:
  - version: 1.5.0
    date: 2026-11-06
    change: "Suite release: visual refresh, consistency pass, scan sounds."
---
```

`crop: {top: 72, bottom: 126}` is right for the `c72_clone` emulator at 1080x1920. Use the same
values in every app so the frames match.

### Sections: fixed order

Each section is a level-1 heading with a key. The build fails if a fixed section is missing or out
of order. Section numbers, the Contents page and the Revision history (from `revisions`) are
generated; do not write them.

| # | Heading (suggested wording) | Key |
|---|---|---|
| 1 | Purpose and scope | `{#purpose}` |
| 2 | Before you start | `{#before}` |
| 3 | The status pill and header | `{#status}` |
| 4 | Signing in | `{#signin}` |
| 5… | One per workflow, named for the job (e.g. *Waste Collection*, *Loading a cage*) | `{#workflow-<name>}` |
| | Scan sounds and vibration (not for the Launcher) | `{#sounds}` |
| | Messages and what to do | `{#messages}` |
| | Settings | `{#settings}` |
| | Finishing up | `{#finishing}` |
| | Troubleshooting | `{#troubleshooting}` |

```markdown
# Signing in {#signin}
```

The first paragraph of a section is styled as its lead-in. Each section starts a new page in print.

### Blocks

| Write | Get |
|---|---|
| `## Heading` / `### Heading` | sub-heading / small accent label |
| `- item`, `1. item` (indent continuation lines by two spaces) | list / numbered list |
| `- [ ] item` | checklist (use in *Before you start*) |
| `**bold**`, `*italic*`, `` `code` ``, `[text](url)` | inline styles |
| `{ok}`, `{fail}`, `{none}` | the sound chips *Success chirp*, *Fail buzz*, *Silent* (sound table) |
| `Table: Caption` on the line before a pipe table | captioned table; 3-column tables get the standard message / meaning / action widths |
| `> [!NOTE] Title`, `> [!WARNING] Title`, `> [!SUPERVISOR] Title`, then `> text` lines | the three callouts. There are no others; anything else fails the build. |

A step with a screenshot (text left, phone frame right; never split across pages):

```markdown
::: step Scan bag code
label: Step 1
shot: 17_waste_step1.png
caption: Step 1. Submit stays grey until there is something to submit.
alt: Waste Collection, Step 1 of 5, "Scan bag code" with a Manual entry field
Body text, lists and callouts as usual.
:::
```

`label`, `shot`, `caption`, `alt` and `crop: none` are optional and must come straight after the
opening line. Without `shot` the step is full width. Use `label: Step N` for numbered workflow
steps, `label: Review` and similar for named ones.

Up to three screenshots in one row:

```markdown
::: shots
14_home_reconnecting.png | Caption | Alt text
31_home_station_offline.png | Caption | Alt text
:::
```

The status pill legend:

```markdown
::: pills
go | Connected | What it means. | What to do.
warn | Reconnecting | ... | ...
warn | Station N offline | ... | ...
stop | Offline | ... | ...
:::
```

## Writing rules (keep the six consistent)

- Describe the app as it behaves now (1.5.0). Quote every operator-facing string exactly as in the
  code, including its punctuation. The Messages section lists **every** refusal and error string.
- Plain words, active voice, sentence case. Name buttons in **bold** as they appear on screen.
- *Scan sounds and vibration*: the suite rule from `UI_Design/sounds/README.md` (success chirp =
  accepted, fail buzz = failed, the 1.5 s repeat-read rule, what is silent) and the app's own
  outcome → sound table taken from its code.
- *Settings*: never print the PIN, never show it being typed. Say "ask your supervisor".
- Screenshots: fresh from a clean build of `master` on the emulator against the app's simulator or
  mock, demo data only, no real passwords. Name them `NN_name.png` in reading order. The build
  prints a note for any image in `images/` that the document does not use: delete those.
- Revision history: `1.5.0`, `2026-11-06`, `Suite release: visual refresh, consistency pass, scan
  sounds.` (plus any earlier SOP versions the app had).

## Checking the result

1. Run the build with `--preview` and open `contact-sheet.png`, then a few pages at full size.
2. Check: the cover on its own page, Contents with page numbers, each section on a new page, no
   screenshot split across pages, no heading stranded at the bottom of a page, page numbers in
   the footer.
3. Put your contact sheet next to the Station 4 one: same cover layout, same section order, same
   styles. Only the accent and the content should differ.
