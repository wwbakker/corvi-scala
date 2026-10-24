# Hyperlinks

Corvi carries explicit OSC 8 links from the host to the renderer as `Cell.link: Option[String]`.

- **Host extraction:** JediTermFX creates a `HyperlinkStyle` whose `LinkInfo` exposes only a `Runnable`. The host stores the URI in a
  `UriLinkInfo(uri)` subclass of `LinkInfo` and attaches it to the style; `ScreenReader` reads `HyperlinkStyle.getLinkInfo` for every cell.
  The engine can derive a new `HyperlinkStyle` after SGR bold/colour changes, and the derived style still carries the same `LinkInfo`, so the
  URI survives. The host re-applies the open link after an SGR reset (`ESC[0m`) and clears it at `setLinkUriFinished`. History rows use the
  same lookup as the visible grid.
- **Scheme policy:** `LinkPolicy.allows` accepts only `http`, `https` and `file`. Unsafe OSC 8 targets remain ordinary styled text, with no
  cell link and no click.
- **Hover and click:** the renderer underlines the link under the pointer and changes the cursor to a hand. `⌘-click` on macOS or
  `ctrl-click` elsewhere invokes the app-supplied opener. The app validates the scheme again before `HostServices.showDocument`, so a
  terminal cannot launch an arbitrary URL handler. Only the primary mouse button opens a link; Cmd/Ctrl + right/middle does not. Hover is
  recomputed on the next pointer move, so output that changes the cell under a stationary pointer can leave the old hover indication until
  that move.
- **Not in this slice:** automatic URL detection in ordinary output, file/line links, editor integration, or opening on plain click.

## Manual check

1. Run a shell command that emits an OSC 8 link, for example:
   `printf '\033]8;;https://example.com\007example\033]8;;\007\n'`
2. Hover the word: it should be underlined and the pointer should become a hand.
3. `⌘`-click on macOS / `ctrl-click` on Linux should open the URL in the platform browser.
4. Repeat with `javascript:alert(1)` and confirm no link underline and no opener call.
5. Check that plain printed URLs without OSC 8 are not clickable; auto-detection is intentionally absent.

The automated suite covers host extraction (`HostProbe`), frame/damage serialization (`ProtocolProbe`), scheme policy (`LinkPolicyTest`), and
renderer hover/click handling (`LinkTest`). A real browser launch is a manual check and was not run in the automated environment.
