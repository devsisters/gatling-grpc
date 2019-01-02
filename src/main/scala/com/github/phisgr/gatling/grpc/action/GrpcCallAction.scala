package com.github.phisgr.gatling.grpc.action

import com.github.phisgr.gatling.grpc.HeaderPair
import com.github.phisgr.gatling.grpc.check.StatusExtract
import com.github.phisgr.gatling.grpc.protocol.GrpcProtocol
import io.gatling.commons.stats.{KO, OK}
import io.gatling.commons.validation.{Failure, Success, SuccessWrapper, Validation}
import io.gatling.core.action.{Action, ChainableAction}
import io.gatling.core.check.Check
import io.gatling.core.session.Session
import io.gatling.core.stats.message.ResponseTimings
import io.gatling.core.structure.ScenarioContext
import io.gatling.core.util.NameGen
import io.grpc._
import io.grpc.stub.{AbstractStub, MetadataUtils}

import scala.concurrent.ExecutionContextExecutor
import scala.util.Try

case class GrpcCallAction[Req, Res](
  builder: GrpcCallActionBuilder[Req, Res],
  ctx: ScenarioContext,
  next: Action
) extends ChainableAction with NameGen {

  override val name = genName("grpcCall")

  override def execute(session: Session): Unit = {
    type ResolvedH = (Metadata.Key[T], T) forSome {type T}

    val resFV = for {
      resolvedHeaders <- builder.headers.foldLeft[Validation[List[ResolvedH]]](Nil.success) { case (lV, HeaderPair(key, value)) =>
        for {
          l <- lV
          resolvedValue <- value(session)
        } yield (key, resolvedValue) :: l
      }
      resolvedPayload <- builder.payload(session)
    } yield {
      val rawChannel = session(GrpcProtocol.ChannelAttributeName).as[ManagedChannel]
      val channel = if (resolvedHeaders.isEmpty) rawChannel else {
        val headers = new Metadata()
        resolvedHeaders.foreach { case (key, value) => headers.put(key, value) }
        ClientInterceptors.intercept(rawChannel, MetadataUtils.newAttachHeadersInterceptor(headers))
      }
      System.currentTimeMillis() -> builder.method(channel)(resolvedPayload)
    }

    val statsEngine = ctx.coreComponents.statsEngine
    implicit val ec: ExecutionContextExecutor = ctx.coreComponents.actorSystem.dispatcher

    resFV match {
      case Success((start, resF)) =>
        resF.onComplete { t =>
          val endTimestamp = System.currentTimeMillis()

          val resolvedChecks = if (builder.checks.exists(_.checksStatus)) builder.checks else {
            StatusExtract.DefaultCheck :: builder.checks
          }
          val (checkSaveUpdated, checkError) = Check.check(t, session, resolvedChecks)

          val (status, newSession) = if (checkError.isEmpty) {
            (OK, checkSaveUpdated)
          } else {
            (KO, checkSaveUpdated.markAsFailed)
          }

          statsEngine.logResponse(
            newSession,
            builder.requestName,
            startTimestamp = start,
            endTimestamp = endTimestamp,
            status = status,
            responseCode = StatusExtract.extractStatus(t) match {
              case Success(value) => Some(value.getCode.toString)
              case Failure(_) => None
            },
            message = checkError.map(_.message)
          )
          next ! newSession

        }
      case Failure(message) =>
        statsEngine.reportUnbuildableRequest(session, builder.requestName, message)
        next ! session.markAsFailed
    }

  }
}
