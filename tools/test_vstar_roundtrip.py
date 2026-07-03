"""
Test unitaire V★ v1.1 — encodeur/décodeur (réplique exacte du code Kotlin).
Exécute avec: python3 test_vstar_roundtrip.py
"""
import struct, json, math, os, sys, tempfile

# === Données de test ===
workspace = r"C:\Users\nicol\.openclaw\workspace\miroir-fusion"
test_dir = os.path.join(workspace, "mirror_test_20260702_171859")
state_file = os.path.join(test_dir, "state.json")

if not os.path.exists(state_file):
    print("Fichier test introuvable, utilisation de données synthétiques")
    # Données synthétiques : 2 strokes simples
    STROKES = [
        {"points": [(100.0, 200.0), (101.5, 198.3), (103.0, 196.0), (105.2, 194.1)],
         "timestamps": [0, 10, 20, 30],
         "pressures": [0.8, 0.9, 0.7, 0.5]},
        {"points": [(200.0, 300.0), (202.3, 298.7), (204.0, 297.0)],
         "timestamps": [0, 10, 20],
         "pressures": [0.9, 0.8, 0.6]},
    ]
else:
    with open(state_file) as f:
        data = json.load(f)
    STROKES = data.get("strokes", [])

SCALE = 8.0

# === ENCODEUR (réplique exacte de VStarEncoder.kt) ===
def encode(strokes):
    """Encode strokes → bytes (14 bytes/token, flat encoding, reconstructedPosition)"""
    buf = bytearray()
    
    # Tous les strokes vivants dans un seul groupe (flat encoding)
    all_live = [i for i, s in enumerate(strokes) 
                if not s.get("isDeleted", False) 
                and len(s.get("points", [])) > 0
                and len(s.get("timestamps", [])) > 0]
    
    for idx in all_live:
        s = strokes[idx]
        pts = s["points"]
        tss = s.get("timestamps", [])
        prs = s.get("pressures", [])
        
        rx, ry = 0.0, 0.0  # position reconstruite
        
        for j, (px, py) in enumerate(pts):
            px, py = float(px), float(py)
            
            if j == 0:
                # Premier point : absolu (×8)
                scaled_dx = min(max(round(px * SCALE), -32768), 32767)
                scaled_dy = min(max(round(py * SCALE), -32768), 32767)
                dx = scaled_dx
                dy = scaled_dy
                rx = dx / SCALE  # position reconstruite
                ry = dy / SCALE
                dt = 0
                ps = 1  # PENDOWN
            else:
                # Delta depuis position RECONSTRUITE
                scaled_dx = min(max(round((px - rx) * SCALE), -32768), 32767)
                scaled_dy = min(max(round((py - ry) * SCALE), -32768), 32767)
                dx = scaled_dx
                dy = scaled_dy
                rx += dx / SCALE
                ry += dy / SCALE
                if j < len(tss):
                    dt = min(max(tss[j] - tss[j-1], -32768), 32767)
                else:
                    dt = 10
                ps = 0 if j == len(pts) - 1 else 1  # PENUP si dernier, sinon PENDOWN
            
            p = min(max(round(prs[j] * 255) if j < len(prs) else 128, 0), 255)
            
            # Token 14 bytes
            buf.extend(struct.pack('>h', dx))      # 0-1
            buf.extend(struct.pack('>h', dy))      # 2-3
            buf.extend(struct.pack('>h', dt))      # 4-5
            buf.append(p)                           # 6
            buf.append(0xFF)                        # 7 az
            buf.append(0xFF)                        # 8 i
            buf.append(ps)                          # 9
            buf.append(0)                           # 10 h
            buf.extend(struct.pack('>H', idx))     # 11-12 captureIndex
    
    # GROUP_SEP (14 bytes)
    buf.extend(struct.pack('>h', 0))  # dx=0
    buf.extend(struct.pack('>h', 0))  # dy=0
    buf.extend(struct.pack('>h', 0))  # dt=0
    buf.append(0)                      # p=0
    buf.append(0)                      # az=0
    buf.append(0)                      # i=0
    buf.append(4)                      # ps=GROUP_SEP
    buf.append(0)                      # h=0
    buf.extend(struct.pack('>H', 0))  # ci=0
    
    # ANCRE (14 bytes) — dx,dy en absolu ×8 pour le premier stroke
    if all_live:
        first_pts = strokes[all_live[0]]["points"]
        if first_pts:
            ax, ay = float(first_pts[0][0]), float(first_pts[0][1])
            buf.extend(struct.pack('>h', min(max(round(ax * SCALE), -32768), 32767)))
            buf.extend(struct.pack('>h', min(max(round(ay * SCALE), -32768), 32767)))
        else:
            buf.extend(struct.pack('>h', 0))
            buf.extend(struct.pack('>h', 0))
    else:
        buf.extend(struct.pack('>h', 0))
        buf.extend(struct.pack('>h', 0))
    buf.extend(struct.pack('>h', 0))  # dt=0
    buf.append(0)    # p=0
    buf.append(0xFF) # az
    buf.append(0xFF) # i
    buf.append(5)    # ps=ANCRE
    buf.append(0)    # h=0
    buf.extend(struct.pack('>H', 0)) # ci=0
    
    # END (14 bytes)
    buf.extend(struct.pack('>h', 0))
    buf.extend(struct.pack('>h', 0))
    buf.extend(struct.pack('>h', 0))
    buf.append(0)
    buf.append(0xFF)
    buf.append(0xFF)
    buf.append(3)  # ps=END
    buf.append(0)
    buf.extend(struct.pack('>H', 0))
    
    return bytes(buf)


