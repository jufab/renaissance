package org.renaissance.twitter.finagle

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.twitter.finagle.ListeningServer
import com.twitter.finagle._
import com.twitter.finagle.http._
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.io.Buf
import com.twitter.util.Await
import com.twitter.util.Future
import java.net.InetSocketAddress
import java.util.Date
import org.renaissance.Config
import org.renaissance.License
import org.renaissance.RenaissanceBenchmark

class FinagleHttp extends RenaissanceBenchmark {

  def description =
    "Sends many small Finagle HTTP requests to a Finagle HTTP server, and awaits the response."

  override def defaultRepetitions = 12

  def licenses = License.create(License.APACHE2)

  /** Number of requests sent during the execution of the benchmark.
   */
  val NUM_REQUESTS = 2000000

  /** Number of clients that are simultaneously sending the requests.
   */
  val NUM_CLIENTS = 20

  var server: ListeningServer = null

  var port: Int = -1

  override def setUpBeforeAll(c: Config): Unit = {
    val mapper: ObjectMapper = new ObjectMapper().registerModule(DefaultScalaModule)
    val helloWorld: Buf = Buf.Utf8("Hello, World!")
    val muxer: HttpMuxer = new HttpMuxer()
      .withHandler(
        "/json",
        Service.mk { req: Request =>
          val rep = Response()
          rep.content =
            Buf.ByteArray.Owned(mapper.writeValueAsBytes(Map("message" -> "Hello, World!")))
          rep.contentType = "application/json"
          Future.value(rep)
        }
      )
      .withHandler("/plaintext", Service.mk { req: Request =>
        val rep = Response()
        rep.content = helloWorld
        rep.contentType = "text/plain"
        Future.value(rep)
      })

    val serverAndDate: SimpleFilter[Request, Response] =
      new SimpleFilter[Request, Response] {
        private[this] val addServerAndDate: Response => Response = { rep =>
          rep.server = "Finagle"
          rep.date = new Date()
          rep
        }

        def apply(req: Request, s: Service[Request, Response]): Future[Response] =
          s(req).map(addServerAndDate)
      }

    server = com.twitter.finagle.Http.server
      .withCompressionLevel(0)
      .withStatsReceiver(NullStatsReceiver)
      .withTracer(NullTracer)
      .serve(s":0", serverAndDate.andThen(muxer))
    port = server.boundAddress.asInstanceOf[InetSocketAddress].getPort
  }

  override def tearDownAfterAll(c: Config): Unit = {
    server.close()
  }

  override def runIteration(c: Config): Unit = {
    var totalLength = 0L
    for (i <- 0 until NUM_CLIENTS) {
      val clientThread = new Thread {
        override def run(): Unit = {
          val client: Service[http.Request, http.Response] =
            com.twitter.finagle.Http.newService(s"localhost:$port")
          val request = http.Request(http.Method.Get, "/json")
          request.host = s"localhost:$port"
          val response: Future[http.Response] = client(request)
          for (i <- 0 until NUM_REQUESTS) {
            Await.result(response.onSuccess { rep: http.Response =>
              totalLength += rep.content.length
            })
          }
          client.close()
        }
      }
      clientThread.start()
      clientThread.join()
    }
    blackHole(totalLength)
  }
}