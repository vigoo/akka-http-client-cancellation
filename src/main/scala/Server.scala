import akka.Done
import akka.actor.ActorSystem
import akka.pattern.after
import akka.http.scaladsl.Http
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.StdIn

object Server extends App {
  implicit val actorSystem = ActorSystem("service")
  implicit val ec = actorSystem.dispatcher
  implicit val materializer = ActorMaterializer()

  val route =
    path("test" / IntNumber) { n =>
      withoutRequestTimeout {
        get {
          val duration =
            if ((n % 2) == 0) {
              50.millis
            } else {
              1.hour
            }
          println(s"Request $n, responding in $duration")

          val responseFuture = after(duration, actorSystem.scheduler) {
            Future.successful(Done)
          }
          onComplete(responseFuture) { _ =>
            complete(n.toString)
          }
        }
      }
    }

  val bindingFuture = Http().bindAndHandle(route, "0.0.0.0", 8080)

  println("Service is running")
  StdIn.readLine()

  bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ ⇒ actorSystem.terminate())
}
