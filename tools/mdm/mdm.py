"""
mdm.py — MarkDownMiroir — Moteur de rendu
==========================================
Syntaxe MDM :
  <* *>   justifier       |* *|   centrer        >* *>   droite
  (* *)   groupe          [* *]   cadre           @mot    dessin
  ;*      colonne

Usage : from mdm import compile; svg = compile(src, ["strokes.json"])
"""

import re, json, math
from dataclasses import dataclass, field
from typing import List, Dict

@dataclass
class Node:
    kind: str = ""
    attrs: dict = field(default_factory=dict)
    kids: list = field(default_factory=list)
    text: str = ""

# ═══════ PARSEUR ═══════

_TOKEN = re.compile(
    r'<(\*)\s|\*>|'      # <*  or  *>   (justify)
    r'>(\*)\s|\*>|'      # >*  or  *>   (right)
    r'\|(\*)\s|\*\||'    # |*  or  *|   (center)
    r'\((\*)\s|\*\)|'    # (*  or  *)   (group)
    r'\[(\*)\s|\*\]|'    # [*  or  *]   (frame)
    r';(\*)|'            # ;*           (col sep)
    r'@(\w+)|'           # @word
    r'(\S+)'             # text
)

CLOSE_MAP = {'*>': ('justify', 'right'), '*|': ('center',),
             '*)': ('group',), '*]': ('frame',)}

def _pop_until(stack, kinds):
    for i in range(len(stack)-1, -1, -1):
        if stack[i].kind in kinds:
            stack[:] = stack[:i]; return

def parse(src: str) -> Node:
    # Pre-process: wrap ;* cells in (* *) groups
    lines = src.split('\n')
    processed = []
    for line in lines:
        s = line.strip()
        if ';*' in s:
            cells = [c.strip() for c in s.split(';*') if c.strip()]
            # Add a leading ;* so first cell also triggers row creation
            processed.append(';* ' + ' ;* '.join(f'(* {c} *)' for c in cells))
        else:
            processed.append(line)
    src = '\n'.join(processed)

    root = Node(kind="root")
    stack = [root]
    buf = []

    def flush():
        nonlocal buf
        if buf:
            stack[-1].kids.append(Node(kind="text", text=' '.join(buf)))
            buf.clear()

    i = 0
    while i < len(src):
        if src[i] == '\n':
            flush()
            while stack[-1].kind == 'row': stack.pop()
            i += 1; continue

        m = _TOKEN.match(src, i)
        if not m: i += 1; continue
        i = m.end()

        # ;* → colonne
        if m.group(0) == ';*':
            flush()
            row = None
            for p in reversed(stack):
                if p.kind == 'row': row = p; break
            if not row:
                row = Node(kind="row")
                stack[-1].kids.append(row)
            if stack[-1].kind == 'group': stack.pop()
            stack.append(row)
            continue

        # Balises ouvrantes: groups 1(<*) 2(>*) 3(|*) 4((*) 5([*)
        for g_idx, kind in [(1,'justify'),(2,'right'),(3,'center'),(4,'group'),(5,'frame')]:
            if m.group(g_idx) is not None:
                flush()
                node = Node(kind=kind)
                stack[-1].kids.append(node); stack.append(node)
                break
        else:
            closed = False
            for token, kinds in CLOSE_MAP.items():
                if m.group(0) == token:
                    flush(); _pop_until(stack, kinds); closed = True; break
            if closed: continue

            if m.group(7) is not None:  # @word
                flush(); stack[-1].kids.append(Node(kind="word", text=m.group(7)))
            elif m.group(8) is not None:  # text
                buf.append(m.group(8))

    flush()
    while stack[-1].kind == 'row': stack.pop()
    return root

# ═══════ STROKES ═══════

class StrokeDB:
    def __init__(self, paths=None):
        self._s = {}
        for p in (paths or []):
            with open(p) as f: d = json.load(f)
            self._s[d['text']] = [(x[0], x[1], int(x[2])) for x in d['strokes']]
    def get(self, w): return self._s.get(w, [])
    def bbox(self, w):
        s = self.get(w)
        return (min(x[0] for x in s), min(x[1] for x in s),
                max(x[0] for x in s), max(x[1] for x in s)) if s else (0,0,60,30)

# ═══════ RENDU ═══════

