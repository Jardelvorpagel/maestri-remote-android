#!/usr/bin/env python3
"""Deterministic implementation-contract gate for Terminal visual fixes.

Proves IMPLEMENTATION contract (scoped file/function assertions on the
Compose sources), NOT pixel equivalence of a tablet with no design frame
(v4 review section 6: no tablet frame exists, phone 393x852 vs tablet
landscape cannot map 1:1).

Usage (repo root):
  python3 terminal-fidelity-gate.py            # normal gate, exit 0 iff all PASS
  python3 terminal-fidelity-gate.py --selftest # deterministic mutation autotest
  python3 terminal-fidelity-gate.py --css-root <dir>  # also validate reference CSS literals

No network, no device, no timestamps/randomness in output. Stdlib only.
Limit: textual parsing, not a Kotlin compiler. Patterns are anchored to the
owner file AND owner function/composable (see _scope); a move of the code to
another function fails loudly rather than passing silently.
"""
import argparse
import re
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Dict, List, Optional, Tuple

ROOT = Path(__file__).resolve().parent
TS = ROOT / "app/src/main/java/app/maestri/ui/screens/TerminalScreen.kt"
TC = ROOT / "app/src/main/java/app/maestri/ui/screens/TerminalChip.kt"
TP = ROOT / "app/src/main/java/app/maestri/ui/screens/TerminalComposer.kt"
TOK = ROOT / "app/src/main/java/app/maestri/ui/Style/Tokens.kt"
HTML = ROOT / "app/src/main/assets/terminal/index.html"


def _scope(src: str, start_pat: str, end_pat: Optional[str] = None) -> str:
    """Return source slice of the owner block; empty string if owner missing."""
    m = re.search(start_pat, src)
    if not m:
        return ""
    tail = src[m.start():]
    if end_pat:
        e = re.search(end_pat, tail[1:])
        if e:
            return tail[: e.start() + 1]
    return tail[:6000]


def check_back_chevron(s: Dict[str, str]):
    b = _scope(s["screen"], r"val backBtn.*?=\s*\{", r"\n        val \w+")
    if not b:
        return False, "owner val backBtn not found in TerminalScreen.kt"
    if re.search(r"ArrowBack|arrow_back|\"<\"|Text\(\s*\"‹", b):
        return False, "glyph/arrow icon instead of vector chevron"
    ok = bool(re.search(r"Chevron|chevron|rememberVectorPainter|ImageVector.*[Cc]hevron|pathData|Canvas\s*\(", b))
    return ok, "vector chevron in backBtn" if ok else "no vector chevron in backBtn"


def check_back_box_touch(s: Dict[str, str]):
    b = _scope(s["screen"], r"val backBtn.*?=\s*\{", r"\n        val \w+")
    if not b:
        return False, "owner val backBtn not found"
    # Comments mention the rejected shapes; only code is scanned.
    code = "\n".join(l for l in b.splitlines() if not l.strip().startswith("//"))
    # Touch layer (outer, clickable) and visual box (inner) are DIFFERENT sizes:
    # outer .size(48.dp) absorbs the tap, inner .size(44.dp) paints #232220+ring.
    # Never anchor on the first .size — collect all and require the 48+44 pair.
    sizes = [int(x) for x in re.findall(r"\.size\((\d+)\.dp\)", code)]
    touch48 = 48 in sizes and "clickable" in code
    box44 = 44 in sizes
    # A CircleShape clip on the touch layer slices the 44dp box: its corners sit
    # 31.1dp from the center, past a 24dp circle radius, so the four 12dp corners
    # render as a blob. Same outcome when M3 IconButton (40dp CircleShape).
    chain = code.split("clickable", 1)[0]
    circle_clip = bool(re.search(r"CircleShape", chain))
    m3_icon_button = bool(re.search(r"\bIconButton\s*\(", code))
    # The a11y contract that uiautomator actually merges into ONE
    # android.widget.Button with ACTION_CLICK: desc + role in the same semantics
    # block that follows clickable. Split across nodes -> role-less View + orphan Button.
    sem = re.search(r"\.semantics\s*(?:\(\s*mergeDescendants\s*=\s*true\s*\)\s*)?\{([^}]*)\}", code)
    sem_body = sem.group(1) if sem else ""
    desc = bool(re.search(r'contentDescription\s*=\s*"Voltar"', sem_body))
    role_button = bool(re.search(r"role\s*=\s*Role\.Button", sem_body))
    after_click = bool(re.search(r"clickable\s*\([\s\S]*?\.semantics", code))
    round12 = bool(re.search(r"RoundedCornerShape\(\s*12\.dp\s*\)", code)) or any(
        dp == "12" and re.search(rf"\b{n}\b", code)
        for n, dp in re.findall(r"val (\w+)\s*=\s*RoundedCornerShape\(\s*(\d+)\.dp\s*\)", s["screen"]))
    ok = (touch48 and box44 and role_button and desc and after_click and round12
          and not circle_clip and not m3_icon_button)
    return ok, (f"touch48={touch48} box44={box44} roleButton={role_button} desc={desc} "
                f"semAfterClick={after_click} r12={round12} circleClip={circle_clip} "
                f"iconButton={m3_icon_button} sizes={sizes}")


