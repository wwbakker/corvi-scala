# Find and font size

The manual checks below are listed for a desktop pass; this build's automated environment did not run the real-Pi or non-integer-DPI
checks, so those remain pending.

## Find

`TerminalSearch` is a literal, case-insensitive search over the loaded history followed by the live grid. It reports document rows and
terminal-cell spans, not UTF-16 offsets: a wide character contributes one cell, and overlapping matches are reported separately.

`⌘F` / `ctrl-shift-F` opens the selected tab's find bar. The first query asks the host for all available history (`history(from = 1,
count = maxScroll)`), then later queries reuse what has arrived. Matches in the history scroll into view, all matches are highlighted, and
the current match uses a stronger highlight. Enter / the down button goes to the next match; Shift+Enter / the up button goes to the
previous one, wrapping around. Escape closes the bar. Switching tabs or ending the session drops the query and highlights.

The initial slice deliberately stops there: no regex, no search over output the host has not sent, and no cross-tab search. Matches are
refreshed when a history page arrives; live frame damage does not re-run the search, so output that arrives while the bar is open may need
another query or a history update before it is findable. A history update preserves and clamps the current match index instead of jumping
back to the first match.

Manual check:

1. Run `./mill -i app.run`, open a shell, and print enough lines to scroll into history (`seq 1 200`).
2. Press `⌘F` / `ctrl-shift-F`, type a word from an older line, and confirm the viewport moves to it and the current match is
   visually different from the others.
3. Press Enter several times to cycle; press Escape and confirm the bar disappears and the terminal gets the keyboard back.
4. Repeat the search in a second tab, switch tabs, and confirm the first tab's find bar and highlights are gone.
5. Do the same in Pi after producing enough screen output, confirming the search is client-side and does not send anything to Pi.

## Font size

Font size is one runtime preference for the window. `⌘=` / `ctrl-shift-=` increases it, `⌘-` / `ctrl-shift--` decreases it, and
`⌘0` / `ctrl-shift-0` resets it. The preference is bounded to 8–32 px and is not persisted yet.

On a change, the shell asks every mounted `TerminalView` to re-measure on the FX thread, replaces its canvas and painter, keeps the
model, viewport offset and selection, then asks the host for a grid that fits the new metrics. New tabs use the current preference.

Manual check:

1. Open two tabs and print a prompt in each.
2. Change the size once and confirm both tabs re-measure, not only the selected one.
3. Scroll back in one tab, change the size, and confirm the reader stays on the same logical row; the cursor should also stay on the
   same frame cell.
4. Run on a display with a non-integer scale (for example 1.25 or 1.5) and check that cell edges, the cursor and selection stay aligned
   after several size changes.
5. Reset the size and confirm the grid round-trips to the default.
