import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, RequestTimeoutException, Uri}
import akka.pattern.after
import akka.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult, ThrottleMode}
import akka.stream.scaladsl._

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object Client extends App {

  type Pool = Flow[(HttpRequest, (Future[Done], Promise[HttpResponse])), (Try[HttpResponse], (Future[Done], Promise[HttpResponse])), HostConnectionPool]
  type Queue = SourceQueueWithComplete[(HttpRequest, (Future[Done], Promise[HttpResponse]))]

  implicit val actorSystem = ActorSystem("client")
  implicit val executionContext = actorSystem.dispatcher
  implicit val materializer = ActorMaterializer()

  val connectionPool: Pool = Http().cachedHostConnectionPool("localhost", port = 8080)
  val queue: Queue =
    Source
      .queue[(HttpRequest, (Future[Done], Promise[HttpResponse]))](bufferSize = 8, overflowStrategy = OverflowStrategy.dropNew)
      .filterNot { case (_, (timedOut, _)) =>
          timedOut.isCompleted
      }
      .via(connectionPool)
      .toMat(Sink.foreach {
        case (Success(response), (_, promise)) => promise.success(response)
        case (Failure(reason), (_, promise)) => promise.failure(reason)
      })(Keep.left)
      .run()

  def performRequest(n: Int): Unit = {
    println(s"$n: Sending request")
    val request = HttpRequest(uri = Uri(s"/test/$n"))
    val timedOut = Promise[Done]
    val responseFuture: Future[HttpResponse] = {
      val promise = Promise[HttpResponse]
      Try(queue.offer(request -> (timedOut.future, promise))) match {
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

    val timeout = after(190.millis, actorSystem.scheduler) {
      // We need to consume (discard) the response when it eventually arrives
      // NOTE: Here we could should somehow cancel the whole request
      responseFuture.onComplete {
        case Success(response) =>
          // Discarding succeeded timed out request
          response.entity.discardBytes()
        case Failure(_) =>
        // Ignoring failed timed out request
      }
      timedOut.success(Done)
      Future.failed[HttpResponse](RequestTimeoutException(request, "Request level completion timeout"))

    }
    val guardedResponse = Future.firstCompletedOf(Seq(responseFuture, timeout))

    guardedResponse.onComplete {
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
