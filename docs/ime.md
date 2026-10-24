# IME notes

Corvi implements the two IME stages the plan describes:

- **Commit-only:** `InputMethodEvent.getCommitted` goes through `session.text`, exactly like typed text. The host never sees the event
  type; it receives the committed string on the normal input route.
- **Inline composition:** `InputMethodEvent.getComposed` is kept in the renderer as a `Preedit` and painted as a canvas overlay at the
  terminal cursor. It has a translucent background and an underline, is split by grapheme cluster, and gives East Asian wide characters and
  emoji two cells. A wide cluster wraps to the next row when it would cross the right edge. The host grid and the model's cursor are not
  modified; an empty composition or a commit removes the overlay.

The view exposes JavaFX `InputMethodRequests` with the terminal cursor's screen location as the candidate-window anchor. The interface's
screen-point-to-offset lookup returns `0`, `cancelLatestCommittedText` is a no-op, and `getSelectedText` always returns `null` — even when
the terminal has a text selection — because the host owns the editable text and the renderer cannot safely reverse-map or undo it. The
composition caret is drawn from `InputMethodEvent.getCaretPosition`; an offset inside a cluster points at that cluster's first cell.

## Manual checks

These require a desktop with a system input method; the automated environment used for this change has no IME configured, so the real-IME
manual pass is **pending** and only the synthetic tests below were run here.

### macOS

1. Add a Japanese input source (System Settings → Keyboard → Input Sources), then select Hiragana.
2. Run `./mill -i app.run` and focus a terminal.
3. Type `nihon`; the preedit should appear at the cursor over the grid, with no shell input yet. The candidate window should be near the
   cursor cell.
4. Convert and commit. The overlay disappears and the shell receives the committed text, not the romaji composition.
5. Repeat with a wide character (e.g. `日本`) and check the overlay covers two cells per character.
6. Press Escape during composition and check the overlay clears without sending the preedit.

### Linux

1. Start the session's input method (for example `ibus-daemon -drx` or fcitx5) and select Japanese/Chinese.
2. Ensure the toolkit sees the IM variables for the session (`GTK_IM_MODULE` / `XMODIFIERS` as appropriate).
3. Run `./mill -i app.run`, focus a terminal, and repeat the macOS steps: preedit only at the cursor, commit sends only committed text,
   wide characters occupy two cells, candidate window near the cursor.

## Synthetic checks

`KeyTest` fires `InputMethodEvent`s directly:

- committed `日本語🙂` reaches `session.text` as one string;
- composed `にほん` sends nothing, appears in `TerminalView.composition`, and a later commit `日本` sends only `日本` and clears the
  overlay.

`PreeditTest` checks grapheme clustering, combining marks, wide/emoji widths, wrapping at the right edge and the bottom-row cutoff.

## Limits

- Preedit text-run highlights (`InputMethodTextRun.getHighlight`) are not drawn; the whole composition uses one style.
- Candidate-window placement is cursor-cell based, not character-offset based.
- `cancelLatestCommittedText` and `getLocationOffset` are inert.
- Preedit is not sent to the host, so it cannot be copied, searched or reflowed; it is chrome over the current frame.
