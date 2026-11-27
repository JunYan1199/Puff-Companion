"""Offline DQS skin mesh optimizer & binary packer for Puff Companion.

Reads high-poly *.skin.json from D:\\ysm\\geckolib-base-body\\geckolib_assets\\geo\\entity\\
(read-only, never modifies sources), performs topology-preserving structured quad-grid
coarsening on outfits/ears, compacts unused vertices/UVs, sorts bone weights descending,
and writes compact *.skin.bin files to:
  D:\\老鼠\\puff_companion\\src\\main\\resources\\assets\\puffcompanion\\skins\\entity\\
"""

from __future__ import annotations

import glob
import json
import os
import struct
from collections import defaultdict, deque

SRC_DIR = r"D:\ysm\geckolib-base-body\geckolib_assets\geo\entity"
DST_DIR = r"D:\老鼠\puff_companion\src\main\resources\assets\puffcompanion\skins\entity"

TARGET_NAMES = [
    "mouse_girl_body",
    "mouse_girl_vroid_head",
    "mouse_girl_chiffon_hair",
    "mouse_girl_ears",
    "mouse_girl_bikini",
    "mouse_girl_adventure",
    "mouse_girl_artisan",
    "mouse_girl_starlight",
    "mouse_girl_wedding",
]

# Keep head, hair, and body at full resolution; coarsen outfits and ears.
COARSEN_NAMES = {
    "mouse_girl_ears",
    "mouse_girl_bikini",
    "mouse_girl_adventure",
    "mouse_girl_artisan",
    "mouse_girl_starlight",
    "mouse_girl_wedding",
}


def _sample_non_periodic(n_quads: int, stride: int = 2) -> list[int]:
    """Sample grid vertex indices [0..n_quads] preserving both endpoints."""
    if n_quads < 4:
        return list(range(n_quads + 1))
    pts = list(range(0, n_quads + 1, stride))
    if pts[-1] != n_quads:
        pts.append(n_quads)
    return pts


def _sample_periodic(n_quads: int, stride: int = 2) -> list[int]:
    """Sample periodic grid vertex indices [0..n_quads-1] (wraps around)."""
    if n_quads < 6:
        return list(range(n_quads))
    if n_quads % stride == 0:
        return list(range(0, n_quads, stride))
    if n_quads >= 9:
        return list(range(0, n_quads - 1, stride))
    return list(range(n_quads))


