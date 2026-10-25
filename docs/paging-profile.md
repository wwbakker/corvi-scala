# Pi paging profile

`./scripts/pi-paging-profile.sh [session.jsonl]` reproduces the slow Pi paging report without touching the user's host or Pi session.

## What it does

- copies the selected Pi session (default: the newest one) into a temporary directory;
- writes a temporary `$SHELL` that resumes that copy with `--offline`;
- starts a **private** host socket with the copy;
- opens the real window on workspace 6, through a floating Hyprland window rule when `hyprctl` is available;
- waits for Pi to settle at roughly 185×83 cells;
- sends 20 PageUp and 20 PageDown steps, then two rapid 20-key bursts, through JavaFX key dispatch;
- records frame counts, first-frame and settle latency, CPU, context switches and JFR recordings for the app and host.

The script removes Mill's cached `forkEnv`/`forkArgs` task results first, so each run gets its own socket and rendering mode. Set
`CORVI_PAGING_HW=0` to profile the software fallback; the default is the production `es2,sw` path.

## Reading the result

The probe prints `PI_PAGING_RESULT` and writes `result.json`, `app.jfr` and `host.jfr` in the work directory printed by the script.
Useful JFR views:

```sh
jfr view hot-methods              "$WORK/app.jfr"
jfr view allocation-by-site       "$WORK/app.jfr"
jfr view gc-pauses                "$WORK/app.jfr"
jfr view hot-methods              "$WORK/host.jfr"
jfr view allocation-by-site       "$WORK/host.jfr"
jfr view socket-writes-by-host    "$WORK/host.jfr"
```

The first profile on this machine (186×83, copied 7.4 MB Pi session, hardware ES2) showed:

- 20 PageUp steps: ~6.8 s total, first frame p50 ~213 ms;
- 20 PageDown steps: ~6.3 s total, first frame p50 ~172 ms;
- rapid 20-key bursts: ~0.6–0.9 s each, coalesced to 2–6 painted frames;
- app CPU ~21 s, host CPU ~7.4 s;
- host socket writes: ~74.5 MB over the measured window;
- app hot methods: Kyo JSON/Chunk decoding (`Chunk.Compact`, `FramerImpl.indexOf`, byte/array copies), not canvas painting;
- host hot methods: JSON serialisation (`StringBuilder`, `SchemaSerializer`, `ScreenReader` cells) and GC;
- GC: 31 app pauses / 238 ms total, 19 host pauses / 152 ms total.

The software run was within a few percent of the hardware run: rendering is not the dominant cost at this size. The frame payload and
the JSON codec are.

## Optimization history

The probe was run at each production change (hardware ES2, 186x83, same copied session):

| Change | App CPU | Host CPU | PageUp steps | PageDown steps | App heap |
|---|---:|---:|---:|---:|---:|
| baseline JSON damage | 23.3 s | 6.9 s | 6.58 s | 6.61 s | 175 MB |
| `d838258` binary damage | 4.2 s | 4.4 s | 3.77 s | 3.63 s | 175 MB |
| `12f34fd` one-frame ack | 4.9 s | 5.1 s | 4.3 s | 4.15 s | 37 MB |
| `737ca4e` changed-row damage | 4.4 s | 4.0 s | 4.2 s | 4.0 s | 69 MB |
| `18a4fe2` text runs | 4.4 s | 4.7 s | 4.2 s | 4.0 s | 49 MB |

The binary codec is the large win: host socket traffic fell from roughly 74.5 MB to under 1 MB for the measured window. One-frame
acknowledgement trades a little latency for bounded memory and no stale frame backlog. A dedicated socket-writer thread was also tested
and reverted: with one in-flight frame, writes are small, and the extra queue/thread cost more CPU without reducing the page latency.

The probe window lives on workspace 6, so it is not composited by the active workspace. That exercises the model, renderer and protocol,
but it may understate the cost of presenting painted frames on screen. The next benchmark should either force a scene snapshot after the
burst or run the window on the active workspace; painting is the next thing to measure before changing threads further.
