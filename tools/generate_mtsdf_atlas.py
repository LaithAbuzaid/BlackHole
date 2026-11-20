#!/usr/bin/env python3
"""
Generates a 3-channel MSDF + true-SDF alpha (RGBA8 MTSDF) glyph atlas for
JetBrains Mono Medium covering printable ASCII (32..126) + degree (°) + bullet (•).

Output:
  src/main/resources/fonts/jetbrains_mono_mtsdf.bin
    16-byte big-endian header:
      uint32 magic   = 0x4D545344 ('MTSD')
      uint16 atlasW
      uint16 atlasH
      uint16 cols
      uint16 rows
      uint16 cellW
      uint16 cellH
      float32 pxRange  (half-span in atlas texels; full [0..1] span = 2 * pxRange)
      float32 capTopNorm    (cap-line Y from cell top, in [0..1])
      float32 capHeightNorm (cap height / cellH)
      float32 advNorm       (font advance / cellW)
    followed by zlib-compressed raw RGBA8 atlas pixels (row-major, top-to-bottom).
"""

import math
import os
import struct
import sys
import zlib
import numpy as np
from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import DecomposingRecordingPen

FONT_PATH = "/usr/share/fonts/TTF/JetBrainsMonoNLNerdFontMono-Medium.ttf"
OUT_PATH = "src/main/resources/fonts/jetbrains_mono_mtsdf.bin"

COLS = 16
ROWS = 7
CELL_W = 36
CELL_H = 56
PX_RANGE = 4.0  # Half-range in atlas texels (full [0..1] span = 8.0 texels)
CURVE_SUBDIV = 10  # Quadratic Bezier polyline subdivision per curve segment
CORNER_SIN_THRESH = math.sin(math.radians(3.0))

GLYPH_CODES = list(range(32, 127)) + [0x00B0, 0x2022]  # 97 glyphs


def extract_contours(glyph_set, glyph_name):
    """Extracts closed contours as lists of smooth edges; each edge is a list of (A, B) segments."""
    pen = DecomposingRecordingPen(glyph_set)
    glyph_set[glyph_name].draw(pen)

    # First collect raw TrueType primitives per closed contour
    raw_contours = []
    cur_contour = []
    cur_pt = None
    start_pt = None

    for op, args in pen.value:
        if op == "moveTo":
            if cur_contour:
                raw_contours.append(cur_contour)
                cur_contour = []
            cur_pt = args[0]
            start_pt = cur_pt
        elif op == "lineTo":
            p1 = args[0]
            if cur_pt is not None and p1 != cur_pt:
                cur_contour.append(("line", cur_pt, p1))
                cur_pt = p1
        elif op == "qCurveTo":
            # TrueType quadratic spline with implied midpoints between consecutive off-curve points
            pts = list(args)
            if pts[-1] is None:
                # All-off-curve closed contour
                n = len(pts) - 1
                off = pts[:-1]
                start = ((off[-1][0] + off[0][0]) * 0.5, (off[-1][1] + off[0][1]) * 0.5)
                cur_pt = start
                start_pt = start
                for i in range(n):
                    c = off[i]
                    nxt = off[(i + 1) % n]
                    end = ((c[0] + nxt[0]) * 0.5, (c[1] + nxt[1]) * 0.5)
                    cur_contour.append(("quad", cur_pt, c, end))
                    cur_pt = end
            else:
                off_pts = pts[:-1]
                end_pt = pts[-1]
                if not off_pts:
                    if cur_pt != end_pt:
                        cur_contour.append(("line", cur_pt, end_pt))
                        cur_pt = end_pt
                else:
                    for i, c in enumerate(off_pts):
                        if i < len(off_pts) - 1:
                            c_next = off_pts[i + 1]
                            mid = ((c[0] + c_next[0]) * 0.5, (c[1] + c_next[1]) * 0.5)
                        else:
                            mid = end_pt
                        cur_contour.append(("quad", cur_pt, c, mid))
                        cur_pt = mid
        elif op == "curveTo":
            p0 = cur_pt
            p1, p2, p3 = args
            cur_contour.append(("cubic", p0, p1, p2, p3))
            cur_pt = p3
        elif op == "closePath":
            if cur_pt is not None and start_pt is not None and cur_pt != start_pt:
                cur_contour.append(("line", cur_pt, start_pt))
            if cur_contour:
                raw_contours.append(cur_contour)
                cur_contour = []
            cur_pt = None
            start_pt = None

    if cur_contour:
        raw_contours.append(cur_contour)

    return raw_contours


