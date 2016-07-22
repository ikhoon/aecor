package aecor.tests

import java.time.Instant

import aecor.core.aggregate.serialization.PersistentEntityEventEnvelopeCodec
import aecor.core.aggregate.{AggregateEventEnvelope, CommandId, EventId}
import akka.actor.ExtendedActorSystem

class PersistentEntityEventEnvelopeCodecSpec extends AkkaSpec {

  val codec = new PersistentEntityEventEnvelopeCodec(system.asInstanceOf[ExtendedActorSystem])

  "PersistentEntityEventEnvelopeCodec" must {
    "be able to encode/decode PersistentEntityEventEnvelope" in {
      val obj = AggregateEventEnvelope(EventId("id"), null, Instant.now(), CommandId("causedBy"))
      val blob = codec.encode(obj)
      val ref = codec.decode(blob, codec.manifest(obj))
      ref.get shouldEqual obj
    }
  }
}