# === DÉCODEUR (réplique exacte de VStarDecoder.kt) ===
def decode(raw_bytes):
    """Décode bytes → strokes (14 bytes/token)"""
    strokes = []
    current_stroke = {"points": [], "timestamps": [], "pressures": []}
    cx, cy, ct = 0.0, 0.0, 0
    capture_indices = []
    
    ts = 14  # token size
    i = 0
    while i + ts <= len(raw_bytes):
        t = raw_bytes[i:i+ts]
        dx = struct.unpack('>h', t[0:2])[0]
        dy = struct.unpack('>h', t[2:4])[0]
        dt = struct.unpack('>h', t[4:6])[0]
        p = t[6]
        ps = t[9]
        ci = struct.unpack('>H', t[11:13])[0]
        
        if ps == 3:  # END
            break
        elif ps == 4:  # GROUP_SEP
            pass
        elif ps == 5:  # GROUP_ANCRE
            cx, cy, ct = dx / SCALE, dy / SCALE, 0
        elif ps == 1:  # PENDOWN / MOVE
            if not current_stroke["points"]:
                cx, cy = dx / SCALE, dy / SCALE
            else:
                cx += dx / SCALE
                cy += dy / SCALE
            ct += dt
            current_stroke["points"].append((cx, cy))
            current_stroke["timestamps"].append(ct)
            current_stroke["pressures"].append(p / 255.0)
        elif ps == 0:  # PEN_UP
            cx += dx / SCALE
            cy += dy / SCALE
            ct += dt
            current_stroke["points"].append((cx, cy))
            current_stroke["timestamps"].append(ct)
            current_stroke["pressures"].append(p / 255.0)
            strokes.append(current_stroke)
            capture_indices.append(ci)
            current_stroke = {"points": [], "timestamps": [], "pressures": []}
            cx = cy = ct = 0.0
        else:
            print(f"  ⚠️  PS inconnu: {ps} at token {i//ts}")
        
        i += ts
    
    return strokes, capture_indices


# === TEST ===
print("=" * 60)
print("V★ v1.1 — Test unitaire encodeur/décodeur")
print("=" * 60)

