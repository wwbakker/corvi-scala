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
