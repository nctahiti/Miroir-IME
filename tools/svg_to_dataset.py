#!/usr/bin/env python3
"""
Convertisseur SVG augmenté → parnasse-dataset.v1

Parse les SVG générés depuis les données brutes du Miroir.
Chaque SVG contient des paths avec data-pressure, data-times, data-seq.

Usage :
  python3 svg_to_dataset.py C:/Nicolas/svg/ -o svg_export.jsonl
"""

import argparse
import hashlib
import json
import re
import sys
import os
from pathlib import Path
from xml.etree import ElementTree as ET

VERSION = 1
CAPTURE_SOURCE = "onyx-boox-noteair5c"
MODEL = "human"  # annotations manuelles, pas d'inférence ML


def parse_svg_paths(svg_path):
    """
    Parse un fichier SVG augmenté et retourne les strokes.
    Chaque stroke = {points: [{x,y}], pressures: [float], times: [int]}
    """
    try:
        tree = ET.parse(svg_path)
        root = tree.getroot()
    except Exception as e:
        print(f"  ⚠ {svg_path}: parse error: {e}", file=sys.stderr)
        return None, None

    # Métadonnées du groupe
    ns = {"svg": "http://www.w3.org/2000/svg"}
    group = root.find(".//svg:g", ns) or root.find(".//g")
    if group is None:
        print(f"  ⚠ {svg_path}: pas de <g>", file=sys.stderr)
        return None, None

    meta = {
        "label": group.get("label", ""),
        "category": group.get("category", ""),
        "device": group.get("device", ""),
        "n_strokes": int(group.get("n_strokes", 0)),
        "source": group.get("source", ""),
    }

    # Extraire les paths (strokes)
    paths = group.findall(".//svg:path", ns) or group.findall(".//path")
    strokes = []
    total_points = 0

    for path_elem in paths:
        d = path_elem.get("d", "")
        pressures_str = path_elem.get("data-pressure", "")
        times_str = path_elem.get("data-times", "")
        seq_str = path_elem.get("data-seq", "")

        if not d:
            continue

        # Parser les coordonnées du path "M x y L x y L x y..."
        coords = re.findall(r"[ML]\s*([\d.]+)\s+([\d.]+)", d, re.IGNORECASE)
        if not coords:
            continue

        points = [{"x": float(x), "y": float(y)} for x, y in coords]

        # Pressions
        pressures = []
        if pressures_str:
            pressures = [float(p) for p in pressures_str.split(",") if p.strip()]

        # Timestamps (absolus, en nanosecondes)
        times = []
        if times_str:
            times = [int(t) for t in times_str.split(",") if t.strip()]

        if len(points) < 2:
            continue

        strokes.append({
            "points": points,
            "pressures": pressures,
            "times": times,
        })
        total_points += len(points)

    meta["n_points"] = total_points
    return meta, strokes


def normalize_strokes(strokes):
    """
    Normalise : translation du 1er point du 1er stroke à (0,0,0).
    Retourne les strokes normalisés et les bounds.
    """
    if not strokes or not strokes[0]["points"]:
        return [], {"w": 0, "h": 0}

    first_pt = strokes[0]["points"][0]
    origin_x = first_pt["x"]
    origin_y = first_pt["y"]
    origin_t = strokes[0]["times"][0] if strokes[0]["times"] else 0

    min_x = float("inf")
    min_y = float("inf")
    max_x = float("-inf")
    max_y = float("-inf")

    normalized = []
    for stroke_idx, sr in enumerate(strokes):
        pts = sr["points"]
        pressures = sr.get("pressures", [])
        times = sr.get("times", [])
        npts = []

        for j, pt in enumerate(pts):
            x = pt["x"] - origin_x
            y = pt["y"] - origin_y

            # Timestamp en delta (ms depuis t0)
            if j < len(times):
                t = int((times[j] - origin_t) / 1_000_000)  # ns → ms
            else:
                t = 0 if j == 0 else npts[-1]["t"] + 16

            # Pression
            if j < len(pressures):
                p = round(pressures[j], 4)
            else:
                p = 1.0

            point = {
                "x": round(x, 1),
                "y": round(y, 1),
                "t": t,
                "p": p,
                "tilt": 0.0,
                "orient": 0.0,
                "dist": 0.0,
                "z": 0.0,
            }
            npts.append(point)

            if x < min_x:
                min_x = x
            if y < min_y:
                min_y = y
            if x > max_x:
                max_x = x
            if y > max_y:
                max_y = y

        normalized.append({"id": stroke_idx, "points": npts})

    bounds = {"w": round(max_x - min_x, 1), "h": round(max_y - min_y, 1)}
    return normalized, bounds