def _subbar(s: Dict[str, str]) -> str:
    """The .tchips row: the SECOND Row inside the !chromeTight && !fullscreen block."""
    m = re.search(r"if\s*\(\s*!chromeTight\s*&&\s*!fullscreen\s*\)\s*\{", s["screen"])
    if not m:
        return ""
    tail = s["screen"][m.end():]
    rows = [r.start() for r in re.finditer(r"\n            Row\(", tail)]
    if len(rows) < 2:
        return ""
    return tail[rows[1]: rows[1] + 900]


def check_subbar_order(s: Dict[str, str]):
    b = _subbar(s)
    if not b:
        return False, "subbar Row not found in TerminalScreen.kt"
    code = "\n".join(l for l in b.splitlines() if not l.strip().startswith("//"))
    idx = {k: code.find(k) for k in ("floorChip()", "fullscreenBtn()", "Spacer(Modifier.weight(1f))", "displaySettingsBtn()")}
    ordered = all(idx[k] >= 0 for k in idx) and idx["floorChip()"] < idx["fullscreenBtn()"] < idx["Spacer(Modifier.weight(1f))"] < idx["displaySettingsBtn()"]
    start14 = bool(re.search(r"padding\(\s*start\s*=\s*14\.dp\s*,\s*end\s*=\s*14\.dp\s*\)", code))
    gap14 = bool(re.search(r"Arrangement\.spacedBy\(\s*14\.dp\s*\)", code))
    ok = ordered and start14 and gap14
    return ok, f"order(Terro<Expand<Spacer<Settings)={ordered} pad14={start14} gap14={gap14} idx={idx}"


def check_display_settings_box(s: Dict[str, str]):
    b = _scope(s["screen"], r"val displaySettingsBtn.*?=\s*\{", r"\n        // |\n        val [a-zA-Z]+:")
    if not b:
        return False, "owner val displaySettingsBtn not found"
    code = "\n".join(l for l in b.splitlines() if not l.strip().startswith("//"))
    sizes = [int(x) for x in re.findall(r"\.size\((\d+)\.dp\)", code)]
    box44 = 44 in sizes
    card2 = "0xFF232220" in code
    ring = bool(re.search(r"border\(\s*1\.dp\s*,\s*Color\(0x24B0AEA5\)", code))
    round12 = bool(re.search(r"RoundedCornerShape\(\s*12\.dp\s*\)", code)) or any(
        dp == "12" and re.search(rf"\b{n}\b", code)
        for n, dp in re.findall(r"val (\w+)\s*=\s*RoundedCornerShape\(\s*(\d+)\.dp\s*\)", s["screen"]))
    sliders = "SlidersIcon" in code
    accent = "0xFFD97B54" in code
    role_button = bool(re.search(r"role\s*=\s*Role\.Button", code))
    tuned = "Icons.Filled.Tune" in s["screen"]
    ok = box44 and card2 and ring and round12 and sliders and accent and role_button and not tuned
    return ok, (f"box44={box44} card2={card2} ring={ring} r12={round12} sliders={sliders} "
                f"accent={accent} roleButton={role_button} tuneIcon={tuned} sizes={sizes}")


