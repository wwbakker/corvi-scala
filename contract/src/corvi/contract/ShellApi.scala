package corvi.contract

/** A terminal on screen, as the shell needs it: a node, the keyboard, a lifetime, and the two signals that change a tab.
  *
  * Effect-free and toolkit-free on purpose — `N` is whatever node the implementation's toolkit has, and every callback runs on the UI
  * thread. The shell is then pure JavaFX over plain callbacks, and a probe can hand it a fake view.
  */
private[corvi] trait TerminalView[N]:

  /** What to mount. All views stay mounted; the shell shows one at a time. */
  def node: N

  def focus(): Unit

  /** Output arrived. The shell decides, from its own selection, whether that is a bell. */
  def onActivity(listener: () => Unit): Unit

  /** The shell behind it exited. */
  def onEnded(listener: Ended => Unit): Unit

  /** Releases the client side of the view. The host and the shell are not touched. */
  def dispose(): Unit

/** Where the shell's views come from: the host's sessions, one view each, and the two operations a tab needs beyond showing a session. */
private[corvi] trait TerminalViewFactory[N]:

  /** The view for a session the host already has — a reattach after a UI restart, or the first window of a fresh UI. */
  def open(info: SessionInfo, onActivity: () => Unit, onEnded: Ended => Unit): TerminalView[N]

  /** Asks the host for a new session at this size and hands back what it made, or `None` when it refused. */
  def create(size: TermSize, onCreated: Option[SessionInfo] => Unit): Unit

  /** The grid a view from this factory shows in an area that many pixels wide and high: what a new session is created with, so the host's
    * first frame already fits the window instead of being resized into it. An area that has not been laid out yet gives the default grid.
    */
  def gridFor(width: Double, height: Double): TermSize

  /** Ends the session behind a closed tab, which is what closing a tab means in Corvi. */
  def close(info: SessionInfo): Unit
