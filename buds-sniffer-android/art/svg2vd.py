"""Converts art/ic_launcher.svg (CorelDRAW export) into the adaptive launcher icon drawables and the README logo.

Usage: python art/svg2vd.py [src.svg]
Uses the variant drawn on the 10800x10800 canvas: a background <rect> followed by a <g> of class-filled paths,
optionally wrapped in clip-path groups. Off-canvas variants are ignored.
Monochrome = the first (body) path with light details inside it cut out, plus light details outside it kept solid.
README logo (docs/images/logo.png) = the launcher-visible zone (inner 72 of 108dp) with rounded corners.
Requires shapely and Pillow (pip install shapely pillow).
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageChops, ImageColor, ImageDraw
from shapely.geometry import Polygon
from shapely.ops import unary_union

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "art" / "ic_launcher.svg"
RES = ROOT / "app/src/main/res/drawable"
LOGO = ROOT.parent / "docs/images/logo.png"
NS = "{http://www.w3.org/2000/svg}"
SIZE = 10800
SAFE = SIZE // 6
LOGO_PX, SUPERSAMPLE = 512, 4

root = ET.parse(SRC).getroot()
classes = dict(re.findall(r"\.(\w+)\s*\{fill:([^}]+)\}", root.find(f"{NS}defs/{NS}style").text))
clips = {c.get("id"): c[0] for c in root.iter(NS + "clipPath")}
NAMED = {"black": "#000000", "white": "#FFFFFF", "none": None}


def fill(el):
    v = classes.get(el.get("class"), "none").strip()
    return NAMED.get(v, v)


def clip_d(el):
    m = re.search(r"url\(#(\w+)\)", el.get("style", ""))
    if not m:
        return None
    c = clips[m.group(1)]
    if c.tag == NS + "rect":
        x, y, w, h = (float(c.get(k)) for k in ("x", "y", "width", "height"))
        return f"M{x:g} {y:g}h{w:g}v{h:g}h{-w:g}z"
    return c.get("d")


def subpaths(d):
    """Parses path data into subpaths of absolute cubic segments [p0, c1, c2, p3]; lines become degenerate cubics."""
    toks = re.findall(r"[A-Za-z]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", d)
    arity = {"m": 2, "l": 2, "h": 1, "v": 1, "c": 6, "z": 0}
    out, cur, x, y, sx, sy, cmd, i = [], None, 0.0, 0.0, 0.0, 0.0, "M", 0
    while i < len(toks):
        if toks[i].isalpha():
            cmd = toks[i]
            i += 1
            if cmd.lower() not in arity:
                raise ValueError(f"unsupported path command {cmd!r}")
            if cmd in "zZ":
                if (x, y) != (sx, sy):
                    cur.append([(x, y), (x, y), (sx, sy), (sx, sy)])
                x, y = sx, sy
                continue
        a = [float(t) for t in toks[i:i + arity[cmd.lower()]]]
        i += len(a)
        ox, oy = (x, y) if cmd.islower() else (0.0, 0.0)
        if cmd in "mM":
            x, y = sx, sy = ox + a[0], oy + a[1]
            cur = []
            out.append(cur)
            cmd = "l" if cmd == "m" else "L"
            continue
        if cmd in "hH":
            p3 = (ox + a[0], y)
        elif cmd in "vV":
            p3 = (x, oy + a[0])
        else:
            p3 = (ox + a[-2], oy + a[-1])
        c1, c2 = ((ox + a[0], oy + a[1]), (ox + a[2], oy + a[3])) if cmd in "cC" else ((x, y), p3)
        cur.append([(x, y), c1, c2, p3])
        x, y = p3
    return out


def to_d(subs):
    return " ".join("M{:g} {:g} ".format(*s[0][0]) + " ".join(
        "C{:g} {:g} {:g} {:g} {:g} {:g}".format(*c1, *c2, *p3) for _, c1, c2, p3 in s) + " Z" for s in subs)


def bbox(d):
    pts = [p for s in subpaths(d) for seg in s for p in seg]
    return min(p[0] for p in pts), min(p[1] for p in pts), max(p[0] for p in pts), max(p[1] for p in pts)


def flatten(sub, steps=24):
    pts = []
    for p0, c1, c2, p3 in sub:
        for k in range(steps):
            t = k / steps
            u = 1 - t
            pts.append(tuple(u ** 3 * p0[j] + 3 * u * u * t * c1[j] + 3 * u * t * t * c2[j] + t ** 3 * p3[j]
                             for j in range(2)))
    return pts


def ring_d(coords):
    return "M" + " L".join(f"{x:.1f} {y:.1f}" for x, y in list(coords)[:-1]) + " Z"


def merge_overlaps(subs):
    """Overlapping holes would refill each other under evenOdd, so each overlapping group is replaced by its
    flattened union; holes that touch nothing keep their curves."""
    polys = [Polygon(flatten(s)).buffer(0) for s in subs]
    group = list(range(len(subs)))
    find = lambda i: i if group[i] == i else find(group[i])
    for i in range(len(polys)):
        for j in range(i + 1, len(polys)):
            if polys[i].intersects(polys[j]):
                group[find(j)] = find(i)
    groups = {}
    for i in range(len(subs)):
        groups.setdefault(find(i), []).append(i)
    out = []
    for members in groups.values():
        if len(members) == 1:
            out.append(to_d([subs[members[0]]]))
            continue
        # Corel draws shared edges with slightly different curves; grow-union-shrink closes the hairline gaps.
        merged = unary_union([polys[i].buffer(1) for i in members]).buffer(-1).simplify(0.5)
        for poly in getattr(merged, "geoms", [merged]):
            out += [ring_d(poly.exterior.coords)] + [ring_d(r.coords) for r in poly.interiors]
    return " ".join(out)


def inside(a, b):
    return a[0] >= b[0] and a[1] >= b[1] and a[2] <= b[2] and a[3] <= b[3]


layer = root.find(NS + "g")
kids = list(layer)
bg_i = next(i for i, e in enumerate(kids)
            if e.tag == NS + "rect" and abs(float(e.get("x"))) < 1 and float(e.get("width")) == SIZE)
bg, art = kids[bg_i], kids[bg_i + 1]


def header(comment=None):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           f'<!-- Generated from art/{SRC.name} by art/svg2vd.py; edit the SVG, not this file. -->']
    if comment:
        out.append(f"<!-- {comment} -->")
    return out + ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
                  '    android:width="108dp"',
                  '    android:height="108dp"',
                  f'    android:viewportWidth="{SIZE}"',
                  f'    android:viewportHeight="{SIZE}">']


def path(out, color, d, indent, even_odd=False):
    out.append(f"{indent}<path")
    out.append(f'{indent}    android:fillColor="{color}"')
    if even_odd:
        out.append(f'{indent}    android:fillType="evenOdd"')
    out.append(f'{indent}    android:pathData="{d}" />')


def emit(out, el, indent):
    for c in el:
        if c.tag == NS + "path" and fill(c):
            path(out, fill(c), c.get("d"), indent)
        elif c.tag == NS + "g":
            cd = clip_d(c)
            if cd:
                out.append(f"{indent}<group>")
                out.append(f'{indent}    <clip-path android:pathData="{cd}" />')
                emit(out, c, indent + "    ")
                out.append(f"{indent}</group>")
            else:
                emit(out, c, indent)


def write(name, lines):
    f = RES / name
    f.write_text("\n".join(lines + ["</vector>", ""]), newline="\n")
    print(f)


out = header()
path(out, fill(bg), f"M0,0h{SIZE}v{SIZE}h-{SIZE}z", "    ")
write("ic_launcher_background.xml", out)

out = header()
emit(out, art, "    ")
write("ic_launcher_foreground.xml", out)

# Light accents (oranges, white highlight) become holes; dark ones (nose) merge into the body.
# Relies on the light accents abutting rather than overlapping, otherwise evenOdd would refill them.
paths = [p for p in art if p.tag == NS + "path" and fill(p)]
body, details = paths[0], paths[1:]
body_box = bbox(body.get("d"))
holes, solid = [], []
for p in details:
    if int(fill(p)[1:3], 16) >= 0xC0:
        (holes if inside(bbox(p.get("d")), body_box) else solid).append(p.get("d"))
holes = [s for d in holes for s in subpaths(d)]
out = header("Themed icons use alpha only, so the inner ear, chest, brow and eye are cut out with evenOdd.")
path(out, "#FFFFFFFF", body.get("d") + " " + merge_overlaps(holes), "    ", even_odd=True)
for d in solid:
    path(out, "#FFFFFFFF", d, "    ")
write("ic_launcher_monochrome.xml", out)

px = LOGO_PX * SUPERSAMPLE
scale = px / (SIZE - 2 * SAFE)


def mask(d):
    """evenOdd fill (the SVG's root fill-rule): XOR of the subpath polygons."""
    m = Image.new("L", (px, px))
    for s in subpaths(d):
        layer = Image.new("L", (px, px))
        ImageDraw.Draw(layer).polygon([((x - SAFE) * scale, (y - SAFE) * scale) for x, y in flatten(s)], fill=255)
        m = ImageChops.difference(m, layer)
    return m


def rasterize(img, el, clip=None):
    for c in el:
        if c.tag == NS + "path" and fill(c):
            m = mask(c.get("d"))
            img.paste(ImageColor.getrgb(fill(c)), mask=ImageChops.multiply(m, clip) if clip else m)
        elif c.tag == NS + "g":
            cd = clip_d(c)
            sub = mask(cd) if cd else None
            rasterize(img, c, ImageChops.multiply(sub, clip) if sub and clip else sub or clip)


img = Image.new("RGBA", (px, px), ImageColor.getrgb(fill(bg)))
rasterize(img, art)
corners = Image.new("L", (px, px))
ImageDraw.Draw(corners).rounded_rectangle((0, 0, px - 1, px - 1), radius=px * 0.29, fill=255)
img.putalpha(corners)
img.resize((LOGO_PX, LOGO_PX), Image.LANCZOS).save(LOGO, optimize=True)
print(LOGO)