def check_title(s: Dict[str, str]):
    b = _scope(s["screen"], r"val titleBlock.*?=\s*\{", r"\n        val connChip")
    if not b:
        return False, "owner val titleBlock not found"
    center = bool(re.search(r"TextAlign\.Center|horizontalAlignment\s*=\s*Alignment\.CenterHorizontally|contentAlignment\s*=\s*Alignment\.Center", b))
    sp21 = bool(re.search(r"fontSize\s*=\s*21\.sp", b))
    w600 = bool(re.search(r"FontWeight\.(SemiBold|Bold)|fontWeight\s*=\s*600|W600", b))
    sub11 = bool(re.search(r"fontSize\s*=\s*11\.sp", b))
    ok = center and sp21 and w600 and sub11
    return ok, f"center={center} 21sp={sp21} 600={w600} sub11sp={sub11}"


def check_expand_vector(s: Dict[str, str]):
    b = _scope(s["screen"], r"val fullscreenBtn.*?=\s*\{", r"\n        // Scroll speed")
    if not b:
        return False, "owner val fullscreenBtn not found"
    if re.search(r"Text\(\s*if\s*\(fullscreen\)", b):
        return False, "text glyph U+2921/U+2922 instead of vector"
    ok = bool(re.search(r"Icon\(\s*(CornersExpand|Fullscreen|Expand|Chevron)|ImageVector|rememberVectorPainter|vectorResource|drawPath", b))
    return ok, "vector expand icon" if ok else "no vector expand icon"


def check_chip_circle_48(s):
    c = s['chip']
    i = c.find('val pill = CircleShape')
    if i < 0:
        return False, 'collapsed chip is not CircleShape (design .termpill-btn circle 48px)'
    blk = c[i:i + 400]
    ok = '.size(48.dp)' in blk
    return ok, 'collapsed chip CircleShape + 48dp' if ok else 'CircleShape without .size(48.dp)'


def check_chip_transition(s: Dict[str, str]):
    # Real transition in TerminalScreen.kt: an IME-reactive LaunchedEffect must
    # OPEN the panel when the keyboard is closed (initial closed + open->closed).
    # Accepted forms (keep the check in sync if a new form is introduced):
    #   chipOpen = !imeUp | if (!imeUp) [{] chipOpen = true | chipOpen = imeUp == false
    #   else-if guardado: if (imeUp) {...} else [if (guards)] [{] chipOpen = true
    #   (same with imeVisible). Unconditional `chipOpen = false` is the known bug.
    scr = s["screen"]
    ok_screen, detail = False, "no ime-reactive auto-open in TerminalScreen.kt"
    for m in re.finditer(r"LaunchedEffect\(([^)]*)\)", scr):
        keys = m.group(1)
        tag = "imeUp" if "imeUp" in keys else ("imeVisible" if "imeVisible" in keys else "")
        if not tag:
            continue
        body = scr[m.end():m.end() + 600]
        neg = f"chipOpen\\s*=\\s*!{tag}"
        guard = f"if\\s*\\(\\s*!{tag}\\s*\\)\\s*\\{{?\\s*chipOpen\\s*=\\s*true"
        eqfalse = f"chipOpen\\s*=\\s*{tag}\\s*==\\s*false"
        if re.search(neg, body) or re.search(guard, body) or re.search(eqfalse, body):
            ok_screen, detail = True, "auto-open: IME-closed opens panel"
            break
        m_else = re.search(
            r"if\s*\(\s*ime(Up|Visible)\s*\)\s*\{[^}]*\}\s*else(\s+if\s*\(([^)]*)\))?\s*\{?\s*chipOpen\s*=\s*true",
            body)
        if m_else:
            cond = m_else.group(3)  # None = plain else of if(imeUp): IME-closed by construction
            if cond is None or re.search(r"!(inChat|locked|chipManualClose|ime(Up|Visible))", cond):
                ok_screen, detail = True, "auto-open: guarded else-if opens on IME-closed"
                break
            detail = "else-if opens without IME-closed guards"
            continue
        detail = "ime-keyed effect exists but never opens on IME-closed"
    c = s["chip"]
    panel = "AnimatedVisibility" in c and "visible = expanded" in c
    ok = ok_screen and panel
    return ok, f"auto-open={ok_screen} ({detail}); panel-present={panel}"


def check_switch_order(s: Dict[str, str]):
    p = s["composer"]
    ms = [m.start() for m in re.finditer(r'SegmentButton\("', p)]
    labels = re.findall(r'SegmentButton\("([^"]+)"', p)
    if len(labels) < 2:
        return False, f"expected 2 SegmentButton, found {labels}"
    ok = labels[0] == "Terminal" and labels[1] == "Chat"
    return ok, f"order={labels} (design Terminal|Chat)"


