package corvi

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}
import javafx.application.Platform

/** Bridging a probe's own thread to the FX thread. The probes observe the UI from outside it, so every read and every call has to hop, and
  * a stuck FX thread has to fail rather than hang.
  */
private[corvi] object FxProbe:
  /** How long any one hop onto the FX thread may take before the probe gives up. */
  private val hopSeconds: Long = 30

  /** Runs `body` on the FX thread and returns its result. A stuck FX thread is a failure, not a null result.
    */
  def onFx[A](body: => A): A =
    val result = new AtomicReference[A]()
    val failure = new AtomicReference[Throwable]()
    val done = new CountDownLatch(1)
    Platform.runLater(() =>
      try result.set(body)
      catch case throwable: Throwable => failure.set(throwable)
      finally done.countDown()
    )
    if !done.await(hopSeconds, TimeUnit.SECONDS) then throw new IllegalStateException("the JavaFX thread did not answer within the timeout")
    Option(failure.get).foreach(throwable => throw throwable)
    result.get

  /** Polls until `what` holds, or the timeout runs out. */
  def awaitTrue(what: => Boolean, seconds: Long): Boolean =
    val deadline = System.nanoTime() + seconds * 1_000_000_000L
    var ok = what
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(50)
      ok = what
    ok

  /** Busies the FX thread, so anything marshalled while it runs stays queued. */
  def occupyFxThread(seconds: Long): Unit =
    val busy = new CountDownLatch(1)
    val _ = Platform.runLater(() =>
      busy.countDown()
      Thread.sleep(seconds * 1000)
    )
    val _ = busy.await(hopSeconds, TimeUnit.SECONDS)
