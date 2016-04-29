package aecor.core.process

import aecor.core.entity.{CommandContract, EntityActor, EntityRef}
import aecor.core.logging.PersistentActorLogging
import aecor.core.message.{Deduplication, Message, MessageId, Passivation}
import aecor.core.process.ProcessAction.{CompoundAction, _}
import aecor.core.process.ProcessActor.CommandDelivered
import aecor.core.process.ProcessActor.PersistentMessage.{CommandAccepted, CommandRejected, EventEnvelope}
import aecor.util.{FunctionBuilder, FunctionBuilderSyntax}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}

import scala.concurrent.duration._
import scala.reflect.ClassTag

sealed trait ProcessAction[+In] {
  def and[In0 >: In](that: ProcessAction[In0]): ProcessAction[In0] = {
    CompoundAction(this, that)
  }
}
object ProcessAction {
  case class ChangeState[In](f: In => ProcessAction[In]) extends ProcessAction[In]
  case class DeliverCommand[In, Entity, Rejection, Command](destination: EntityRef[Entity], command: Command, rejectionHandler: Rejection => ProcessAction[In]) extends ProcessAction[In]
  case class CompoundAction[In](l: ProcessAction[In], r: ProcessAction[In]) extends ProcessAction[In]
  case object DoNothing extends ProcessAction[Nothing]
}

trait ProcessSyntax extends FunctionBuilderSyntax {
  final def become[H, In](f: H)(implicit ev: FunctionBuilder[H, In, ProcessAction[In]]): ProcessAction[In] = ChangeState(ev(f))
  trait DeliverCommand[R] {
    def handlingRejection[In](f: R => ProcessAction[In]): ProcessAction[In]
    def ignoringRejection[In]: ProcessAction[In]
  }

  implicit class EntityRefOps[Entity](er: EntityRef[Entity]) {
    final def deliver[Command](command: Command)(implicit contract: CommandContract[Entity, Command]) =
      new DeliverCommand[contract.Rejection] {
        def handlingRejection[In](f: contract.Rejection => ProcessAction[In]): ProcessAction[In] = DeliverCommand(er, command, f)
        def ignoringRejection[In]: ProcessAction[In] = DeliverCommand(er, command, {_: Any => DoNothing})
      }
  }

  final def doNothing[E]: ProcessAction[E] = DoNothing
  final def when[A] = new At[A] {
    override def apply[Out](f: (A) => Out): (A) => Out = f
  }
  def ignore[E]: Any => ProcessAction[E] = _ => doNothing
}

object ProcessActor {
  type ProcessBehavior[E] = E => ProcessAction[E]

  case class CommandDelivered(deliveryId: Long)

  sealed trait PersistentMessage
  object PersistentMessage {
    case class EventEnvelope[E](id: MessageId, event: E) extends PersistentMessage
    case class CommandRejected(rejection: Any, deliveryId: Long) extends PersistentMessage
    case class CommandAccepted(deliveryId: Long) extends PersistentMessage
  }
}

class ProcessActor[E: ClassTag](name: String, initial: E => ProcessAction[E], idleTimeout: FiniteDuration)
  extends PersistentActor
    with AtLeastOnceDelivery
    with Deduplication
    with Passivation
    with PersistentActorLogging {

  context.setReceiveTimeout(idleTimeout)

  override def persistenceId: String = name + "-" + self.path.name

  private var state = initial

  override def receiveRecover: Receive =  {
    case EventEnvelope(id, event: E) =>
      val reaction = state(event)
      runReaction(reaction)
      confirmProcessed(id)
    case CommandRejected(rejection, deliveryId) =>
      commandRejected(rejection, deliveryId)
    case CommandAccepted(deliveryId) =>
      confirmDelivery(deliveryId)
      ()
  }

  override def receiveCommand: Receive = receivePassivationMessages.orElse {
    case Message(id, event: E, deliveryAck) =>
      if (isProcessed(id)) {
        sender() ! deliveryAck
        log.debug("Received duplicate event [{}]", event)
      } else {
        persist(EventEnvelope(id, event)) { em =>
          val reaction = state(event)
          log.debug("Processed event [{}] with reaction [{}]", event, reaction)
          runReaction(reaction)
          confirmProcessed(id)
          sender() ! deliveryAck
        }
      }

    case EntityActor.Response(CommandDelivered(deliveryId), result) => result match {
      case EntityActor.Rejected(rejection) =>
        log.debug("Command [{}] rejected [{}]", deliveryId, rejection)
        persist(CommandRejected(rejection, deliveryId)) { _ =>
          commandRejected(rejection, deliveryId)
        }
      case EntityActor.Accepted =>
        log.debug("Command [{}] accepted", deliveryId)
        persist(CommandAccepted(deliveryId)) { e =>
          confirmDelivery(deliveryId)
          ()
        }
    }
  }

  private def runReaction(reaction: ProcessAction[E]): Unit = reaction match {
    case ChangeState(newState) =>
      state = newState
    case DeliverCommand(destination, command, rejectionHandler) =>
      deliverCommand(destination, command, rejectionHandler)
    case CompoundAction(l, r) =>
      runReaction(l)
      runReaction(r)
    case DoNothing =>
      ()
  }

  type RejectionHandler = Any => ProcessAction[E]

  val rejectionHandlers = scala.collection.mutable.Map.empty[Long, RejectionHandler]

  def deliverCommand[A, C, R](destination: EntityRef[A], command: C, rejectionHandler: R => ProcessAction[E]): Unit = {
    deliver(destination.actorRef.path) { deliveryId =>
      rejectionHandlers.update(deliveryId, rejection => rejectionHandler(rejection.asInstanceOf[R]))
      Message(command, CommandDelivered(deliveryId))
    }
  }

  def commandRejected(rejection: Any, deliveryId: Long): Unit = {
    rejectionHandlers.get(deliveryId).map { handler =>
      handler(rejection)
    }.foreach { reaction =>
      log.debug("Handling rejection [{}] of command [{}] with reaction [{}]", rejection, deliveryId, reaction)
      runReaction(reaction)
    }
    confirmDelivery(deliveryId)
    ()
  }

  override def confirmDelivery(deliveryId: Long): Boolean = {
    rejectionHandlers - deliveryId
    super.confirmDelivery(deliveryId)
  }

  override def passivationThreshold: FiniteDuration = 60.seconds

  override def shouldPassivate: Boolean = numberOfUnconfirmed == 0
}
