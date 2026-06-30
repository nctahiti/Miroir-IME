#!/usr/bin/env python3
"""
Export du dataset Miroir-IME au format parnasse-dataset.v1
Licence : ODbL

Usage :
  python3 export_dataset.py /chemin/vers/blocs/ --output dataset.jsonl
  python3 export_dataset.py /chemin/vers/blocs/ --output dataset/ --split

Format de sortie : JSON Lines (.jsonl) ou dossier de .json
"""

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path


VERSION = 1
CAPTURE_SOURCE = "onyx-boox-noteair5c"  # À adapter selon l'équipement
MODEL = "mlkit-digitalink-v1"


def normalize_strokes(strokes_data):
    """
    Normalise les strokes :
    - Translation : 1er point du 1er stroke = (0, 0, 0)
    - Remplace UUID par ID séquentiel
    - Timestamps en delta (ms depuis t0 du groupe)
    """
    if not strokes_data or not strokes_data[0].get("points"):
        return [], {"w": 0, "h": 0}

    # Trouver le point d'origine (1er point du 1er stroke)
    first_pt = strokes_data[0]["points"][0]
    origin_x = first_pt[0]
    origin_y = first_pt[1]
    origin_t = strokes_data[0].get("timestamps", [0])[0] if "timestamps" in strokes_data[0] else 0

    # Si les timestamps sont dans le stroke, les extraire
    has_timestamps = "timestamps" in strokes_data[0] if strokes_data else False
    has_pressures = "pressures" in strokes_data[0] if strokes_data else False

    min_x, min_y = float("inf"), float("inf")
    max_x, max_y = float("-inf"), float("-inf")

    normalized = []
    for i, sr in enumerate(strokes_data):
        pts = sr["points"]
        if len(pts) < 2:
            continue  # ignorer les strokes trop courts

        npts = []
        for j, pt in enumerate(pts):
            x = pt[0] - origin_x
            y = pt[1] - origin_y

            # Timestamp
            if has_timestamps and j < len(sr.get("timestamps", [])):
                t = int(sr["timestamps"][j] - origin_t)
            else:
                t = 0 if j == 0 else t + 16  # fallback ~60fps

            # Pression
            if has_pressures and j < len(sr.get("pressures", [])):
                p = round(sr["pressures"][j], 4)
            else:
                p = 1.0

            point = {"x": round(x, 1), "y": round(y, 1), "t": t, "p": p}

            # Features avancées (placeholder — à remplir depuis TouchHelper)
            point["tilt"] = 0.0
            point["orient"] = 0.0
            point["dist"] = 0.0
            point["z"] = 0.0

            npts.append(point)

            if x < min_x:
                min_x = x
            if y < min_y:
                min_y = y
            if x > max_x:
                max_x = x
            if y > max_y:
                max_y = y

        normalized.append({"id": i, "points": npts})

    bounds = {"w": round(max_x - min_x, 1), "h": round(max_y - min_y, 1)}
    return normalized, bounds


def canonical_json(strokes):
    """JSON canonique pour le hash (clés triées, pas d'espaces)."""
    return json.dumps(strokes, sort_keys=True, separators=(",", ":"))