def check_switch_track(s: Dict[str, str]):
    p = s["composer"]
    fill = re.search(r"ComposerFill\s*=\s*Color\(0xFF10111A\)", p)
    track_alpha = re.search(r"ComposerTrackAlpha\s*=\s*0\.88f", p)
    track_use = bool(re.search(r"copy\(alpha\s*=\s*ComposerTrackAlpha\)", p))
    panel_alpha = bool(re.search(r"ComposerFillAlpha\s*=\s*0\.96f", p))
    ring = re.search(r"ComposerRing\s*=\s*Color\(0xFFA9B1D6\)\.copy\(alpha\s*=\s*0\.(\d+)f\)", p)
    ok = bool(fill) and bool(track_alpha) and track_use and panel_alpha and bool(ring) and "0.14f" in ring.group(0)
    return ok, f"track88={bool(track_alpha) and track_use} panel96={panel_alpha} ring={ring.group(0) if ring else '?'}"


def check_switch_tokens(s: Dict[str, str]):
    p = s["composer"]
    a = "Color(0xFF3D4150)" in p
    b = "Color(0xFFF2F3F7)" in p
    c = "Color(0xFF9AA0B4)" in p
    return (a and b and c), f"on={a} onText={b} offText={c}"


def check_quickkeys(s: Dict[str, str]):
    c = s["chip"]
    fill = "foreground.copy(alpha = 0.08f)" in c
    ring = "foreground.copy(alpha = 0.16f)" in c
    radius = "RoundedCornerShape(10.dp)" in c
    height = "44.dp" in c
    ok = fill and ring and radius and height
    return ok, f"fill.08={fill} ring.16={ring} r10={radius} h44={height}"


def check_composer(s: Dict[str, str]):
    p = s["composer"]
    fill = "Color(0xFF10111A)" in p
    alpha = bool(re.search(r"ComposerFillAlpha\s*=\s*0\.96f", p)) or "copy(alpha = 0.96f)" in p
    ring = "0.14f" in p and "ComposerRing" in p
    radius = "RoundedCornerShape(26.dp)" in p
    ok = fill and alpha and ring and radius
    return ok, f"fill={fill} alpha96={alpha} ring14={ring} r26={radius}"


def check_terreo(s: Dict[str, str]):
    t = s["screen"]
    b = _scope(s["screen"], r"val floorChip.*?=\s*\{", r"\n        val fullscreenBtn")
    if not b:
        return False, "owner val floorChip not found"
    card = "canvasSurface" in b or "DarkSurface" in b or "0xFF2B2A26" in b
    pad = bool(re.search(r"\.padding\(horizontal\s*=\s*8\.dp,\s*vertical\s*=\s*3\.dp\)|\.padding\(horizontal\s*=\s*15\.dp|\.padding\(.*8\.dp", b))
    tok = "Color(0xFF2B2A26)" in s["tokens"]
    ok = card and pad and tok
    return ok, f"card2B2A26={card} padding={pad} token={tok}"


def check_plus(s: Dict[str, str]):
    p = s["composer"]
    b = _scope(s["composer"], r"fun AttachButton", r"\n@Composable\nprivate fun SendButton")
    if not b:
        return False, "owner fun AttachButton not found"
    # Canvas + 2 drawLine (round caps) is a valid vector plus; Text("+") is not.
    # Touch must be REAL (>=48dp layer or minimumInteractiveComponentSize) and
    # gates the verdict — a bare 34dp clickable box FAILs.
    canvas_vector = "Canvas(" in b and b.count("drawLine") >= 2
    icon_vector = bool(re.search(r"Icon\(|ImageVector|vectorResource|rememberVectorPainter", b))
    vector = (icon_vector or canvas_vector) and 'Text("+"' not in b
    glyph17 = ".size(17.dp)" in b
    box34 = ".size(34.dp)" in b
    touch48 = "minimumInteractiveComponentSize" in b or bool(re.search(r"\.size\((48|56)\.dp\)", b))
    ok = vector and glyph17 and box34 and touch48
    return ok, f"vector={vector} (canvas2line={canvas_vector}) glyph17={glyph17} box34={box34} touch48={touch48}"


def check_fit_copy(s: Dict[str, str]):
    h = s["html"]
    if not h:
        return False, "assets/terminal/index.html not found"
    fit = "#fit" in h or '"fit"' in h or "fit.btn" in h
    copy = "#copy" in h or '"copy"' in h or "copy.btn" in h
    ok = fit and copy
    return ok, f"fit={fit} copy={copy} (secondary: valid only if primary contracts pass)"


