# Key input probe notes

This is the evidence behind slice 1 of the input plan: Pi's key negotiation, the exact bytes the host sends, and what a real Pi session
did with them.

## What Pi negotiates

Captured with `script` on the probe machine (Pi 1.4.0, `TERM=xterm-256color`, `--offline --no-session --no-extensions --no-mcp
--no-skills`):

```
./pi --no-session --offline --no-extensions --no-mcp --no-skills
```

At startup Pi sends, in this order:

- `ESC [ ? 1049 h` — alternate screen
- `ESC [ ? 7 l` — auto-wrap off
- `ESC [ ? 1000 h`, `ESC [ ? 1002 h`, `ESC [ ? 1003 h` — mouse click, button motion and any motion
- `ESC [ ? 1004 h` — focus reporting
- `ESC [ ? 1006 h` — SGR mouse coordinates
- `ESC [ ? 2004 h` — bracketed paste
- `ESC [ > 7 u`, `ESC [ ? u` — kitty keyboard protocol: set flags, then query them
- `ESC ] 7501 ; ? ESC \` — a `pi`-specific color/theme query
- `ESC [ c` — device attributes, twice
- `ESC ] 10 ; ? BEL`, `ESC ] 11 ; ? BEL`, `ESC ] 4 ; n ; ? BEL` — foreground, background and palette queries

It does **not** enable DECCKM at startup (`ESC [ ? 1 h` was absent), so the normal cursor sequences (`ESC [ A`, `ESC [ H`, …) are the ones
Pi receives. JediTermFX does not answer the kitty query, so Pi runs with its standard-key fallback. The host probe therefore checks both
DECCKM off and on at the byte level rather than assuming what Pi leaves enabled.

`ESC [ > 7 u` asks for kitty's keyboard reporting; this slice does not implement kitty flags. The standard xterm sequences still reach Pi,
which is what the smoke check below exercises. Answering the kitty query is a separate decision: it would change every key Pi sees, not
just the missing ones.

## Byte-level checks

`HostProbe` runs a raw-mode `dd` under the shell and compares `od`'s hex output against the exact sequence. The checked set includes:

| key | normal mode | DECCKM |
| --- | --- | --- |
| Up | `ESC [ A` | `ESC O A` |
| Left | `ESC [ D` | `ESC O D` |
| Home | `ESC [ H` | `ESC [ H` |
| End | `ESC [ F` | `ESC [ F` |
| Insert | `ESC [ 2 ~` | `ESC [ 2 ~` |
| Delete | `ESC [ 3 ~` | `ESC [ 3 ~` |
| F1 | `ESC O P` | `ESC O P` |
| Shift+Tab | `ESC [ Z` | `ESC [ Z` |
| Ctrl+Left | `ESC [ 1 ; 5 D` | — |
| Alt+Left | `ESC [ 1 ; 3 D` | — |

`KeyEncoderTest` covers the same encoder without a PTY, including F1–F12, Shift/Control/Alt/Meta suffixes, the Escape and plain-Tab
fallbacks, and the Meta prefix for Alt+Backspace/Enter/Escape and Alt+printable. It reuses one `TerminalKeyEncoder` for a modified key
followed by its plain form, because JediTermFX's `getCodeWithModifiers` mutates its map array in place; `HostProbe` does the same on a real
PTY with Shift+Up, Up, Shift+F1, F1.

## Real-Pi smoke check

A temporary `HostSession` (not part of the committed probe suite) ran Pi with the command above and drove it through the new key route:

- typing `hello`, Left twice and `X` produced `helXlo` — arrows move Pi's input cursor;
- Home then `Y`, End then `Z` produced `YhelXloZ` — Home and End work;
- PageUp/PageDown and Shift+Tab were sent and the session stayed alive, and text typed afterwards still arrived. This is a liveness
  check only: it does not prove Pi scrolled or switched anything, because the startup screen had nothing to scroll. Scrolling is asserted
  at the byte level by `HostProbe`, not against Pi's UI;
- the Alt/Ctrl+Left check did not produce the word-move the check guessed at, so it is recorded as "accepted, no crash" rather than a
  Pi word-motion assertion. The exact bytes are asserted by `HostProbe`.

Pi enables mouse reporting (`1000`/`1002`/`1003`/`1006`), focus reporting (`1004`) and bracketed paste (`2004`) at startup. Mouse reports
already work; focus and bracketed paste are later slices of the same plan.

## Known limitations

- **Alt+printable uses the US layout.** The renderer's table maps shifted digits and punctuation to `!@#$%^&*()` and the US
  punctuation pairings, so a non-US layout can produce a different character for a covered key. Keys outside the table fall back to the
  platform's typed character, which is what `TerminalView` sends when no press took the key.
- **No keypad contract.** JediTermFX encodes only its keypad cursor constants, to the same sequences as the arrow keys; it has no keypad
  digits or Enter. JavaFX does expose `KP_*` and `NUMPAD*` codes, so supporting the keypad would be a separate partial mapping.
- **AltGr is still a Control+Alt report.** Where a platform reports AltGr as Control+Alt, the non-Compose Alt modes currently swallow its
  composed typed character. Alt-only composition in Compose mode is unaffected.
