import sys, statistics

def blocks(path):
    lines = open(path).read().splitlines()
    out, cur = [], None
    for l in lines:
        if l.startswith('---PROFILEDATA---'):
            if cur is None: cur = []
            else: out.append(cur); cur = None
        elif cur is not None:
            cur.append(l)
    return out

def pct(v, p):
    v = sorted(v)
    return v[min(len(v)-1, int(len(v)*p))]

for path in sys.argv[1:]:
    print(f"\n######## {path}")
    for bi, b in enumerate(blocks(path)):
        hdr = [h for h in b[0].split(',') if h]
        rows = [r.split(',') for r in b[1:] if r.strip()]
        rows = [r for r in rows if len(r) >= len(hdr)]
        idx = {n: i for i, n in enumerate(hdr)}
        c = lambda r, n: int(r[idx[n]])
        rows = [r for r in rows if c(r, 'FrameCompleted') > 0]
        if len(rows) < 10:
            print(f"  block {bi}: {len(rows)} frames (skip)"); continue
        print(f"  --- block {bi}: {len(rows)} frames")
        interval = statistics.median([c(r, 'FrameInterval') for r in rows]) / 1e6
        print(f"    target FrameInterval = {interval:.2f} ms  ({1000/interval:.0f} Hz)")

        pres = sorted([c(r, 'DisplayPresentTime') for r in rows if c(r, 'DisplayPresentTime') > 0])
        if len(pres) > 5:
            d = [(pres[i+1]-pres[i])/1e6 for i in range(len(pres)-1)]
            d = [x for x in d if 0 < x < 500]
            print(f"    ФАКТ present interval: p50={statistics.median(d):.1f}ms  "
                  f"p90={pct(d,0.9):.1f}ms  max={max(d):.1f}ms  => fps(p50)={1000/statistics.median(d):.0f}")

        total = [(c(r,'FrameCompleted')-c(r,'IntendedVsync'))/1e6 for r in rows]
        print(f"    IntendedVsync->Completed: p50={statistics.median(total):.2f} p90={pct(total,0.9):.2f} max={max(total):.2f}")
        late = sum(1 for r in rows if c(r,'FrameCompleted') > c(r,'FrameDeadline'))
        print(f"    прошло дедлайн: {late}/{len(rows)} = {100*late/len(rows):.1f}%")

        stages = [('HandleInputStart','AnimationStart','input'),
                  ('AnimationStart','PerformTraversalsStart','animation'),
                  ('PerformTraversalsStart','DrawStart','measure+layout'),
                  ('DrawStart','SyncQueued','draw/record'),
                  ('SyncStart','IssueDrawCommandsStart','sync+upload'),
                  ('IssueDrawCommandsStart','SwapBuffers','issue draw cmds'),
                  ('SwapBuffers','FrameCompleted','swap->completed')]
        for a, b2, name in stages:
            if a in idx and b2 in idx:
                v = [(c(r,b2)-c(r,a))/1e6 for r in rows if c(r,b2) > c(r,a) > 0]
                if v:
                    print(f"      {name:<18} p50={statistics.median(v):6.2f}  p90={pct(v,0.9):6.2f}  max={max(v):7.2f}")