CHECKS = [
    ("back-chevron-vector", check_back_chevron),
    ("back-box44-touch48", check_back_box_touch),
    ("subbar-terro-expand-spacer-settings", check_subbar_order),
    ("display-settings-box44-card2-sliders", check_display_settings_box),
    ("title-center-21sp-600-sub11", check_title),
    ("expand-vector", check_expand_vector),
    ("chip-circle-48", check_chip_circle_48),
    ("chip-keysclosed-opens-panel", check_chip_transition),
    ("switch-terminal-first", check_switch_order),
    ("switch-track-10111A-a88", check_switch_track),
    ("switch-tokens-selected", check_switch_tokens),
    ("quickkeys-fill-ring-r10-h44", check_quickkeys),
    ("composer-fill96-ring14-r26", check_composer),
    ("terreo-2B2A26-padding", check_terreo),
    ("plus-vector-34-touch", check_plus),
    ("fit-copy-secondary", check_fit_copy),
]


def load(root: Path) -> Dict[str, str]:
    def rd(p: Path) -> str:
        try:
            return (root / p.relative_to(ROOT)).read_text() if root != ROOT else p.read_text()
        except FileNotFoundError:
            return ""

    return {
        "screen": rd(TS),
        "chip": rd(TC),
        "composer": rd(TP),
        "tokens": rd(TOK),
        "html": rd(HTML),
    }


def run_gate(s: Dict[str, str]) -> Tuple[list, int, int]:
    rows = [(name, *fn(s)) for name, fn in CHECKS]
    passed = sum(1 for _, ok, _ in rows if ok)
    return rows, passed, len(rows)


def _ime_effect_window(scr):
    for m in re.finditer(r"LaunchedEffect\(([^)]*)\)", scr):
        keys = m.group(1)
        tag = "imeUp" if "imeUp" in keys else ("imeVisible" if "imeVisible" in keys else "")
        if tag:
            return m.end(), tag
    return None, ""


def _chip_fixed_and_mutated(s):
    # Probe the REAL auto-open branch. fixed = tree as-is (must already contain a
    # recognized form — synthesis in the wrong branch caused the v3 stale-probe FAIL).
    # mutated negates THAT branch. Every substitution is verified as applied;
    # (fixed, None) means fail-closed: the caller FAILs the family.
    scr = s["screen"]
    start, tag = _ime_effect_window(scr)
    if start is None:
        return None, None
    body = scr[start:start + 600]
    forms = [
        f"chipOpen\\s*=\\s*!{tag}",
        f"if\\s*\\(\\s*!{tag}\\s*\\)\\s*\\{{?\\s*chipOpen\\s*=\\s*true",
        f"chipOpen\\s*=\\s*{tag}\\s*==\\s*false",
    ]
    has_direct = any(re.search(p, body) for p in forms)
    m_else = re.search(
        r"if\s*\(\s*ime(Up|Visible)\s*\)\s*\{[^}]*\}\s*else(\s+if\s*\(([^)]*)\))?\s*\{?\s*chipOpen\s*=\s*true",
        body)
    has_else = bool(m_else) and (m_else.group(3) is None or
                bool(re.search(r"!(inChat|locked|chipManualClose|ime(Up|Visible))", m_else.group(3))))
    if not (has_direct or has_else):
        return None, None
    fixed = scr
    fbody, negated = body, None
    c1 = fbody.replace(f"chipOpen = !{tag}", f"chipOpen = {tag}", 1)
    if c1 != fbody:
        negated = c1
    if negated is None:
        c4 = re.sub(f"chipOpen\\s*=\\s*{tag}\\s*==\\s*false",
                     f"chipOpen = {tag} == true", fbody, count=1)
        if c4 != fbody:
            negated = c4
    if negated is None and m_else:
        c2 = re.sub(r"(else(?:\\s+if\\s*\\([^)]*\\))?\\s*\\{?\\s*)chipOpen\\s*=\\s*true",
                     r"\\1chipOpen = false", fbody, count=1)
        if c2 != fbody:
            negated = c2
    if negated is None:
        c3 = fbody.replace("chipOpen = true", "chipOpen = false", 1)
        if c3 != fbody:
            negated = c3
    if negated is None:
        return fixed, None
    return fixed, scr[:start] + negated + scr[start + 600:]