def compute_dataset_id(label, original_label, strokes, model):
    """SHA-256(label + original_label + strokes canonique)."""
    payload = f"{model}\n{label}\n{original_label}\n{canonical_json(strokes)}"
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def process_page(page_dir, capture_source=CAPTURE_SOURCE, model=MODEL):
    """
    Lit un dossier de page (state.json + groups.json) et retourne
    une liste d'entrées dataset (une par groupe ayant un label).
    """
    state_file = page_dir / "state.json"
    if not state_file.exists():
        return []

    with open(state_file, "r", encoding="utf-8") as f:
        state = json.load(f)

    labels = state.get("labels", {})
    original_labels = state.get("originalLabels", {})
    anchors = state.get("anchors", {})
    strokes_data = state.get("strokes", [])

    # Indexer les strokes par inkId pour retrouver les groupes
    ink_to_stroke = {}
    for i, sr in enumerate(strokes_data):
        ink_id = sr.get("inkId")
        if ink_id is not None:
            ink_to_stroke[ink_id] = sr

    # Lire les groupes
    groups_file = page_dir / "groups.json"
    groups = []
    if groups_file.exists():
        with open(groups_file, "r", encoding="utf-8") as f:
            groups_data = json.load(f)
            groups = groups_data if isinstance(groups_data, list) else groups_data.get("groups", [])

    entries = []

    # Pour chaque groupe ayant un label
    for first_idx_str, label in labels.items():
        first_idx = int(first_idx_str)
        if not label or not label.strip():
            continue

        original_label = original_labels.get(first_idx_str, label)
        corrected = label != original_label

        # Trouver les strokes de ce groupe
        group_strokes = []
        group_ink_ids = set()

        for g in groups:
            if g.get("o") == first_idx:  # orderIndex == firstIdx (convention)
                group_ink_ids = set(g.get("s", []))
                break

        # Si pas trouvé dans groups.json, chercher le stroke par firstIdx
        if not group_ink_ids:
            for sr in strokes_data:
                if sr.get("inkId") == first_idx:
                    group_ink_ids = {first_idx}
                    break

        # Collecter les strokes du groupe
        for ink_id in sorted(group_ink_ids):
            sr = ink_to_stroke.get(ink_id)
            if sr and sr.get("points") and len(sr["points"]) >= 2:
                group_strokes.append(sr)

        if not group_strokes:
            continue

        # Normaliser
        norm_strokes, bounds = normalize_strokes(group_strokes)

        if not norm_strokes:
            continue

        # Construire l'entrée
        entry_strokes_for_id = []
        for s in norm_strokes:
            entry_strokes_for_id.append({"id": s["id"], "points": s["points"]})

        dataset_id = compute_dataset_id(label, original_label, entry_strokes_for_id, model)

        entry = {
            "version": VERSION,
            "dataset_id": dataset_id,
            "capture_source": capture_source,
            "model": model,
            "label": label,
            "original_label": original_label,
            "corrected": corrected,
            "strokes": entry_strokes_for_id,
            "bounds": bounds,
        }
        entries.append(entry)

    return entries


def export_blocks(blocks_dir, output_path, split=False, capture_source=CAPTURE_SOURCE, model=MODEL):
    """Exporte tous les blocs vers un fichier .jsonl ou un dossier."""
    blocks_path = Path(blocks_dir)
    if not blocks_path.exists():
        print(f"Erreur : {blocks_dir} introuvable", file=sys.stderr)
        sys.exit(1)

    all_entries = []
    stats = {"pages": 0, "entries": 0, "corrected": 0}

    # Parcourir les blocs
    for block_dir in sorted(blocks_path.iterdir()):
        if not block_dir.is_dir():
            continue
        # Parcourir les pages
        for page_dir in sorted(block_dir.iterdir()):
            if not page_dir.is_dir() or not page_dir.name.startswith("page_"):
                continue
            entries = process_page(page_dir, capture_source, model)
            stats["pages"] += 1
            stats["entries"] += len(entries)
            stats["corrected"] += sum(1 for e in entries if e["corrected"])
            all_entries.extend(entries)

    # Écriture
    if split:
        out_dir = Path(output_path)
        out_dir.mkdir(parents=True, exist_ok=True)
        for i, entry in enumerate(all_entries):
            fname = f"{entry['dataset_id'][:12]}.json"
            with open(out_dir / fname, "w", encoding="utf-8") as f:
                json.dump(entry, f, ensure_ascii=False, indent=2)
        print(f"→ {len(all_entries)} fichiers dans {out_dir}/")
    else:
        with open(output_path, "w", encoding="utf-8") as f:
            for entry in all_entries:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        print(f"→ {len(all_entries)} entrées dans {output_path}")

    print(f"📊 {stats['pages']} pages, {stats['entries']} entrées, "
          f"{stats['corrected']} corrigées ({stats['corrected']/max(1,stats['entries'])*100:.0f}%)")


def main():
    parser = argparse.ArgumentParser(description="Export dataset Miroir-IME")
    parser.add_argument("blocks_dir", help="Dossier contenant les blocs (cache/blocks/)")
    parser.add_argument("--output", "-o", default="dataset.jsonl", help="Fichier de sortie (.jsonl) ou dossier (avec --split)")
    parser.add_argument("--split", action="store_true", help="Écrire un fichier .json par entrée")
    parser.add_argument("--source", default=CAPTURE_SOURCE, help=f"Source de capture (défaut: {CAPTURE_SOURCE})")
    parser.add_argument("--model", default=MODEL, help=f"Modèle d'inférence (défaut: {MODEL})")
    args = parser.parse_args()

    export_blocks(args.blocks_dir, args.output, args.split, args.source, args.model)


if __name__ == "__main__":
    main()
