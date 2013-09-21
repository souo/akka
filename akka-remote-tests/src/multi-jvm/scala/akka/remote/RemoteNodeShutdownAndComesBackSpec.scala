/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.remote.testconductor.RoleName
import akka.remote.transport.ThrottlerTransportAdapter.{ForceDisassociate, Direction}
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.actor.ActorIdentity
import akka.remote.testconductor.RoleName
import akka.actor.Identify
import scala.concurrent.Await

object RemoteNodeShutdownAndComesBackSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("""
      akka.loglevel = INFO
      akka.remote.log-remote-lifecycle-events = INFO
      #akka.remote.retry-gate-closed-for = 0.5 s
      akka.remote.watch-failure-detector.acceptable-heartbeat-pause = 60 s
      akka.remote.gate-invalid-addresses-for = 0.5 s
                              """)))

  testTransport(on = true)

  class Subject extends Actor {
    def receive = {
      case "shutdown" => context.system.shutdown()
      case msg ⇒ sender ! msg
    }
  }

}

class RemoteNodeShutdownAndComesBackMultiJvmNode1 extends RemoteNodeShutdownAndComesBackSpec
class RemoteNodeShutdownAndComesBackMultiJvmNode2 extends RemoteNodeShutdownAndComesBackSpec

abstract class RemoteNodeShutdownAndComesBackSpec
  extends MultiNodeSpec(RemoteNodeShutdownAndComesBackSpec)
          with STMultiNodeSpec with ImplicitSender {

  import RemoteNodeShutdownAndComesBackSpec._

  override def initialParticipants = roles.size

  def identify(role: RoleName, actorName: String): ActorRef = {
    system.actorSelection(node(role) / "user" / actorName) ! Identify(actorName)
    expectMsgType[ActorIdentity].ref.get
  }

  "RemoteNodeShutdownAndComesBack" must {

    "properly reset system message buffer state when new system with same Address comes up" taggedAs LongRunningTest in {
      runOn(first) {
        val secondAddress = node(second).address
        system.actorOf(Props[Subject], "subject1")
        enterBarrier("actors-started")

        val subject = identify(second, "subject")
        val sysmsgBarrier = identify(second, "sysmsgBarrier")

        // Prime up the system message buffer
        watch(subject)
        enterBarrier("watch-established")

        // Wait for proper system message propagation
        // (Using a helper actor to ensure that all previous system messages arrived)
        watch(sysmsgBarrier)
        system.stop(sysmsgBarrier)
        expectTerminated(sysmsgBarrier)

        // Drop all messages from this point so no SHUTDOWN is ever received
        testConductor.blackhole(second, first, Direction.Send).await
        // Shut down all existing connections so that the system can enter recovery mode (association attempts)
        Await.result(RARP(system).provider.transport.managementCommand(ForceDisassociate(node(second).address)), 3.seconds)

        // Trigger reconnect attempt and also queue up a system message to be in limbo state (UID of remote system
        // is unknown, and system message is pending)
        system.stop(subject)
        subject ! "hello"
        subject ! "hello"
        subject ! "hello"

        // Get rid of old system -- now SHUTDOWN is lost
        testConductor.shutdown(second).await
        expectTerminated(subject, 10.seconds)

        // At this point the second node is restarting, while the first node is trying to reconnect without resetting
        // the system message send state

        // Now wait until second system becomes alive again
        within(30.seconds) {
          // retry because the Subject actor might not be started yet
          awaitAssert {
            system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! "echo"
            expectMsg(1.second, "echo")
          }
        }

        // Establish watch with the new system. This triggers additional system message traffic. If buffers are out
        // of synch the remote system will be quarantined and the rest of the test will fail (or even in earlier
        // stages depending on circumstances).
        system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! Identify("subject")
        val subjectNew = expectMsgType[ActorIdentity].ref.get
        watch(subjectNew)

        subjectNew ! "shutdown"
        expectTerminated(subjectNew)
      }

      runOn(second) {
        val addr = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        system.actorOf(Props[Subject], "subject")
        system.actorOf(Props[Subject], "sysmsgBarrier")
        val path = node(first)
        enterBarrier("actors-started")

        enterBarrier("watch-established")

        system.awaitTermination(30.seconds)

        val freshSystem = ActorSystem(system.name, ConfigFactory.parseString(s"""
                    akka.remote.netty.tcp {
                      hostname = ${addr.host.get}
                      port = ${addr.port.get}
                    }
                    """).withFallback(system.settings.config))
        freshSystem.actorOf(Props[Subject], "subject")


        freshSystem.awaitTermination(30.seconds)
      }

    }

  }
}