def _back_blob_and_fixed(s):
    """(fixed_screen, blobbed_screen). blobbed reproduces the shipped defect verbatim:
    a CircleShape clip on the 48dp touch layer (which slices the 44dp box corners into
    a blob) plus no Role.Button, so the node is not android.widget.Button."""
    scr = s["screen"]
    start = re.search(r"val backBtn.*?=\s*\{", scr)
    if not start:
        return None, None
    end = re.search(r"\n        val \w+", scr[start.end():])
    if not end:
        return None, None
    block = scr[start.end():start.end() + end.start()]
    bad = re.sub(r"Modifier\.size\(48\.dp\)", "Modifier.size(48.dp).clip(CircleShape)", block, count=1)
    if bad == block:
        return None, None
    return scr, scr[:start.end()] + bad + scr[start.end() + end.start():]


def _subbar_mutated(scr: str) -> str:
    """The shipped subbar: collapse pushed right of the spacer, settings on a bare IconButton."""
    m = re.search(r"(floorChip\(\)\n\s*fullscreenBtn\(\)\n\s*Spacer\(Modifier\.weight\(1f\)\))", scr)
    if not m:
        return scr
    return scr[:m.start()] + "floorChip()\n                Spacer(Modifier.weight(1f))\n                fullscreenBtn()" + scr[m.end():]


def mutate_for_selftest(name, s):
    m = dict(s)
    if name == 'icon':
        # the chevron is drawn (Canvas); regress it to a glyph and the vector contract dies
        m['screen'] = s['screen'].replace('Canvas(Modifier.size(18.dp)) {', 'Text(\"<\")', 1)
    elif name == 'type':
        i = s['screen'].find('val titleBlock')
        j = s['screen'].find('21.sp', i)
        m['screen'] = s['screen'][:j] + '17.sp' + s['screen'][j + 5:]
    elif name == 'chip':
        return m  # chip family uses _chip_fixed_and_mutated, not a blind mutation
    elif name == 'order':
        m['composer'] = s['composer'].replace('SegmentButton("Terminal"', 'SegmentButton("ZZZ-Terminal"', 1)
    elif name == 'subbar':
        m['screen'] = _subbar_mutated(s['screen'])
    elif name == 'sliders':
        m['screen'] = s['screen'].replace('SlidersIcon', 'Icons.Filled.Tune', 1)
    elif name == 'color':
        m['composer'] = s['composer'].replace('0xFF3D4150', '0xFF000000', 1)
    return m


