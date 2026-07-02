#!/usr/bin/env python3
"""Collecte tous les fichiers d'un test Miroir pour comparaison JSON vs V★."""

import subprocess, sys, json, struct, os
from datetime import datetime

ADB = ['adb', 'shell']
PKG = 'com.parnasse.miroir.v4'

def adb_text(cmd):
    """Récupère un fichier texte via adb shell run-as."""
    args = ' '.join(cmd)
    full_cmd = f'adb shell "run-as {PKG} {args}"'
    return subprocess.check_output(full_cmd, shell=True, text=True)

def adb_bin(cmd):
    """Récupère un fichier binaire via adb exec-out."""
    args = ' '.join(cmd)
    full_cmd = f'adb exec-out "run-as {PKG} {args}"'
    return subprocess.check_output(full_cmd, shell=True)

def find_latest_block():
    """Trouve le bloc le plus récent dans cache/blocks/."""
    blocks = adb_text(['ls', '-t', 'cache/blocks/']).strip().split('\n')
    for b in blocks:
        if b.startswith('com_') or b.startswith('org_'):
            return b
    return None

def find_latest_vstar():
    """Trouve le .vstar Writer le plus récent avec contenu (>200o)."""
    files = adb_text(['ls', '-lt', 'files/vstar/']).strip().split('\n')
    for line in files:
        parts = line.split()
        if len(parts) >= 5:
            name = parts[-1]
            size = int(parts[4])
            if name.endswith('.vstar') and size > 200:
                return name
    return None