def _try_coarsen_component(comp_q_indices, quads, quad_uvs, uvs):
    """Attempt to unfold a pure-quad connected component into a regular (R, C) grid
    and coarsen it by stride 2 along eligible axes.
    Returns (new_quad_list, new_quad_uv_list, success_bool).
    """
    if len(comp_q_indices) < 16:
        return None, None, False

    # Build directed half-edge map: (v_a, v_b) -> list of (q_idx, edge_pos_in_q)
    # Also build undirected edge adjacency with UV continuity check.
    edge_map = defaultdict(list)
    for qi in comp_q_indices:
        base = qi * 4
        vs = quads[base : base + 4]
        for k in range(4):
            a, b = vs[k], vs[(k + 1) % 4]
            key = (a, b) if a < b else (b, a)
            edge_map[key].append((qi, k))

    # Each quad has 4 edges. For each edge, find valid neighbor (non-manifold -> abort)
    adj = {}  # (qi, k) -> (nj, nk)
    for key, entries in edge_map.items():
        if len(entries) > 2:
            return None, None, False
        if len(entries) == 2:
            (q1, k1), (q2, k2) = entries
            b1, b2 = q1 * 4, q2 * 4
            # Check opposite winding on shared edge AND UV continuity
            v1a, v1b = quads[b1 + k1], quads[b1 + (k1 + 1) % 4]
            v2a, v2b = quads[b2 + k2], quads[b2 + (k2 + 1) % 4]
            if not (v1a == v2b and v1b == v2a):
                return None, None, False
            u1a, u1b = quad_uvs[b1 + k1], quad_uvs[b1 + (k1 + 1) % 4]
            u2a, u2b = quad_uvs[b2 + k2], quad_uvs[b2 + (k2 + 1) % 4]
            if (
                abs(uvs[u1a * 2] - uvs[u2b * 2]) > 1e-4
                or abs(uvs[u1a * 2 + 1] - uvs[u2b * 2 + 1]) > 1e-4
                or abs(uvs[u1b * 2] - uvs[u2a * 2]) > 1e-4
                or abs(uvs[u1b * 2 + 1] - uvs[u2a * 2 + 1]) > 1e-4
            ):
                continue
            adj[(q1, k1)] = (q2, k2)
            adj[(q2, k2)] = (q1, k1)

    # BFS assign (r, c) coordinates to each quad and its 4 corners
    # Local corner offsets for a quad at (qr, qc):
    # corner 0: (qr, qc), corner 1: (qr+1, qc), corner 2: (qr+1, qc+1), corner 3: (qr, qc+1)
    # Wait: neighbors must match rotation! Track each quad's orientation `rot` (0..3)
    # where corner `(k + rot) % 4` maps to local (0,0), (1,0), (1,1), (0,1).
    # Even simpler: assign (r, c) to corners of q0 directly, then propagate via shared edge!
    q_grid = {}  # (qr, qc) -> (qi, corners_rc_order)
    visited_q = {}  # qi -> (qr, qc, rot)
    # Edge k goes from corner k to (k+1)%4.
    # In canonical quad (rot=0):
    #   c0=(0,0), c1=(1,0), c2=(1,1), c3=(0,1)
    #   edge 0: c0->c1 (+1, 0), outward normal step to neighbor = (0, -1)
    #   edge 1: c1->c2 (0, +1), step to neighbor = (+1, 0)
    #   edge 2: c2->c3 (-1, 0), step to neighbor = (0, +1)
    #   edge 3: c3->c0 (0, -1), step to neighbor = (-1, 0)
    EDGE_STEP = [(0, -1), (1, 0), (0, 1), (-1, 0)]

    q0 = comp_q_indices[0]
    visited_q[q0] = (0, 0, 0)
    q_grid[(0, 0)] = q0
    dq = deque([q0])

    while dq:
        qi = dq.popleft()
        qr, qc, rot = visited_q[qi]
        for k in range(4):
            nb = adj.get((qi, k))
            if nb is None:
                continue
            nj, nk = nb
            canon_e = (k - rot) % 4
            dr, dc = EDGE_STEP[canon_e]
            n_qr, n_qc = qr + dr, qc + dc
            # Neighbor's edge nk must be opposite to canon_e in canonical space: (canon_e + 2) % 4
            n_rot = (nk - (canon_e + 2)) % 4
            if nj in visited_q:
                continue
            if (n_qr, n_qc) in q_grid:
                return None, None, False
            visited_q[nj] = (n_qr, n_qc, n_rot)
            q_grid[(n_qr, n_qc)] = nj
            dq.append(nj)

    if len(visited_q) != len(comp_q_indices):
        return None, None, False

    min_r = min(r for r, _ in q_grid)
    max_r = max(r for r, _ in q_grid)
    min_c = min(c for _, c in q_grid)
    max_c = max(c for _, c in q_grid)
    R = max_r - min_r + 1
    C = max_c - min_c + 1
    if R * C != len(comp_q_indices):
        return None, None, False

    # Build (R+1, C+1) vertex & uv grid
    v_grid = [[None] * (C + 1) for _ in range(R + 1)]
    u_grid = [[None] * (C + 1) for _ in range(R + 1)]
    CANON_OFF = [(0, 0), (1, 0), (1, 1), (0, 1)]

    for (qr, qc), qi in q_grid.items():
        _, _, rot = visited_q[qi]
        rr, cc = qr - min_r, qc - min_c
        base = qi * 4
        for canon_c, (dr, dc) in enumerate(CANON_OFF):
            idx_in_q = (canon_c + rot) % 4
            v = quads[base + idx_in_q]
            uv = quad_uvs[base + idx_in_q]
            vr, vc = rr + dr, cc + dc
            if v_grid[vr][vc] is None:
                v_grid[vr][vc] = v
                u_grid[vr][vc] = uv
            elif v_grid[vr][vc] != v or u_grid[vr][vc] != uv:
                return None, None, False

    # Check periodicity along R and C
    r_periodic = R >= 3 and all(v_grid[0][c] == v_grid[R][c] for c in range(C + 1))
    c_periodic = C >= 3 and all(v_grid[r][0] == v_grid[r][C] for r in range(R + 1))

    # Decide sampling
    r_pts = _sample_periodic(R, 2) if r_periodic else _sample_non_periodic(R, 2)
    c_pts = _sample_periodic(C, 2) if c_periodic else _sample_non_periodic(C, 2)

    # Extra pass for ultra-dense sheets (e.g. 60x80 skirts/veils)
    nr = len(r_pts) if r_periodic else len(r_pts) - 1
    nc = len(c_pts) if c_periodic else len(c_pts) - 1
    if R * C >= 2800 and nr * nc >= 900:
        if nr >= nc and nr >= 8:
            if r_periodic:
                r_pts = r_pts[::2] if len(r_pts) % 2 == 0 else r_pts
            else:
                sub = _sample_non_periodic(len(r_pts) - 1, 2)
                r_pts = [r_pts[i] for i in sub]
        elif nc >= 8:
            if c_periodic:
                c_pts = c_pts[::2] if len(c_pts) % 2 == 0 else c_pts
            else:
                sub = _sample_non_periodic(len(c_pts) - 1, 2)
                c_pts = [c_pts[i] for i in sub]

    # Reconstruct quads with exact original winding
    new_q = []
    new_quv = []
    r_count = len(r_pts) if r_periodic else len(r_pts) - 1
    c_count = len(c_pts) if c_periodic else len(c_pts) - 1
    if r_count == R and c_count == C:
        return None, None, False

    for i in range(r_count):
        r0 = r_pts[i]
        r1 = r_pts[(i + 1) % len(r_pts)] if r_periodic else r_pts[i + 1]
        for j in range(c_count):
            c0 = c_pts[j]
            c1 = c_pts[(j + 1) % len(c_pts)] if c_periodic else c_pts[j + 1]
            # Canonical order: (r0,c0), (r1,c0), (r1,c1), (r0,c1)
            new_q.extend([v_grid[r0][c0], v_grid[r1][c0], v_grid[r1][c1], v_grid[r0][c1]])
            new_quv.extend([u_grid[r0][c0], u_grid[r1][c0], u_grid[r1][c1], u_grid[r0][c1]])

    return new_q, new_quv, True


