import akka.Done
import akka.actor.ActorSystem
import akka.pattern.after
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, RequestTimeoutException, Uri}
import akka.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed}
import akka.stream.scaladsl._
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult, ThrottleMode}
import org.reactivestreams.{Subscriber, Subscription}

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object Client extends App {

  type Queue = SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])]

  implicit val actorSystem = ActorSystem("client")
  implicit val executionContext = actorSystem.dispatcher
  implicit val materializer = ActorMaterializer()

  val bufferSize = 8
  val timeout = 190.millis
  val queue: Queue =
    Source
      .queue[(HttpRequest, Promise[HttpResponse])](bufferSize, overflowStrategy = OverflowStrategy.dropNew)
      .toMat(Sink.foreach[(HttpRequest, Promise[HttpResponse])] {
        case (request, responsePromise) =>
          val connectionFlow = Http()
            .outgoingConnection(request.uri.authority.host.toString(), request.uri.effectivePort)

          val responseFuture = Source
            .single(request)
            .via(connectionFlow)
            .idleTimeout(timeout)
            .runWith(Sink.head)

          responsePromise.tryCompleteWith(responseFuture)
      })(Keep.left)
      .run()

  def performRequest(n: Int): Unit = {
    println(s"$n: Sending request")
    val request = HttpRequest(uri = Uri(s"http://localhost:8080/test/$n"))
    val timedOut = Promise[Done]
    val responseFuture: Future[HttpResponse] = {
      val promise = Promise[HttpResponse]
      val now = System.currentTimeMillis()
      Try(queue.offer(request -> promise)) match {
        case Success(offerResult) =>
          offerResult.flatMap {
            case Enqueued =>
              promise.future
            case Dropped =>
              Future.failed(new RuntimeException("Queue is full"))
            case QueueOfferResult.Failure(reason) =>
              Future.failed(reason)
            case QueueClosed =>
              Future.failed(new RuntimeException("Queue has been closed"))
          }
        case Failure(reason) =>
          Future.failed(reason)
      }
    }

    responseFuture.onComplete {
      case Success(response) =>
        response.discardEntityBytes()
        println(s"$n: Got response")
      case Failure(reason) =>
        println(s"$n: Failed: $reason")
    }
  }

  Source(1 to 1000)
    .throttle(5, 1.second, 5, ThrottleMode.shaping)
    .runForeach(performRequest)
}