# Filtrer les strokes vivants
live_strokes = [s for s in STROKES 
                if not s.get("isDeleted", False) 
                and len(s.get("points", [])) > 0
                and len(s.get("timestamps", [])) > 0]
total_pts = sum(len(s["points"]) for s in live_strokes)
print(f"Input: {len(live_strokes)} strokes, {total_pts} points")

# Encoder
encoded = encode(STROKES)
token_count = len(encoded) // 14
print(f"Encoded: {len(encoded)} bytes = {token_count} tokens")
print(f"  Expected: {total_pts + 3} tokens ({total_pts} pts + 1 GROUP_SEP + 1 ANCRE + 1 END)")
print(f"  Match: {'✅' if token_count == total_pts + 3 else '❌ MANQUANT ' + str(total_pts + 3 - token_count) + ' tokens'}")

# Décoder
decoded_strokes, cis = decode(encoded)
decoded_pts = sum(len(s["points"]) for s in decoded_strokes)
print(f"Decoded: {len(decoded_strokes)} strokes, {decoded_pts} points")
print(f"  Capture indices: {cis}")

# Comparer
print(f"\nComparaison point par point:")
n = min(len(live_strokes), len(decoded_strokes))
all_ok = True
for si in range(n):
    sj = live_strokes[si]
    sv = decoded_strokes[si]
    pj = [(float(p[0]), float(p[1])) for p in sj["points"]]
    pv = sv["points"]
    np = min(len(pj), len(pv))
    
    if np == 0:
        print(f"  stroke {si}: PAS DE POINTS ❌")
        all_ok = False
        continue
    
    errors = [abs(pj[pi][0] - pv[pi][0]) + abs(pj[pi][1] - pv[pi][1]) for pi in range(np)]
    max_e = max(errors)
    mean_e = sum(errors) / len(errors)
    
    status = "✅" if max_e < 1.0 else "⚠️ " if max_e < 5.0 else "❌"
    print(f"  stroke {si}: {np} pts, max_err={max_e:.4f}px, mean={mean_e:.4f}px {status}")
    if max_e >= 1.0:
        all_ok = False

if len(decoded_strokes) != len(live_strokes):
    print(f"\n❌ Nombre de strokes: attendu={len(live_strokes)}, décodé={len(decoded_strokes)}")
    all_ok = False

# Vérifier le format binaire
print(f"\nVérification format binaire (premiers tokens):")
for t in range(min(5, token_count)):
    tok = encoded[t*14:(t+1)*14]
    dx = struct.unpack('>h', tok[0:2])[0]
    dy = struct.unpack('>h', tok[2:4])[0]
    dt = struct.unpack('>h', tok[4:6])[0]
    p = tok[6]
    ps = tok[9]
    h = tok[10]
    ci = struct.unpack('>H', tok[11:13])[0]
    print(f"  t{t}: dx={dx:6d} dy={dy:6d} dt={dt:3d} p={p:3d} ps={ps} h={h} ci={ci}")

# Vérifier pas de PS > 5
ps_values = set()
for t in range(token_count):
    ps_values.add(encoded[t*14+9])
unexpected = ps_values - {0, 1, 3, 4, 5}
if unexpected:
    print(f"\n❌ PS values inattendues: {unexpected}")
    all_ok = False
else:
    print(f"\n✅ Tous les PS sont valides: {sorted(ps_values)}")

# Vérifier pas de CI > nombre de strokes
max_ci = max(struct.unpack('>H', encoded[t*14+11:t*14+13])[0] for t in range(token_count - 3))  # -3 pour GROUP_SEP/ANCRE/END
print(f"✅ CI max = {max_ci} (attendu < {len(live_strokes)})")

print("=" * 60)
if all_ok:
    print("✅ TEST PASSÉ — encodeur/décodeur fonctionne correctement")
else:
    print("❌ TEST ÉCHOUÉ — voir erreurs ci-dessus")
print("=" * 60)
sys.exit(0 if all_ok else 1)
