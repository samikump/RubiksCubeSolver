import sys
import time
import array
import os

# --- High-Performance Two-Phase Rubik's Cube Solver ---
# Optimized for GraalVM/Python integration.
# Fast Two-Phase Search with QTM-based Junction Merging.

class CubieCube:
    __slots__ = ['cp', 'co', 'ep', 'eo']
    def __init__(self, cp=None, co=None, ep=None, eo=None):
        self.cp = cp if cp is not None else list(range(8))
        self.co = co if co is not None else [0] * 8
        self.ep = ep if ep is not None else list(range(12))
        self.eo = eo if eo is not None else [0] * 12

    def multiply(self, b):
        res = CubieCube()
        for i in range(8):
            res.cp[i] = self.cp[b.cp[i]]
            res.co[i] = (self.co[b.cp[i]] + b.co[i]) % 3
        for i in range(12):
            res.ep[i] = self.ep[b.ep[i]]
            res.eo[i] = (self.eo[b.ep[i]] + b.eo[i]) % 2
        return res

# --- Facelet Mapping ---
corner_facelets = [(8,9,20), (6,18,38), (0,36,47), (2,45,11), (29,26,15), (27,44,24), (33,53,42), (35,17,51)]
edge_facelets = [(5,10), (7,19), (3,37), (1,46), (32,16), (28,25), (30,43), (34,52), (23,12), (21,41), (50,39), (48,14)]

def get_cubie_from_facelets(s):
    cc = CubieCube()
    corner_colors = [["U","R","F"], ["U","F","L"], ["U","L","B"], ["U","B","R"], ["D","F","R"], ["D","L","F"], ["D","B","L"], ["D","R","B"]]
    for i, flets in enumerate(corner_facelets):
        for ori in range(3):
            if s[flets[ori]] in ['U', 'D']: cc.co[i] = ori; break
        colors = sorted([s[flets[0]], s[flets[1]], s[flets[2]]])
        for j, def_colors in enumerate(corner_colors):
            if colors == sorted(def_colors): cc.cp[i] = j; break
    edge_colors = [["U","R"], ["U","F"], ["U","L"], ["U","B"], ["D","R"], ["D","F"], ["D","L"], ["D","B"], ["F","R"], ["F","L"], ["B","L"], ["B","R"]]
    for i, flets in enumerate(edge_facelets):
        c0, c1 = s[flets[0]], s[flets[1]]
        if c0 in ['U', 'D']: cc.eo[i] = 0
        elif c1 in ['U', 'D']: cc.eo[i] = 1
        elif c0 in ['F', 'B']: cc.eo[i] = 0
        else: cc.eo[i] = 1
        colors = sorted([c0, c1])
        for j, def_colors in enumerate(edge_colors):
            if colors == sorted(def_colors): cc.ep[i] = j; break
    return cc

def apply_facelet_move(s, move):
    f = list(s)
    def swap4(i, j, k, l): f[i], f[j], f[k], f[l] = f[l], f[i], f[j], f[k]
    if move == 'U':
        swap4(0,2,8,6); swap4(1,5,7,3)
        swap4(18,36,45,9); swap4(19,37,46,10); swap4(20,38,47,11)
    elif move == 'R':
        swap4(9,11,17,15); swap4(10,14,16,12)
        swap4(8,45,35,26); swap4(5,48,32,23); swap4(2,51,29,20)
    elif move == 'F':
        swap4(18,20,26,24); swap4(19,23,25,21)
        swap4(6,9,29,44); swap4(7,12,28,41); swap4(8,15,27,38)
    elif move == 'D':
        swap4(27,29,35,33); swap4(28,32,34,30)
        swap4(24,15,51,42); swap4(25,16,52,43); swap4(26,17,53,44)
    elif move == 'L':
        swap4(36,38,44,42); swap4(37,41,43,39)
        swap4(0,18,27,53); swap4(3,21,30,50); swap4(6,24,33,47)
    elif move == 'B':
        swap4(45,47,53,51); swap4(46,50,52,48)
        swap4(2,36,33,17); swap4(1,39,34,14); swap4(0,42,35,11)
    return "".join(f)