def selftest() -> int:
    s = load(ROOT)
    failures = []
    # 1. baseline recorded (informational: may FAIL pre-integration; record it)
    rows, passed, total = run_gate(s)
    print(f"SELFTEST baseline: {passed}/{total} (recorded, not asserted)")
    # 2. each mutation family must produce at least one FAIL
    fams = {
        "icon": ["back-chevron-vector", "back-box44-touch48"],
        "type": ["title-center-21sp-600-sub11"],
        "chip": ["chip-circle-48", "chip-keysclosed-opens-panel"],
        "back": ["back-box44-touch48"],
        "subbar": ["subbar-terro-expand-spacer-settings"],
        "sliders": ["display-settings-box44-card2-sliders"],
        "order": ["switch-terminal-first"],
        "color": ["switch-track-10111A-a88", "switch-tokens-selected", "quickkeys-fill-ring-r10-h44", "composer-fill96-ring14-r26"],
    }
    for fam, names in fams.items():
        if fam == "back":
            # Two-sided auto-probe: the real form must PASS, the shipped blob must FAIL.
            fixed, blobbed = _back_blob_and_fixed(s)
            if fixed is None or blobbed is None:
                print("SELFTEST mutation[back]: FAIL (probe could not build fixed/blobbed pair — fail closed)")
                failures.append(fam)
                continue
            ok_fixed, _ = check_back_box_touch(dict(s, screen=fixed))
            broke = not check_back_box_touch(dict(s, screen=blobbed))[0]
            status = "PASS" if ok_fixed and broke else "FAIL"
            print(f"SELFTEST mutation[back]: {status} (fixed-form PASS={ok_fixed}, blob FAIL={broke})")
            if not (ok_fixed and broke):
                failures.append(fam)
            continue
        if fam == "chip":
            # Two-sided auto-open probe: a correct form must PASS, its negation FAIL.
            fixed, mutated = _chip_fixed_and_mutated(s)
            if fixed is None or mutated is None:
                print("SELFTEST mutation[chip]: FAIL (probe could not build fixed/negated pair — fail closed)")
                failures.append(fam)
                continue
            fs = dict(s, screen=fixed)
            ok_fixed, _ = check_chip_transition(fs)
            ms = dict(s, screen=mutated)
            mrows, _, _ = run_gate(ms)
            broke = [n for n, ok, _ in mrows if n == "chip-keysclosed-opens-panel" and not ok]
            status = "PASS" if ok_fixed and broke else "FAIL"
            print(f"SELFTEST mutation[chip]: {status} (fixed-form PASS={ok_fixed}, negated FAIL={bool(broke)})")
            if not (ok_fixed and broke):
                failures.append(fam)
            continue
        ms = mutate_for_selftest(fam, s)
        mrows, _, _ = run_gate(ms)
        hit = [n for n, ok, _ in mrows if n in names and not ok]
        status = "PASS" if hit else "FAIL"
        print(f"SELFTEST mutation[{fam}]: {status} (broke {hit if hit else 'NOTHING'}; want >=1 FAIL in {names})")
        if not hit:
            failures.append(fam)
    # 3. copy-in-temp proves disk untouched: run gate against a temp copy
    with tempfile.TemporaryDirectory() as td:
        for p in (TS, TC, TP, TOK, HTML):
            d = Path(td) / p.relative_to(ROOT)
            d.parent.mkdir(parents=True, exist_ok=True)
            if p.exists():
                shutil.copy(p, d)
        ts = load(Path(td))
        trows, tpassed, ttotal = run_gate(ts)
        same = [(n, ok) for n, ok, _ in trows] == [(n, ok) for n, ok, _ in rows]
        print(f"SELFTEST tempcopy: {'PASS' if same else 'FAIL'} ({tpassed}/{ttotal}, identical={same})")
        if not same:
            failures.append("tempcopy")
    if failures:
        print(f"SELFTEST RESULT: FAIL families={failures}")
        return 1
    print("SELFTEST RESULT: PASS (all mutations detected, tempcopy identical)")
    return 0


def check_css_reference(css_root: Path) -> int:
    css = css_root / "assets" / "maestri-remote.css"
    if not css.exists():
        print(f"CSSREF: {css} not found; skipping (gate must not depend on it)")
        return 0
    text = css.read_text()
    expects = {
        "tnav-title 600 21px": r"\.tnav-title\s*\{[^}]*600\s+21px",
        "tnav-bk 44px": r"\.tnav-bk\s*\{[^}]*44px",
        "termpill-btn circle 48": r"\.termpill-btn\s*\{[^}]*(48px|circle|border-radius\s*:\s*50%)",
        "qkk .08/.16 r10 h44": r"\.qkk\s*\{[^}]*169,\s*177,\s*214",
        "gseg track .88": r"\.gseg\s*\{[^}]*16,\s*17,\s*26",
        "gcomp-panel .96 r26": r"\.gcomp-panel\s*\{[^}]*16,\s*17,\s*26",
        "tchips gap14 pad14": r"\.tchips\s*\{[^}]*gap:14px[^}]*padding:4px 14px 12px 14px",
        "tchip-collapse 44 r-sm dim": r"\.tchip-collapse\s*\{[^}]*width:44px;height:44px[^}]*--r-sm[^}]*--dim",
        "tconf-btn 44 card-2 ring accent-hi": r"\.tconf-btn\s*\{[^}]*width:44px;height:44px[^}]*card-2[^}]*--ring[^}]*--accent-hi",
    }
    rc = 0
    for name, pat in expects.items():
        ok = bool(re.search(pat, text, re.S))
        print(f"CSSREF {'PASS' if ok else 'FAIL'} {name}")
        rc += 0 if ok else 1
    return 1 if rc else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Terminal fidelity implementation-contract gate")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--css-root", default=None)
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    if args.css_root:
        return check_css_reference(Path(args.css_root))
    s = load(ROOT)
    rows, passed, total = run_gate(s)
    for name, ok, detail in rows:
        print(f"{'PASS' if ok else 'FAIL'} {name} :: {detail}")
    print(f"TALLY {passed}/{total}")
    print("SCOPE: implementation contract only; NOT pixel equivalence (no tablet frame exists, v4 section 6).")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