def prim_tangents(prim):
    """Returns (start_tangent, end_tangent) unit vectors for a contour primitive."""
    kind = prim[0]
    if kind == "line":
        dx = prim[2][0] - prim[1][0]
        dy = prim[2][1] - prim[1][1]
        l = math.hypot(dx, dy)
        t = (dx / l, dy / l) if l > 1e-9 else (1.0, 0.0)
        return t, t
    elif kind == "quad":
        p0, p1, p2 = prim[1], prim[2], prim[3]
        d0 = (p1[0] - p0[0], p1[1] - p0[1])
        if math.hypot(*d0) < 1e-9:
            d0 = (p2[0] - p0[0], p2[1] - p0[1])
        d1 = (p2[0] - p1[0], p2[1] - p1[1])
        if math.hypot(*d1) < 1e-9:
            d1 = (p2[0] - p0[0], p2[1] - p0[1])
        l0 = max(1e-9, math.hypot(*d0))
        l1 = max(1e-9, math.hypot(*d1))
        return (d0[0] / l0, d0[1] / l0), (d1[0] / l1, d1[1] / l1)
    else:  # cubic
        p0, p1, p2, p3 = prim[1], prim[2], prim[3], prim[4]
        d0 = (p1[0] - p0[0], p1[1] - p0[1])
        if math.hypot(*d0) < 1e-9:
            d0 = (p2[0] - p0[0], p2[1] - p0[1])
        d1 = (p3[0] - p2[0], p3[1] - p2[1])
        if math.hypot(*d1) < 1e-9:
            d1 = (p3[0] - p1[0], p3[1] - p1[1])
        l0 = max(1e-9, math.hypot(*d0))
        l1 = max(1e-9, math.hypot(*d1))
        return (d0[0] / l0, d0[1] / l0), (d1[0] / l1, d1[1] / l1)


def flatten_prim(prim):
    """Converts a primitive to a list of directed line segments (A, B)."""
    kind = prim[0]
    if kind == "line":
        return [(prim[1], prim[2])]
    elif kind == "quad":
        p0, p1, p2 = prim[1], prim[2], prim[3]
        segs = []
        prev = p0
        for i in range(1, CURVE_SUBDIV + 1):
            t = i / CURVE_SUBDIV
            mt = 1.0 - t
            x = mt * mt * p0[0] + 2.0 * mt * t * p1[0] + t * t * p2[0]
            y = mt * mt * p0[1] + 2.0 * mt * t * p1[1] + t * t * p2[1]
            cur = (x, y)
            if math.hypot(cur[0] - prev[0], cur[1] - prev[1]) > 1e-6:
                segs.append((prev, cur))
                prev = cur
        return segs
    else:
        p0, p1, p2, p3 = prim[1], prim[2], prim[3], prim[4]
        segs = []
        prev = p0
        for i in range(1, CURVE_SUBDIV + 1):
            t = i / CURVE_SUBDIV
            mt = 1.0 - t
            x = mt**3 * p0[0] + 3 * mt**2 * t * p1[0] + 3 * mt * t**2 * p2[0] + t**3 * p3[0]
            y = mt**3 * p0[1] + 3 * mt**2 * t * p1[1] + 3 * mt * t**2 * p2[1] + t**3 * p3[1]
            cur = (x, y)
            if math.hypot(cur[0] - prev[0], cur[1] - prev[1]) > 1e-6:
                segs.append((prev, cur))
                prev = cur
        return segs


def is_corner(t_in, t_out):
    cross = t_in[0] * t_out[1] - t_in[1] * t_out[0]
    dot = t_in[0] * t_out[0] + t_in[1] * t_out[1]
    return dot <= 0.0 or abs(cross) > CORNER_SIN_THRESH