def coarsen_mesh(data: dict) -> dict:
    quads = data["quads"]
    quad_uvs = data["quad_uvs"]
    uvs = data["uvs"]
    nq = len(quads) // 4

    pure_indices = []
    out_q = []
    out_quv = []
    for qi in range(nq):
        b = qi * 4
        vs = quads[b : b + 4]
        if len(set(vs)) == 4:
            pure_indices.append(qi)
        else:
            out_q.extend(vs)
            out_quv.extend(quad_uvs[b : b + 4])

    # Connected components among pure quads (by shared vertex)
    v_to_q = defaultdict(list)
    for qi in pure_indices:
        b = qi * 4
        for v in quads[b : b + 4]:
            v_to_q[v].append(qi)

    seen = set()
    coarsened_comps = 0
    total_comps = 0
    for start_q in pure_indices:
        if start_q in seen:
            continue
        total_comps += 1
        comp = []
        dq = deque([start_q])
        seen.add(start_q)
        while dq:
            cur = dq.popleft()
            comp.append(cur)
            b = cur * 4
            for v in quads[b : b + 4]:
                for nxt in v_to_q[v]:
                    if nxt not in seen:
                        seen.add(nxt)
                        dq.append(nxt)

        cq, cquv, ok = _try_coarsen_component(comp, quads, quad_uvs, uvs)
        if ok:
            coarsened_comps += 1
            out_q.extend(cq)
            out_quv.extend(cquv)
        else:
            for qi in comp:
                b = qi * 4
                out_q.extend(quads[b : b + 4])
                out_quv.extend(quad_uvs[b : b + 4])

    out = dict(data)
    out["quads"] = out_q
    out["quad_uvs"] = out_quv
    return out


