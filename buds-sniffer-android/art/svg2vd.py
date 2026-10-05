"""Converts art/ic_launcher.svg into the launcher foreground vector drawable.

Usage: python art/svg2vd.py [src.svg] [out.xml]
Expects a single <g transform="translate(cx cy) scale(s) translate(-px -py)"> holding plain <path> elements.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "art" / "ic_launcher.svg"
OUT = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml"
NS = "{http://www.w3.org/2000/svg}"

g = ET.parse(SRC).getroot().find(NS + "g")
cx, cy, s, px, py = map(float, re.findall(r"-?[\d.]+", g.get("transform")))
px, py = -px, -py


def num(v):
    return f"{v:g}"


out = ['<?xml version="1.0" encoding="utf-8"?>',
       f'<!-- Generated from art/{SRC.name} by art/svg2vd.py; edit the SVG, not this file. -->',
       '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
       '    android:width="108dp"',
       '    android:height="108dp"',
       '    android:viewportWidth="108"',
       '    android:viewportHeight="108">',
       '    <group',
       f'        android:pivotX="{num(px)}"',
       f'        android:pivotY="{num(py)}"',
       f'        android:scaleX="{num(s)}"',
       f'        android:scaleY="{num(s)}"',
       f'        android:translateX="{num(cx - px)}"',
       f'        android:translateY="{num(cy - py)}">']
for p in g.findall(NS + "path"):
    out.append('        <path')
    if p.get("fill") != "none":
        out.append(f'            android:fillColor="{p.get("fill")}"')
    if p.get("stroke"):
        out.append(f'            android:strokeColor="{p.get("stroke")}"')
        out.append(f'            android:strokeWidth="{p.get("stroke-width")}"')
    if p.get("stroke-linecap"):
        out.append(f'            android:strokeLineCap="{p.get("stroke-linecap")}"')
    out.append(f'            android:pathData="{p.get("d")}" />')
out += ['    </group>', '</vector>', '']
OUT.write_text("\n".join(out), newline="\n")
print(OUT)