def color_contours(raw_contours):
    """
    Applies Chlumsky edge coloring to each contour.
    Returns list of segments: (ax, ay, bx, by, r_bit, g_bit, b_bit, start_is_corner, end_is_corner).
    """
    COLORS = [(1, 1, 0), (0, 1, 1), (1, 0, 1)]  # Yellow, Cyan, Magenta
    colored_segs = []

    for contour in raw_contours:
        n = len(contour)
        if n == 0:
            continue
        tangents = [prim_tangents(p) for p in contour]
        corners = []
        for i in range(n):
            prev_t_end = tangents[(i - 1) % n][1]
            cur_t_start = tangents[i][0]
            if is_corner(prev_t_end, cur_t_start):
                corners.append(i)

        if len(corners) == 0:
            # Smooth closed loop (e.g., O, 0, o, ., °, •): white (all 3 channels)
            for prim in contour:
                for a, b in flatten_prim(prim):
                    colored_segs.append((a[0], a[1], b[0], b[1], 1, 1, 1, False, False))
        elif len(corners) == 1:
            # Single-corner teardrop: split flattened polyline into 3 colored arcs
            c0 = corners[0]
            ordered = contour[c0:] + contour[:c0]
            flat = []
            for prim in ordered:
                flat.extend(flatten_prim(prim))
            m = len(flat)
            for idx, (a, b) in enumerate(flat):
                ci = min(2, (idx * 3) // max(1, m))
                col = COLORS[ci]
                s_c = (idx == 0)
                e_c = (idx == m - 1)
                colored_segs.append((a[0], a[1], b[0], b[1], col[0], col[1], col[2], s_c, e_c))
        else:
            # Multi-corner contour: cycle Y/C/M across edges between corners
            num_edges = len(corners)
            edge_colors = []
            for ei in range(num_edges):
                if ei == 0:
                    edge_colors.append(0)
                elif ei == num_edges - 1:
                    # Pick color different from both previous and first
                    prev_c = edge_colors[-1]
                    first_c = edge_colors[0]
                    c = next(x for x in (0, 1, 2) if x != prev_c and x != first_c) if prev_c != first_c else (prev_c + 1) % 3
                    edge_colors.append(c)
                else:
                    edge_colors.append((edge_colors[-1] + 1) % 3)

            for ei in range(num_edges):
                i_start = corners[ei]
                i_end = corners[(ei + 1) % num_edges]
                col = COLORS[edge_colors[ei]]

                prims = []
                idx = i_start
                while True:
                    prims.append(contour[idx])
                    idx = (idx + 1) % n
                    if idx == i_end:
                        break

                flat = []
                for prim in prims:
                    flat.extend(flatten_prim(prim))
                m = len(flat)
                for si, (a, b) in enumerate(flat):
                    colored_segs.append((a[0], a[1], b[0], b[1], col[0], col[1], col[2], si == 0, si == m - 1))

    return colored_segs


def rasterize_mtsdf(colored_segs, scale, offset_x, offset_y):
    """
    Computes (CELL_H, CELL_W, 4) float32 MTSDF in [0, 1] using vectorized NumPy.
    Coordinate system: font coords (x_f, y_f) -> atlas coords:
      px = offset_x + x_f * scale
      py = offset_y - y_f * scale  (Y flipped so row 0 is top of glyph)
    """
    if not colored_segs:
        return np.zeros((CELL_H, CELL_W, 4), dtype=np.float32)

    # Transform segments into atlas pixel coordinates (Y-down!)
    # Note: flipping Y reverses winding orientation:
    # Original TrueType outer contour is CW in Y-up -> CCW in Y-down!
    # In Y-down, CCW outer contour has interior on the LEFT of tangent? Let's use exact winding number so sign is 100% immune!
    ax = np.array([offset_x + s[0] * scale for s in colored_segs], dtype=np.float64)
    ay = np.array([offset_y - s[1] * scale for s in colored_segs], dtype=np.float64)
    bx = np.array([offset_x + s[2] * scale for s in colored_segs], dtype=np.float64)
    by = np.array([offset_y - s[3] * scale for s in colored_segs], dtype=np.float64)
    ch_mask = np.array([[s[4], s[5], s[6]] for s in colored_segs], dtype=np.bool_)
    start_corner = np.array([s[7] for s in colored_segs], dtype=np.bool_)
    end_corner = np.array([s[8] for s in colored_segs], dtype=np.bool_)

    dx = bx - ax
    dy = by - ay
    seg_len2 = dx * dx + dy * dy
    valid = seg_len2 > 1e-12
    ax, ay, bx, by = ax[valid], ay[valid], bx[valid], by[valid]
    dx, dy, seg_len2 = dx[valid], dy[valid], seg_len2[valid]
    ch_mask = ch_mask[valid]
    start_corner = start_corner[valid]
    end_corner = end_corner[valid]

    seg_len = np.sqrt(seg_len2)
    tx = dx / seg_len
    ty = dy / seg_len

    # Pixel centers (H, W)
    gx = (np.arange(CELL_W, dtype=np.float64) + 0.5)[None, :, None]  # (1, W, 1)
    gy = (np.arange(CELL_H, dtype=np.float64) + 0.5)[:, None, None]  # (H, 1, 1)

    # Vector A -> P: shape (H, W, S)
    pax = gx - ax[None, None, :]
    pay = gy - ay[None, None, :]

    # Projection parameter t along segment
    t_raw = (pax * dx[None, None, :] + pay * dy[None, None, :]) / seg_len2[None, None, :]
    t = np.clip(t_raw, 0.0, 1.0)

    # Vector Q -> P where Q = A + t*(B - A)
    qx = pax - t * dx[None, None, :]
    qy = pay - t * dy[None, None, :]
    dist2 = qx * qx + qy * qy
    dist = np.sqrt(dist2)

    # Exact non-zero winding number via horizontal ray casting to +X
    # Segment crosses horizontal line y = gy upward (ay <= gy < by) or downward (by <= gy < ay)
    cond_up = (ay[None, None, :] <= gy) & (by[None, None, :] > gy)
    cond_dn = (by[None, None, :] <= gy) & (ay[None, None, :] > gy)
    # Cross product (B-A) x (P-A) = dx*pay - dy*pax
    edge_cross = dx[None, None, :] * pay - dy[None, None, :] * pax
    winding = np.sum(cond_up & (edge_cross > 0.0), axis=2) - np.sum(cond_dn & (edge_cross < 0.0), axis=2)
    inside = winding != 0
    global_sign = np.where(inside, 1.0, -1.0)  # +1 inside, -1 outside

    # Signed perpendicular distance to infinite supporting line of each segment.
    # TrueType outer contours are CW in Y-up -> CCW in Y-down, so interior lies to the right
    # of T in Y-down: cross(T, P - A) = tx*pay - ty*pax > 0 inside.
    signed_perp = tx[None, None, :] * pay - ty[None, None, :] * pax
    local_sign = np.where(signed_perp >= 0.0, 1.0, -1.0)

    # Orthogonality = |cross(T, (P - Q) / dist)| = |signed_perp| / dist
    ortho = np.abs(signed_perp) / np.maximum(dist, 1e-12)

    # True SDF (channel A)
    true_min_dist = np.min(dist, axis=2)
    true_sdf = global_sign * true_min_dist

    # Per-channel MSDF with Chlumsky tie-breaking (prefer smaller dist, then larger ortho)
    # Effective pseudo-distance: use signed_perp when clamped at a corner endpoint, else local_sign * dist
    at_start_corner = (t_raw < 0.0) & start_corner[None, None, :]
    at_end_corner = (t_raw > 1.0) & end_corner[None, None, :]
    use_pseudo = at_start_corner | at_end_corner
    pseudo_d = np.where(use_pseudo, signed_perp, local_sign * dist)

    # Composite key for Chlumsky selection: dist - 1e-5 * ortho (breaks ties at shared corner vertices)
    key = dist - 1e-5 * ortho

    out = np.zeros((CELL_H, CELL_W, 4), dtype=np.float32)
    ch_vals = np.zeros((CELL_H, CELL_W, 3), dtype=np.float64)

    for c in range(3):
        mask_c = ch_mask[:, c]
        if not np.any(mask_c):
            ch_vals[:, :, c] = true_sdf
            continue
        key_c = np.where(mask_c[None, None, :], key, 1e9)
        best_idx = np.argmin(key_c, axis=2)
        h_idx = np.arange(CELL_H)[:, None]
        w_idx = np.arange(CELL_W)[None, :]
        ch_vals[:, :, c] = pseudo_d[h_idx, w_idx, best_idx]

    # Exact global sign consistency guard: if median sign disagrees with exact winding, repair with true_sdf
    med = np.median(ch_vals, axis=2)
    mismatch = (med >= 0.0) != inside
    if np.any(mismatch):
        for c in range(3):
            ch_vals[:, :, c] = np.where(mismatch, true_sdf, ch_vals[:, :, c])

    # Normalize to [0, 1] with 0.5 on contour and full span = 2 * PX_RANGE texels
    inv_span = 1.0 / (2.0 * PX_RANGE)
    for c in range(3):
        out[:, :, c] = np.clip(0.5 + ch_vals[:, :, c] * inv_span, 0.0, 1.0)
    out[:, :, 3] = np.clip(0.5 + true_sdf * inv_span, 0.0, 1.0)

    return out


def main():
    font = TTFont(FONT_PATH)
    cmap = font.getBestCmap()
    glyph_set = font.getGlyphSet()
    hmtx = font["hmtx"]
    os2 = font["OS/2"]

    cap_height = float(getattr(os2, "sCapHeight", 730))
    adv_width = float(hmtx[cmap[ord("H")]][0])

    # Scale so glyph fits comfortably with >= 4.5 px padding on all 4 sides of 36x56 cell
    scale = 24.0 / adv_width  # 24 px advance inside 36 px cell -> 6 px side margin!
    # Baseline positioned so ascender (930) + descender (-240) sit centered vertically
    baseline_y = 41.0
    cap_top_y = baseline_y - cap_height * scale
    cap_height_px = cap_height * scale

    atlas_w = COLS * CELL_W
    atlas_h = ROWS * CELL_H
    atlas = np.zeros((atlas_h, atlas_w, 4), dtype=np.uint8)

    print(f"Font: {os.path.basename(FONT_PATH)} | adv={adv_width} capH={cap_height} scale={scale:.5f}")
    print(f"Atlas: {atlas_w}x{atlas_h} ({COLS}x{ROWS} grid, {CELL_W}x{CELL_H} cell, pxRange={PX_RANGE})")

    mismatches_total = 0
    for idx, cp in enumerate(GLYPH_CODES):
        glyph_name = cmap.get(cp)
        col = idx % COLS
        row = idx // COLS
        x0 = col * CELL_W
        y0 = row * CELL_H

        if glyph_name is None or cp == 32:
            continue

        raw_contours = extract_contours(glyph_set, glyph_name)
        if not raw_contours:
            continue

        # Center advance horizontally inside CELL_W
        gw = float(hmtx[glyph_name][0])
        offset_x = (CELL_W - gw * scale) * 0.5
        colored_segs = color_contours(raw_contours)
        cell_f = rasterize_mtsdf(colored_segs, scale, offset_x, baseline_y)

        # Sanity check: median >= 0.5 matches true_sdf >= 0.5
        med_u = np.median(cell_f[:, :, :3], axis=2) >= 0.5
        sdf_u = cell_f[:, :, 3] >= 0.5
        mismatches_total += int(np.sum(med_u != sdf_u))

        cell_u8 = np.clip(np.round(cell_f * 255.0), 0, 255).astype(np.uint8)
        atlas[y0:y0 + CELL_H, x0:x0 + CELL_W, :] = cell_u8

    print(f"Generated {len(GLYPH_CODES)} glyphs | median/SDF sign mismatches: {mismatches_total}")

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    header = struct.pack(
        ">IHHHHHHffff",
        0x4D545344,  # 'MTSD'
        atlas_w,
        atlas_h,
        COLS,
        ROWS,
        CELL_W,
        CELL_H,
        PX_RANGE,
        cap_top_y / CELL_H,
        cap_height_px / CELL_H,
        (adv_width * scale) / CELL_W,
    )
    compressed = zlib.compress(atlas.tobytes(), level=9)
    with open(OUT_PATH, "wb") as f:
        f.write(header)
        f.write(compressed)

    print(f"Wrote {OUT_PATH}: {len(header) + len(compressed):,} bytes (raw {atlas.nbytes:,} bytes)")

    # Optional visual preview when --preview <path.png> is passed
    if len(sys.argv) >= 3 and sys.argv[1] == "--preview":
        from PIL import Image
        # Render reconstructed median at 2x magnification to verify razor-sharp corners
        img = Image.fromarray(atlas[:, :, :3], mode="RGB")
        img2 = img.resize((atlas_w * 2, atlas_h * 2), Image.BILINEAR)
        arr = np.asarray(img2, dtype=np.float32) / 255.0
        med = np.median(arr, axis=2)
        aa = np.clip((med - 0.5) * 16.0 + 0.5, 0.0, 1.0)
        vis = np.clip(aa * 255.0, 0, 255).astype(np.uint8)
        Image.fromarray(vis, mode="L").save(sys.argv[2])
        print(f"Saved preview to {sys.argv[2]}")


if __name__ == "__main__":
    main()