solved_str = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB"
move_names = ["U", "U U", "U'", "R", "R R", "R'", "F", "F F", "F'", "D", "D D", "D'", "L", "L L", "L'", "B", "B B", "B'"]
all_moves = []
for m_name in ['U', 'R', 'F', 'D', 'L', 'B']:
    m_str = apply_facelet_move(solved_str, m_name)
    m_cubie = get_cubie_from_facelets(m_str)
    m2 = m_cubie.multiply(m_cubie)
    m3 = m2.multiply(m_cubie)
    all_moves.extend([m_cubie, m2, m3])

class Search:
    def __init__(self):
        self.initialized = False
        self.twist_move = array.array('H', [0] * (2187 * 18))
        self.flip_move = array.array('H', [0] * (2048 * 18))
        self.slice_move = array.array('H', [0] * (495 * 18))
        self.cp_move = array.array('H', [0] * (40320 * 18))
        self.ep8_move = array.array('H', [0] * (40320 * 18))
        self.ep4_move = array.array('B', [0] * (24 * 18))
        
        self.dist_twist_slice = bytearray([255] * (495 * 2187))
        self.dist_flip_slice = bytearray([255] * (495 * 2048))
        self.dist_p2_cp = bytearray([255] * (24 * 40320))
        self.dist_p2_ep8 = bytearray([255] * (24 * 40320))

    def init_tables(self, log_func):
        if self.initialized: return
        log_func("Building move tables...")
        for i in range(2187):
            c = CubieCube(); self.set_twist(c, i)
            for m in range(18): self.twist_move[i * 18 + m] = self.get_twist(c.multiply(all_moves[m]))
        for i in range(2048):
            c = CubieCube(); self.set_flip(c, i)
            for m in range(18): self.flip_move[i * 18 + m] = self.get_flip(c.multiply(all_moves[m]))
        for i in range(495):
            c = CubieCube(); self.set_slice(c, i)
            for m in range(18): self.slice_move[i * 18 + m] = self.get_slice(c.multiply(all_moves[m]))
        for i in range(40320):
            c = CubieCube(); self.set_cp(c, i)
            for m in range(18): self.cp_move[i * 18 + m] = self.get_cp(c.multiply(all_moves[m]))
        for i in range(40320):
            c = CubieCube(); self.set_ep8(c, i)
            for m in range(18): self.ep8_move[i * 18 + m] = self.get_ep8(c.multiply(all_moves[m]))
        for i in range(24):
            c = CubieCube(); self.set_ep4(c, i)
            for m in range(18): self.ep4_move[i * 18 + m] = self.get_ep4(c.multiply(all_moves[m]))

        def build_dist(table, move_a, move_b, size_a, size_b, name, allowed_moves):

            log_func(f"{name} pruning table...")
            table[0] = 0
            queues = [array.array('I') for _ in range(32)]
            queues[0].append(0)
            count = 1
            for depth in range(31):
                if not queues[depth]: continue
                for idx in queues[depth]:
                    if table[idx] < depth: continue
                    s_idx, b_idx = divmod(idx, size_b)
                    for m in allowed_moves:
                        w = 2 if m % 3 == 1 else 1 # QTM weight
                        n_s = move_a[s_idx * 18 + m]
                        n_b = move_b[b_idx * 18 + m]
                        nidx = n_s * size_b + n_b
                        if table[nidx] > depth + w:
                            if table[nidx] == 255: count += 1
                            table[nidx] = depth + w
                            queues[depth + w].append(nidx)
                # log_func(f"Depth {depth}: {count} nodes")
                log_func(f"Depth {depth:02d}: {count} nodes")
                queues[depth] = array.array('I')

        build_dist(self.dist_twist_slice, self.slice_move, self.twist_move, 495, 2187, "Twist-Slice", range(18))
        build_dist(self.dist_flip_slice, self.slice_move, self.flip_move, 495, 2048, "Flip-Slice", range(18))
        
        # Phase 2 moves: U123, D123, R2, L2, F2, B2
        p2_moves = [0, 1, 2, 9, 10, 11, 4, 13, 7, 16]
        build_dist(self.dist_p2_cp, self.ep4_move, self.cp_move, 24, 40320, "P2-CP", p2_moves)
        build_dist(self.dist_p2_ep8, self.ep4_move, self.ep8_move, 24, 40320, "P2-EP8", p2_moves)

        self.initialized = True
        log_func("Solver Ready.")

    def get_twist(self, c):
        res = 0
        for i in range(7): res = res * 3 + (c.co[i] % 3)
        return res
    def set_twist(self, c, v):
        s = 0
        for i in range(6, -1, -1): c.co[i] = v % 3; s += c.co[i]; v //= 3
        c.co[7] = (3 - s % 3) % 3

    def get_flip(self, c):
        res = 0
        for i in range(11): res = res * 2 + (c.eo[i] % 2)
        return res
    def set_flip(self, c, v):
        s = 0
        for i in range(10, -1, -1): c.eo[i] = v % 2; s += c.eo[i]; v //= 2
        c.eo[11] = s % 2

    def get_slice(self, c):
        res, r, n = 0, 3, 11
        while n >= 0:
            if 8 <= c.ep[n] <= 11: r -= 1
            else:
                if r >= 0: res += self._C(n, r)
            n -= 1
        return res
    def set_slice(self, c, v):
        c.ep = [-1] * 12; r, n = 3, 11
        while n >= 0:
            if v >= self._C(n, r): v -= self._C(n, r)
            else: c.ep[n] = r + 8; r -= 1
            n -= 1
        j = 0
        for i in range(12):
            if c.ep[i] == -1:
                while j in [8, 9, 10, 11]: j += 1
                c.ep[i] = j; j += 1

    def get_cp(self, c):
        res, p = 0, list(c.cp)
        for i in range(8):
            res *= (8 - i); count = 0
            for j in range(i + 1, 8):
                if p[j] < p[i]: count += 1
            res += count
        return res
    def set_cp(self, c, v):
        p = [0] * 8
        for i in range(7, -1, -1): p[i] = v % (8 - i); v //= (8 - i)
        for i in range(6, -1, -1):
            for j in range(i + 1, 8):
                if p[j] >= p[i]: p[j] += 1
        c.cp = p

    def get_ep8(self, c):
        res = []
        for i in range(12):
            if c.ep[i] < 8: res.append(c.ep[i])
        perm = 0
        for i in range(8):
            perm *= (8 - i); count = 0
            for j in range(i + 1, 8):
                if res[j] < res[i]: count += 1
            perm += count
        return perm
    def set_ep8(self, c, v):
        p = [0] * 8
        for i in range(7, -1, -1): p[i] = v % (8 - i); v //= (8 - i)
        for i in range(6, -1, -1):
            for j in range(i + 1, 8):
                if p[j] >= p[i]: p[j] += 1
        j = 0
        for i in range(12):
            if c.ep[i] < 8: c.ep[i] = p[j]; j += 1

    def get_ep4(self, c):
        res = []
        for i in range(12):
            if 8 <= c.ep[i] <= 11: res.append(c.ep[i] - 8)
        perm = 0
        for i in range(4):
            perm *= (4 - i); count = 0
            for j in range(i + 1, 4):
                if res[j] < res[i]: count += 1
            perm += count
        return perm
    def set_ep4(self, c, v):
        p = [0] * 4
        for i in range(3, -1, -1): p[i] = v % (4 - i); v //= (4 - i)
        for i in range(2, -1, -1):
            for j in range(i + 1, 4):
                if p[j] >= p[i]: p[j] += 1
        j = 0
        for i in range(12):
            if 8 <= c.ep[i] <= 11: c.ep[i] = p[j] + 8; j += 1

    def _C(self, n, k):
        if k < 0 or k > n: return 0
        if k == 0 or k == n: return 1
        if k > n // 2: k = n - k
        res = 1
        for i in range(k): res = res * (n - i) // (i + 1)
        return res

    def solve(self, state, log_func):
        self.init_tables(log_func)
        cc = get_cubie_from_facelets(state)
        
        twist, flip, slice = self.get_twist(cc), self.get_flip(cc), self.get_slice(cc)
        
        log_func("Searching for solution...")
        self.best_sol = None
        
        # Phase 1: Find the shortest path to G1 (QTM-weighted)
        for p1_depth in range(31):
            solutions = []
            self.find_p1(cc, twist, flip, slice, p1_depth, -1, [], solutions)
            if solutions:
                # For each P1 solution, find the shortest P2
                best_total_qtm = 100
                for p1_path in solutions:
                    c2 = cc
                    for m in p1_path: c2 = c2.multiply(all_moves[m])
                    cp, ep8, ep4 = self.get_cp(c2), self.get_ep8(c2), self.get_ep4(c2)
                    h2 = max(self.dist_p2_cp[ep4 * 40320 + cp], self.dist_p2_ep8[ep4 * 40320 + ep8])
                    
                    # Search Phase 2 for this P1
                    for p2_depth in range(h2, 31):
                        p2_path = []
                        last_face = p1_path[-1] // 3 if p1_path else -1
                        if self.search_p2(cp, ep8, ep4, p2_depth, last_face, p2_path):
                            full_path = p1_path + p2_path
                            sol_str, qtm = self.simplify_and_format(full_path)
                            if qtm < best_total_qtm:
                                self.best_sol = full_path
                                best_total_qtm = qtm
                            break
                    if best_total_qtm <= p1_depth + 2: break # Heuristic stop
                if self.best_sol: break

        if self.best_sol:
            sol_str, qtm_count = self.simplify_and_format(self.best_sol)
            log_func(f"Solution found: {qtm_count} move(s).")
            return sol_str
        return "Error: No solution found."

    def find_p1(self, cc, twist, flip, slice, depth, last_face, path, solutions):
        h = max(self.dist_twist_slice[slice * 2187 + twist], self.dist_flip_slice[slice * 2048 + flip])
        if h > depth: return
        if depth == 0:
            solutions.append(list(path))
            return
        
        for m in range(18):
            w = 2 if m % 3 == 1 else 1
            if w > depth: continue
            face = m // 3
            if face == last_face: continue
            if last_face != -1 and face == (last_face + 3) % 6 and face < last_face: continue
            
            self.find_p1(cc.multiply(all_moves[m]), 
                         self.twist_move[twist*18+m], 
                         self.flip_move[flip*18+m], 
                         self.slice_move[slice*18+m], 
                         depth - w, face, path + [m], solutions)
            if len(solutions) >= 10: return # Limit for speed

    def search_p2(self, cp, ep8, ep4, depth, last_face, path):
        h = max(self.dist_p2_cp[ep4 * 40320 + cp], self.dist_p2_ep8[ep4 * 40320 + ep8])
        if h > depth: return False
        if depth == 0: return cp == 0 and ep8 == 0 and ep4 == 0
        
        # Phase 2 moves: U123, D123, R2, L2, F2, B2
        for m in [0, 1, 2, 9, 10, 11, 4, 13, 7, 16]:
            w = 2 if m % 3 == 1 else 1
            if w > depth: continue
            face = m // 3
            if face == last_face: continue
            if last_face != -1 and face == (last_face + 3) % 6 and face < last_face: continue
            
            if self.search_p2(self.cp_move[cp*18+m], self.ep8_move[ep8*18+m], self.ep4_move[ep4*18+m], depth - w, face, path):
                path.insert(0, m)
                return True
        return False

    def simplify_and_format(self, moves):
        if not moves: return "Solved", 0
        # First pass: merge same-face moves
        merged = []
        for m in moves:
            face = m // 3
            power = (m % 3) + 1
            if merged and merged[-1] // 3 == face:
                last_m = merged.pop()
                last_power = (last_m % 3) + 1
                new_power = (last_power + power) % 4
                if new_power > 0:
                    merged.append(face * 3 + (new_power - 1))
            else:
                merged.append(m)
        
        # Second pass: format for Java (QTM)
        res = []
        qtm_count = 0
        for m in merged:
            face_name = ["U", "R", "F", "D", "L", "B"][m // 3]
            power = (m % 3) + 1
            if power == 1:
                res.append(face_name)
                qtm_count += 1
            elif power == 2:
                res.append(face_name)
                res.append(face_name)
                qtm_count += 2
            elif power == 3:
                res.append(face_name + "'")
                qtm_count += 1
        return " ".join(res), qtm_count

_searcher = Search()
def solve(state):
    try:
        import polyglot
        log = polyglot.import_value("log")
    except: log = print
    return _searcher.solve(state, log)

try:
    import polyglot
    polyglot.export_value("solve", solve)
except: pass
