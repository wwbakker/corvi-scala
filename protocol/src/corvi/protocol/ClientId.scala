package corvi.protocol

/** Who a client is, as far as the host's push registrations are concerned.
  *
  * Damage is pushed per client because a client only sees the sessions it attached to; with one transport per client (the shape the
  * Unix-socket phase will use) the host binds a handler per id and the subscription is simply the set of sessions that handler attached to.
  */
opaque type ClientId = String

object ClientId:
  def apply(value: String): ClientId = value

  extension (id: ClientId) def value: String = id

  given CanEqual[ClientId, ClientId] = CanEqual.derived
