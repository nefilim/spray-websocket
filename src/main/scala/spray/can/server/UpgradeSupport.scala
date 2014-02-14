package spray.can.server

import akka.actor.ActorRef
import akka.actor.ExtendedActorSystem
import akka.actor.ExtensionKey
import akka.actor.Props
import akka.io.Tcp
import spray.can.Http
import spray.can.HttpExt
import spray.can.HttpManager
import spray.http._
import spray.io._
import spray.can.client.{UpgradableHttpClientSettingsGroup, ClientConnectionSettings}
import spray.can.websocket.{HandshakeResponseEvent, HandshakeSuccess, HandshakeResponse}
import akka.util.{ByteString, CompactByteString}
import scala.annotation.tailrec
import spray.can.parsing._
import spray.http.HttpHeaders.{`Transfer-Encoding`, `Content-Type`, `Content-Length`}
import spray.http.HttpRequest
import scala.Some
import spray.http.HttpResponse

object UpgradeSupport {

  def apply[C <: SslTlsContext](settings: ServerSettings)(defaultStage: RawPipelineStage[C]) = new RawPipelineStage[C] {
    def apply(context: C, commandPL: CPL, eventPL: EPL): Pipelines = new DynamicPipelines {
      become(defaultState)

      def defaultState = new State {
        val defaultPipelines = defaultStage(context, commandPL, eventPL)
        val cpl = defaultPipelines.commandPipeline

        val commandPipeline: CPL = {
          case UHttp.Upgrade(pipelineStage, resp) ⇒
            resp foreach (x ⇒ cpl(Http.MessageCommand(x)))
            val upgradedPipelines = upgradedState(pipelineStage(settings)(context, commandPL, eventPL))
            become(upgradedPipelines)

            upgradedPipelines.eventPipeline(UHttp.Upgraded)

          case cmd ⇒ cpl(cmd)
        }

        val eventPipeline = defaultPipelines.eventPipeline
      }

      def upgradedState(pipelines: Pipelines) = new State {
        val commandPipeline = pipelines.commandPipeline
        val epl = pipelines.eventPipeline

        val eventPipeline: EPL = {
          case ev: AckEventWithReceiver ⇒ // rounding-up works, just drop it
          case ev                       ⇒ epl(ev)
        }
      }

    }
  }

  def apply[C <: PipelineContext](settings: ClientConnectionSettings)(defaultStage: RawPipelineStage[C]) = new RawPipelineStage[C] {
    def apply(context: C, commandPL: CPL, eventPL: EPL): Pipelines = new DynamicPipelines {
      become(defaultState)

      def defaultState = new State {
        val defaultPipelines = defaultStage(context, commandPL, eventPL)
        val cpl = defaultPipelines.commandPipeline
        val epl = defaultPipelines.eventPipeline

        val commandPipeline: CPL = {
          case UHttp.UpgradeClient(pipelineStage, req) ⇒
            req foreach (x ⇒ cpl(Http.MessageCommand(x)))
            become(upgradingState(defaultPipelines, pipelineStage(settings)))

          case cmd ⇒ cpl(cmd)
        }

        val eventPipeline = epl
      }

      def upgradingState(defaultPipelines: Pipelines, upgradedPipelines: HandshakeSuccess => PipelineStage) = new State {
        var remainingData: Option[ByteString] = None
        var parser: Parser = new HttpResponsePartParser(settings.parserSettings)(){
          override def parseEntity(headers: List[HttpHeader], input: ByteString, bodyStart: Int, clh: Option[`Content-Length`],
                                   cth: Option[`Content-Type`], teh: Option[`Transfer-Encoding`], hostHeaderPresent: Boolean,
                                   closeAfterResponseCompletion: Boolean): Result = {
            remainingData = Some(input.drop(bodyStart))

            emit(message(headers, HttpEntity.Empty), closeAfterResponseCompletion) {Result.IgnoreAllFurtherInput}
          }
          setRequestMethodForNextResponse(HttpMethods.GET)
        }

        @tailrec def handle(result: Result): Unit = result match {
          case Result.NeedMoreData(next) =>
            parser = next
          case Result.Emit(part, closeAfterResponseCompletion, continue) =>
            eventPipeline(HandshakeResponseEvent(part, remainingData.get))
            handle(continue())
          case Result.Expect100Continue(continue) =>
            handle(continue())
          case Result.ParsingError(status, info) =>
            defaultPipelines.commandPipeline(Http.Close)
          case Result.IgnoreAllFurtherInput =>
        }

        val commandPipeline: CPL = {
          case cmd ⇒ defaultPipelines.commandPipeline(cmd)
        }

        val eventPipeline: EPL = {
          case Tcp.Received(data: CompactByteString) => handle(parser(data))
          case HandshakeResponseEvent(HandshakeResponse(state), data) => {
            val pipelines = upgradedState(upgradedPipelines(state)(context, commandPL, eventPL))
            become(pipelines)
            pipelines.eventPipeline(UHttp.Upgraded)
            pipelines.eventPipeline(Tcp.Received(data))
          }
          case event => defaultPipelines.eventPipeline(event)
        }
      }

      def upgradedState(pipelines: Pipelines) = new State {
        val commandPipeline = pipelines.commandPipeline
        val epl = pipelines.eventPipeline

        val eventPipeline: EPL = {
          case ev: AckEventWithReceiver ⇒ // rounding-up works, just drop it
          case ev                       ⇒ epl(ev)
        }
      }
    }
  }
}

/**
 * UpgradableHttp extension
 *
 * UHttp, UHttpExt and UHttpManager is for getting HttpListener
 * to be replaced by UpgradableHttpListener only.
 *
 * Usage:
 *    IO(UHttp) ! Http.Bind
 * instead of:
 *    IO(Http) ! Http.Bind
 */
object UHttp extends ExtensionKey[UHttpExt] {
  // upgrades -- clould be in spray.can.Http, since Upgrade is part of HTTP spec.
  case class Upgrade(pipelineStage: ServerSettings ⇒ RawPipelineStage[SslTlsContext], resp: Option[HttpResponse]) extends Tcp.Command
  case class UpgradeClient(pipelineStage: ClientConnectionSettings => HandshakeSuccess => RawPipelineStage[PipelineContext], req: Option[HttpRequest]) extends Tcp.Command
  case object Upgraded extends Tcp.Event
}

class UHttpExt(system: ExtendedActorSystem) extends HttpExt(system) {
  override val manager = system.actorOf(
    props = Props(new UHttpManager(Settings)) withDispatcher Settings.ManagerDispatcher,
    name = "IO-UHTTP") // should be unique name
}

private class UHttpManager(httpSettings: HttpExt#Settings) extends HttpManager(httpSettings) {

  override def newHttpListener(commander: ActorRef, bind: Http.Bind, httpSettings: HttpExt#Settings) = {
    new UpgradableHttpListener(commander, bind, httpSettings)
  }

  override def newHttpClientSettingsGroup(settings: ClientConnectionSettings, httpSettings: HttpExt#Settings) = {
    new UpgradableHttpClientSettingsGroup(settings, httpSettings)
  }
}