def canonical_json(strokes):
    return json.dumps(strokes, sort_keys=True, separators=(",", ":"))


def compute_dataset_id(label, strokes, model="human"):
    payload = f"{model}\n{label}\n{label}\n{canonical_json(strokes)}"
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def process_svg(svg_path, model="human", capture_source=CAPTURE_SOURCE):
    """Parse un SVG et retourne une entrée dataset, ou None."""
    meta, strokes_data = parse_svg_paths(svg_path)
    if meta is None or not strokes_data:
        return None

    label = meta["label"].strip()
    if not label:
        return None

    norm_strokes, bounds = normalize_strokes(strokes_data)
    if not norm_strokes:
        return None

    entry_strokes = []
    for s in norm_strokes:
        entry_strokes.append({"id": s["id"], "points": s["points"]})

    dataset_id = compute_dataset_id(label, entry_strokes, model)

    entry = {
        "version": VERSION,
        "dataset_id": dataset_id,
        "capture_source": capture_source,
        "model": model,
        "label": label,
        "original_label": label,     # annotation manuelle → label = original_label
        "corrected": False,          # pas de correction ML Kit
        "strokes": entry_strokes,
        "bounds": bounds,
    }
    return entry


def export_svgs(svg_dir, output_path, split=False, model="human", capture_source=CAPTURE_SOURCE):
    """Exporte tous les SVG d'un dossier vers le format dataset."""
    svg_path = Path(svg_dir)
    if not svg_path.exists():
        print(f"Erreur : {svg_dir} introuvable", file=sys.stderr)
        sys.exit(1)

    svg_files = sorted(svg_path.rglob("*.svg"))
    print(f"📁 {len(svg_files)} fichiers SVG trouvés")

    all_entries = []
    skipped = 0
    stats = {"categories": {}}

    for svg_file in svg_files:
        entry = process_svg(svg_file, model, capture_source)
        if entry is None:
            skipped += 1
            continue
        all_entries.append(entry)
        cat = svg_file.parent.name
        stats["categories"][cat] = stats["categories"].get(cat, 0) + 1

    # Écriture
    if split:
        out_dir = Path(output_path)
        out_dir.mkdir(parents=True, exist_ok=True)
        for entry in all_entries:
            fname = f"{entry['dataset_id'][:12]}.json"
            with open(out_dir / fname, "w", encoding="utf-8") as f:
                json.dump(entry, f, ensure_ascii=False, indent=2)
        print(f"→ {len(all_entries)} fichiers dans {out_dir}/")
    else:
        with open(output_path, "w", encoding="utf-8") as f:
            for entry in all_entries:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        print(f"→ {len(all_entries)} entrées dans {output_path}")

    print(f"\n📊 {len(all_entries)} entrées, {skipped} ignorées")
    print("   Catégories :")
    for cat, count in sorted(stats["categories"].items()):
        print(f"     {cat}: {count}")


def main():
    parser = argparse.ArgumentParser(description="Convertir SVG augmentés → parnasse-dataset.v1")
    parser.add_argument("svg_dir", help="Dossier racine des SVG")
    parser.add_argument("--output", "-o", default="svg_export.jsonl", help="Fichier de sortie")
    parser.add_argument("--split", action="store_true", help="Un fichier .json par entrée")
    parser.add_argument("--model", default=MODEL, help=f"Modèle/annotateur (défaut: {MODEL})")
    parser.add_argument("--source", default=CAPTURE_SOURCE, help="Source de capture")
    args = parser.parse_args()

    export_svgs(args.svg_dir, args.output, args.split, args.model, args.source)


if __name__ == "__main__":
    main()