class Engine:
    def __init__(self, strokes=None):
        self.db = StrokeDB(strokes)
        self.W = 800; self.I = 24; self._out = []

    def render(self, root: Node) -> str:
        self._out = [f'<rect width="100%" height="100%" fill="#fafaf8"/>']
        h = self._layout(root, 20, 20, self.W - 40)
        vh = max(600, int(h + 40))
        return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.W} {vh}">\n  ' + '\n  '.join(self._out) + '\n</svg>'

    def _layout(self, node: Node, x: float, y: float, w: float) -> float:
        I = self.I
        if node.kind in ('root', 'group'):
            cy = y
            for kid in node.kids: cy = self._layout(kid, x, cy, w)
            return cy
        if node.kind == 'row': return self._row(node, x, y, w)
        if node.kind == 'justify': return self._flow(node, x, y, w, 'stretch')
        if node.kind == 'right': return self._flow(node, x, y, w, 'end')
        if node.kind == 'center': return self._flow(node, x, y, w, 'center')
        if node.kind == 'frame': return self._frame(node, x, y, w)
        if node.kind == 'word': return self._word(node, x, y)
        if node.kind == 'text': return self._text(node, x, y, w, 'start')
        return y + I

    def _row(self, node: Node, x: float, y: float, w: float) -> float:
        cells = [k for k in node.kids if k.kind == 'group']
        if not cells: return y + self.I
        n = len(cells); gap = 8; cw = (w - (n-1)*gap) / n
        # Measure
        max_h = self.I
        for cell in cells:
            h = self._measure(cell, cw) - 0
            max_h = max(max_h, h)
        # Render
        cx = x
        for cell in cells:
            self._layout(cell, cx, y, cw)
            cx += cw + gap
        return y + max_h

    def _measure(self, node: Node, w: float) -> float:
        saved = self._out; self._out = []
        h = self._layout(node, 0, 0, w)
        self._out = saved; return h

    def _flow(self, node: Node, x: float, y: float, w: float, align: str) -> float:
        cy = y
        for kid in node.kids:
            if kid.kind == 'text':   cy = self._text(kid, x, cy, w, align)
            elif kid.kind == 'word': cy = self._word(kid, x, cy)
            elif kid.kind == 'frame': cy = self._frame(kid, x, cy, w)
            else:                     cy = self._layout(kid, x, cy, w)
        return cy

    def _frame(self, node: Node, x: float, y: float, w: float) -> float:
        fill = node.attrs.get('fill', 'none')
        stroke = node.attrs.get('stroke', '#ccc')
        r = node.attrs.get('round', '0')
        rx = f' rx="{r}" ry="{r}"' if r != '0' else ''
        m = 10; iw = w - 2*m
        # Measure content height
        fh = (self._measure(Node(kind="group", kids=node.kids), iw) - 0) + m
        self._out.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{fh:.1f}" fill="{fill}" stroke="{stroke}" stroke-width="1"{rx}/>')
        cy = y + m
        for kid in node.kids: cy = self._layout(kid, x + m, cy, iw)
        return y + fh

    def _word(self, node: Node, x: float, y: float) -> float:
        word = node.text; strokes = self.db.get(word); I = self.I
        if not strokes:
            self._out.append(f'<text x="{x:.1f}" y="{y+I*0.7:.1f}" font-size="14" fill="#999">{word}</text>')
            return y + I
        xs = [s[0] for s in strokes]; ys = [s[1] for s in strokes]
        min_x, min_y = min(xs), min(ys); max_y = max(ys)
        scale = I * 0.7 / max(max_y - min_y, 1)
        paths = []; cur = []
        for sx, sy, pen in strokes:
            nx = (sx - min_x) * scale + x; ny = (sy - min_y) * scale + y + I*0.15
            if pen > 0 and cur:
                if len(cur) >= 2: paths.append(cur)
                cur = []
            else: cur.append((nx, ny))
        if len(cur) >= 2: paths.append(cur)
        for path in paths:
            d = "M " + " L ".join(f"{px:.1f} {py:.1f}" for px, py in path)
            self._out.append(f'<path d="{d}" fill="none" stroke="#222" stroke-width="1.0" stroke-linecap="round" stroke-linejoin="round"/>')
        return y + I

    def _text(self, node: Node, x: float, y: float, w: float, align: str) -> float:
        text = node.text
        if not text: return y + self.I
        I = self.I; cw = 6.5
        words = text.split(); lines = []; cur = []; cur_w = 0
        for wd in words:
            ww = len(wd)*cw + cw
            if cur and cur_w + ww > w - 10: lines.append(' '.join(cur)); cur = [wd]; cur_w = ww
            else: cur.append(wd); cur_w += ww
        if cur: lines.append(' '.join(cur))
        for i, line in enumerate(lines):
            if align == 'center':   tx = x + (w - len(line)*cw)/2
            elif align == 'end':    tx = x + w - len(line)*cw - 4
            elif align == 'stretch':
                lw = line.split()
                if len(lw) > 1 and i < len(lines)-1:
                    tc = sum(len(w) for w in lw); sp = (w-10-tc*cw)/(len(lw)-1)
                    cx = x
                    for wd in lw:
                        self._out.append(f'<text x="{cx:.1f}" y="{y+(i+1)*I*0.8:.1f}" font-family="sans-serif" font-size="{I*0.6:.0f}" fill="#333">{wd}</text>')
                        cx += len(wd)*cw + sp
                    continue
                tx = x
            else: tx = x
            self._out.append(f'<text x="{tx:.1f}" y="{y+(i+1)*I*0.8:.1f}" font-family="sans-serif" font-size="{I*0.6:.0f}" fill="#333">{line}</text>')
        return y + len(lines)*I

# ═══════ API ═══════

def compile(src: str, stroke_paths=None) -> str:
    return Engine(stroke_paths).render(parse(src))

# ═══════ DÉMOS ═══════

if __name__ == '__main__':
    paths = ["strokes_mer.json", "strokes_bonjour.json"]

    svg = compile("""|* Produit *| ;* |* Quantité *| ;* |* Prix *|
<* @bonjour *> ;* |* 3 *| ;* >* 12.5€ *>
<* @capitaine *> ;* |* 1 *| ;* >* 47.0€ *>
""", paths)
    with open('mdm_tableau.svg', 'w') as f: f.write(svg)
    print("mdm_tableau.svg")

    svg = compile("""<*
  Lorem ipsum dolor sit amet consectetur adipiscing
  elit sed do eiusmod tempor incididunt ut labore
  et dolore magna aliqua ut enim ad minim veniam
*> ;*
|*
  [* fill=#f5f0eb stroke=#cba round=6 *]
    |* @bonjour *|
  [* *]
*|
""", paths)
    with open('mdm_texte_cadre.svg', 'w') as f: f.write(svg)
    print("mdm_texte_cadre.svg")

    print("MDM — prêt.")
