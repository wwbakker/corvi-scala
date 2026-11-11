# Reconnect and host loss

The window now supervises its host connection instead of attaching once.

- **Loss signal.** `Client.onConnectionLost` fires once when the socket reader/writer sees `Closed`, or when a command returns `Closed`.
- **Supervisor.** `AppWindow` disposes the old shell views and stops the old client, shows `Host lost; reconnecting. The previous shells
  ended with the host.`, and retries `HostConnection.ensure` with bounded exponential backoff: 250 ms, 500 ms, 1 s, 2 s, then 4 s. The
  last font size chosen in the old shell is remembered and used by the replacement views. Closing while a connection attempt is still in
  flight abandons that client without showing a shell.
- **Reattach.** Each successful attempt lists the replacement host's sessions and opens them. If the replacement has none, the window
  stays on the Overview with its New action; it does not create an empty terminal in the background.
- **Honesty.** Host death is shell death. The status says the previous shells ended with the host; nothing tries to restore a dead shell.
- **Build mismatch is separate.** `HostConnection.connectCurrent` still stops a daemon from another build and starts this build's daemon,
  preserving the socket/token path. That is a build restart, not the connection-loss supervisor.

## Manual checks

1. Run `./mill -i app.run`, open a terminal, and leave a command's output on screen.
2. Kill the daemon (the process named in `host.sock.owner`, or the JVM launched with `-Dcorvi.host.daemon=true`).
3. Confirm the window immediately shows `Host lost; reconnecting. The previous shells ended with the host.`, not the old frame pretending to
   be live.
4. Confirm a replacement daemon is started after a short delay, and the window reaches a shell. With no sessions on the replacement it
   should land on Overview with New; no blank terminal should appear.
5. If practical, start a replacement daemon with a session before the retry and confirm the window reattaches to that session.
6. Stop the replacement repeatedly and confirm the retry delay grows to 4 s rather than spinning; leave the host unavailable and confirm
   the window stays responsive and shows the error.

The automated suite covers the retry policy (`ReconnectPolicyTest`), the one-shot lost signal (`ClientCapabilityTest`), and the window
supervisor with fake clients: empty replacement, successful replacement reattach, and failed-start backoff (`ReconnectTest`). A real-daemon
kill and replacement remains a manual check; it was not run in this automated environment.