def decode_vstar(binary):
    """Décode un flux V★ (×8) et retourne la liste des strokes."""
    marker = b'\n---\n'
    m = binary.find(marker)
    if m < 0:
        return [], {}
    bin_data = binary[m+len(marker):]
    
    strokes = []
    current_pts, current_ts, current_pr = [], [], []
    currentX, currentY, currentTime = 0.0, 0.0, 0
    anchors = {}
    
    for i in range(len(bin_data)//13):
        off = i*13
        dx = struct.unpack('>h', bin_data[off:off+2])[0]
        dy = struct.unpack('>h', bin_data[off+2:off+4])[0]
        dt = struct.unpack('>h', bin_data[off+4:off+6])[0]
        p = bin_data[off+6] & 0xFF
        ps = bin_data[off+9] & 0xFF
        sr = bin_data[off+11] & 0xFF
        
        # PS=1 (PENDOWN) : premier point = absolu, suivants = delta
        # PS=0 (PENUP)  : delta, puis fin de stroke
        if ps == 1:  # PENDOWN
            if not current_pts:
                # Premier point du stroke → absolu
                currentX = dx / 8.0
                currentY = dy / 8.0
            else:
                # Point intermédiaire → delta
                currentX += dx / 8.0
                currentY += dy / 8.0
            currentTime += dt
            current_pts.append((currentX, currentY))
            current_ts.append(currentTime)
            current_pr.append(p/255.0)
        elif ps == 0:  # PENUP
            currentX += dx / 8.0
            currentY += dy / 8.0
            currentTime += dt
            current_pts.append((currentX, currentY))
            current_ts.append(currentTime)
            current_pr.append(p/255.0)
            strokes.append({'pts': current_pts, 'ts': current_ts, 'pr': current_pr, 'stroke_idx': sr})
            current_pts, current_ts, current_pr = [], [], []
        elif ps == 3:  # END
            break
        elif ps == 4:  # GROUP_SEP
            pass
        elif ps == 5:  # ANCRE
            ax = dx / 8.0
            ay = dy / 8.0
            currentX, currentY = ax, ay
            currentTime = 0
            anchors[sr] = (ax, ay)
            if current_pts:
                strokes.append({'pts': current_pts, 'ts': current_ts, 'pr': current_pr, 'stroke_idx': sr})
                current_pts, current_ts, current_pr = [], [], []
        elif ps == 0xFF:
            break
    
    if current_pts:
        strokes.append({'pts': current_pts, 'ts': current_ts, 'pr': current_pr, 'stroke_idx': 0})
    
    return strokes, anchors

def compare(state_json, vstar_strokes):
    """Compare les strokes JSON et V★."""
    json_strokes = state_json.get('strokes', [])
    labels = state_json.get('labels', {})
    
    print(f"\n{'='*60}")
    print(f"COMPARAISON JSON vs V★")
    print(f"{'='*60}")
    print(f"JSON: {len(json_strokes)} strokes, {len(labels)} labels")
    print(f"V★:  {len(vstar_strokes)} strokes")
    
    for si in range(min(len(json_strokes), len(vstar_strokes))):
        jp = json_strokes[si]['points']
        vp = vstar_strokes[si]['pts']
        common = min(len(jp), len(vp))
        
        errs = [abs(jp[k][0]-vp[k][0]) + abs(jp[k][1]-vp[k][1]) for k in range(0, common, 10)]
        avg_err = sum(errs)/len(errs) if errs else 0
        
        status = "✅" if avg_err < 1.0 else ("⚠️" if avg_err < 5.0 else "❌")
        print(f"  {status} Stroke {si}: {len(jp)}/{len(vp)} pts, "
              f"JSON 1er=({jp[0][0]:.1f},{jp[0][1]:.1f}), "
              f"V★ 1er=({vp[0][0]:.1f},{vp[0][1]:.1f}), "
              f"err_moy={avg_err:.2f}px")
    
    # Vérifier les ancres/labels
    group_anchors = state_json.get('groupAnchors', state_json.get('anchors', {}))
    if group_anchors:
        print(f"\nANCRES JSON: {len(group_anchors)}")
        for k, v in list(group_anchors.items())[:5]:
            print(f"  idx={k}: ({v[0]:.1f}, {v[1]:.1f})")

def main():
    print("🔍 Collecte des fichiers Miroir...")
    
    # 1. Bloc le plus récent
    block = find_latest_block()
    if not block:
        print("❌ Aucun bloc trouvé")
        return
    
    print(f"📁 Bloc: {block}")
    
    # 2. Récupérer state.json
    state_raw = adb_text(['cat', f'cache/blocks/{block}/page_0/state.json'])
    state = json.loads(state_raw)
    
    # 3. Récupérer groups.json
    try:
        groups_raw = adb_text(['cat', f'cache/blocks/{block}/page_0/groups.json'])
        groups = json.loads(groups_raw)
        print(f"📄 groups.json: {len(groups)} groupes")
    except:
        groups = {}
        print("📄 groups.json: absent")
    
    # 4. Récupérer page.vstar (Encoder batch)
    try:
        pv_raw = adb_bin(['cat', f'cache/blocks/{block}/page_0/page.vstar'])
        print(f"📄 page.vstar (Encoder): {len(pv_raw)} octets")
        if len(pv_raw) > 200:
            pv_strokes, _ = decode_vstar(pv_raw)
            print(f"   → {len(pv_strokes)} strokes décodés")
    except:
        print("📄 page.vstar (Encoder): absent")
    
    # 5. Récupérer le .vstar Writer le plus récent
    vstar_name = find_latest_vstar()
    if vstar_name:
        vstar_raw = adb_bin(['cat', f'files/vstar/{vstar_name}'])
        print(f"📄 Writer .vstar: {vstar_name} ({len(vstar_raw)} octets)")
        vstar_strokes, vstar_anchors = decode_vstar(vstar_raw)
    else:
        print("❌ Aucun .vstar Writer trouvé")
        return
    
    # 6. Comparaison
    compare(state, vstar_strokes)
    
    # 7. Sauvegarde locale pour analyse
    out_dir = f"mirror_test_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
    os.makedirs(out_dir, exist_ok=True)
    with open(f"{out_dir}/state.json", 'w') as f:
        json.dump(state, f, indent=2)
    with open(f"{out_dir}/vstar.bin", 'wb') as f:
        f.write(vstar_raw)
    with open(f"{out_dir}/groups.json", 'w') as f:
        json.dump(groups, f, indent=2)
    print(f"\n💾 Fichiers sauvegardés dans: {out_dir}/")

if __name__ == '__main__':
    main()
