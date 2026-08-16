"""Per-frame main-thread analysis: doFrame durations, their inner phases, and
what DefaultDispatcher/RenderThread were doing at the same time."""
import re, sys, statistics
from collections import defaultdict

pat = re.compile(r'^\s*(\S+)-(\d+)\s+\(\s*(\d+|-+)\)\s+\[(\d+)\]\s+\S+\s+([\d.]+):\s+(\S+):\s*(.*)$')

def load(path, pid):
    per_thread = defaultdict(list)  # tid -> [(ts,kind,name)]
    names = {}
    for line in open(path, errors='ignore'):
        m = pat.match(line)
        if not m: continue
        tname, tid, p, cpu, ts, ev, rest = m.groups()
        if ev != 'tracing_mark_write' or p != pid: continue
        ts = float(ts)
        names[tid] = tname
        if rest.startswith('B|'):
            parts = rest.split('|', 2)
            per_thread[tid].append((ts, 'B', parts[2] if len(parts) > 2 else ''))
        elif rest.startswith('E'):
            per_thread[tid].append((ts, 'E', ''))
    return per_thread, names

def slices(evts):
    st, out = [], []
    for ts, k, n in evts:
        if k == 'B': st.append((ts, n))
        else:
            if st:
                t0, nm = st.pop()
                out.append((t0, ts, nm, len(st)))
    return out

def main(path, pid):
    per_thread, names = load(path, pid)
    main_tid = next(t for t, n in names.items() if n.startswith('chizberg') or n.startswith('zberg'))
    msl = slices(per_thread[main_tid])
    frames = [s for s in msl if s[2].startswith('Choreographer#doFrame') and s[3] == 0]
    frames.sort()
    durs = [(1000 * (b - a)) for a, b, n, d in frames]
    print(f"== {path}: {len(frames)} doFrame, p50={statistics.median(durs):.1f} p90={sorted(durs)[int(len(durs)*.9)]:.1f} max={max(durs):.1f} ms")
    bad = [f for f in frames if (f[1] - f[0]) * 1000 > 12]
    print(f"   кадров >12ms: {len(bad)}")
    # For each bad frame: top inner slices + concurrent decode work
    all_named = defaultdict(list)
    for tid, evts in per_thread.items():
        for s in slices(evts):
            all_named[names[tid]].append(s)
    for a, b, n, d in sorted(bad, key=lambda f: f[0] - f[1])[:8]:
        print(f"   -- кадр {1000*(b-a):6.1f} ms")
        inner = [s for s in msl if s[0] >= a and s[1] <= b and s[3] >= 1]
        inner.sort(key=lambda s: s[0] - s[1])
        for s in inner[:5]:
            dur = 1000 * (s[1] - s[0])
            if dur > 2: print(f"        {dur:7.1f} ms d{s[3]} {s[2][:75]}")
        # concurrent work on other threads
        for tn in ('DefaultDispatch', 'RenderThread', 'GL-Map'):
            conc = [s for s in all_named.get(tn, []) if s[0] < b and s[1] > a and s[3] == 0]
            tot = sum(min(s[1], b) - max(s[0], a) for s in conc) * 1000
            if tot > 3:
                top = max(conc, key=lambda s: s[1] - s[0])
                print(f"        [{tn}] занят {tot:.1f} ms, топ: {top[2][:60]} ({1000*(top[1]-top[0]):.1f} ms)")

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