def compact_and_sort_weights(data: dict) -> dict:
    """Remove unreferenced vertices/UVs and sort each vertex's 4 bone weights descending."""
    quads = data["quads"]
    quad_uvs = data["quad_uvs"]
    verts = data["vertices"]
    norms = data["normals"]
    uvs = data["uvs"]
    b_idx = data["bone_indices"]
    b_w = data["bone_weights"]

    used_v = sorted(set(quads))
    v_remap = {old: new for new, old in enumerate(used_v)}
    used_u = sorted(set(quad_uvs))
    u_remap = {old: new for new, old in enumerate(used_u)}

    new_verts = []
    new_norms = []
    new_b_idx = []
    new_b_w = []
    for ov in used_v:
        vi = ov * 3
        new_verts.extend(verts[vi : vi + 3])
        new_norms.extend(norms[vi : vi + 3])
        bi = ov * 4
        pairs = sorted(
            [(b_w[bi + k], b_idx[bi + k]) for k in range(4)],
            key=lambda x: -x[0],
        )
        s = sum(w for w, _ in pairs)
        if s > 1e-8:
            pairs = [(w / s, idx) for w, idx in pairs]
        for w, idx in pairs:
            new_b_w.append(w)
            new_b_idx.append(idx)

    new_uvs = []
    for ou in used_u:
        ui = ou * 2
        new_uvs.extend(uvs[ui : ui + 2])

    new_quads = [v_remap[v] for v in quads]
    new_quad_uvs = [u_remap[u] for u in quad_uvs]

    return {
        "bones": data["bones"],
        "vertices": new_verts,
        "normals": new_norms,
        "uvs": new_uvs,
        "quads": new_quads,
        "quad_uvs": new_quad_uvs,
        "bone_indices": new_b_idx,
        "bone_weights": new_b_w,
    }


def write_skin_bin(path: str, data: dict) -> None:
    bones = data["bones"]
    verts = data["vertices"]
    norms = data["normals"]
    uvs = data["uvs"]
    quads = data["quads"]
    quad_uvs = data["quad_uvs"]
    b_idx = data["bone_indices"]
    b_w = data["bone_weights"]

    nv = len(verts) // 3
    nu = len(uvs) // 2
    nq = len(quads) // 4
    assert len(bones) <= 255, "boneCount > 255 not supported by uint8 bone_indices"

    with open(path, "wb") as f:
        f.write(b"PSKN")
        f.write(struct.pack(">I", 1))
        f.write(struct.pack(">I", len(bones)))
        for b in bones:
            enc = b.encode("utf-8")
            f.write(struct.pack(">H", len(enc)))
            f.write(enc)
        f.write(struct.pack(">III", nv, nu, nq))
        f.write(struct.pack(f">{len(verts)}f", *verts))
        f.write(struct.pack(f">{len(norms)}f", *norms))
        f.write(struct.pack(f">{len(uvs)}f", *uvs))
        f.write(struct.pack(f">{len(quads)}i", *quads))
        f.write(struct.pack(f">{len(quad_uvs)}i", *quad_uvs))
        f.write(bytes(b_idx))
        f.write(struct.pack(f">{len(b_w)}f", *b_w))


def main() -> None:
    os.makedirs(DST_DIR, exist_ok=True)
    tot_v0 = tot_q0 = tot_v1 = tot_q1 = 0
    tot_mb0 = tot_mb1 = 0.0

    print(f"{'Skin':28s} | {'Orig Q':>7s} -> {'Opt Q':>7s} ({'Drop':>5s}) | {'Orig MB':>7s} -> {'Bin MB':>6s}")
    print("-" * 82)
    for name in TARGET_NAMES:
        src = os.path.join(SRC_DIR, f"{name}.skin.json")
        dst = os.path.join(DST_DIR, f"{name}.skin.bin")
        with open(src, "r", encoding="utf-8") as f:
            raw = json.load(f)

        v0 = len(raw["vertices"]) // 3
        q0 = len(raw["quads"]) // 4
        mb0 = os.path.getsize(src) / 1024 / 1024

        proc = coarsen_mesh(raw) if name in COARSEN_NAMES else raw
        proc = compact_and_sort_weights(proc)
        write_skin_bin(dst, proc)

        v1 = len(proc["vertices"]) // 3
        q1 = len(proc["quads"]) // 4
        mb1 = os.path.getsize(dst) / 1024 / 1024
        drop = (1.0 - q1 / max(1, q0)) * 100.0

        tot_v0 += v0; tot_q0 += q0; tot_mb0 += mb0
        tot_v1 += v1; tot_q1 += q1; tot_mb1 += mb1
        print(f"{name:28s} | {q0:7d} -> {q1:7d} ({drop:4.1f}%) | {mb0:6.2f}M -> {mb1:5.2f}M")

    drop_tot = (1.0 - tot_q1 / max(1, tot_q0)) * 100.0
    print("-" * 82)
    print(f"{'TOTAL':28s} | {tot_q0:7d} -> {tot_q1:7d} ({drop_tot:4.1f}%) | {tot_mb0:6.2f}M -> {tot_mb1:5.2f}M")


if __name__ == "__main__":
    main()
