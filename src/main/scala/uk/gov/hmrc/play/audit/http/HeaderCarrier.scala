/*
 * Copyright 2015 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.play.audit.http

import play.api.mvc.{Headers, Session}
import uk.gov.hmrc.play.audit.EventKeys
import uk.gov.hmrc.play.audit.http.connector.AuditProvider
import uk.gov.hmrc.play.http.logging._
import uk.gov.hmrc.play.http.{HeaderNames, SessionKeys}

import scala.util.Try

case class UserId(value: String) extends AnyVal

case class Token(value: String) extends AnyVal

case class HeaderCarrier(authorization: Option[Authorization] = None,
                         userId: Option[UserId] = None,
                         token: Option[Token] = None,
                         forwarded: Option[ForwardedFor] = None,
                         sessionId: Option[SessionId] = None,
                         requestId: Option[RequestId] = None,
                         requestChain: RequestChain = RequestChain.init,
                         nsStamp: Long = System.nanoTime(),
                         extraHeaders: Seq[(String, String)] = Seq()) extends LoggingDetails with HeaderProvider with AuditProvider {

  import EventKeys._

  /**
   * @return the time, in nanoseconds, since this header carrier was created
   */
  def age = System.nanoTime() - nsStamp

  val names = HeaderNames

  lazy val headers: Seq[(String, String)] = {
    List(requestId.map(rid => names.xRequestId -> rid.value),
      sessionId.map(sid => names.xSessionId -> sid.value),
      forwarded.map(f => names.xForwardedFor -> f.value),
      token.map(t => names.token -> t.value),
      Some(names.xRequestChain -> requestChain.value),
      authorization.map(auth => names.authorisation -> auth.value)).flatten.toList ++ extraHeaders
  }

  def withExtraHeaders(headers:(String, String)*) : HeaderCarrier = {
    this.copy(extraHeaders = extraHeaders ++ headers)
  }

  private lazy val auditTags = Map[String, String](
    names.xRequestId -> requestId.map(_.value).getOrElse("-"),
    names.xSessionId -> sessionId.map(_.value).getOrElse("-")
  )

  private lazy val auditDetails = Map[String, String](
    "ipAddress" -> forwarded.map(_.value).getOrElse("-"),
    names.authorisation -> authorization.map(_.value).getOrElse("-"),
    names.token -> token.map(_.value).getOrElse("-")
  )

  def toAuditTags(transactionName: String, path: String) = {
    auditTags ++ Map[String, String](
      TransactionName -> transactionName,
      Path -> path
    )
  }

  def toAuditDetails(details: (String, String)*) = auditDetails ++ details
}

object HeaderCarrier {
  def fromHeaders(headers: Headers) = {
    val authorization = headers.get(HeaderNames.authorisation).map(Authorization)
    val token = headers.get(HeaderNames.token).map(Token)
    val forwardedFor = headers.get(HeaderNames.xForwardedFor).map(ForwardedFor)
    val sessionId = headers.get(HeaderNames.xSessionId).map(SessionId)

    val requestTimestamp = Try[Long] {
      headers.get(HeaderNames.xRequestTimestamp).map(_.toLong).getOrElse(System.nanoTime())
    }.toOption

    val requestId = headers.get(HeaderNames.xRequestId).map(RequestId)

    new HeaderCarrier(authorization, None, token, forwardedFor, sessionId, requestId, buildRequestChain(headers.get(HeaderNames.xRequestChain)), requestTimestamp.getOrElse(System.nanoTime()))
  }

  def fromSessionAndHeaders(session: Session, headers: Headers) = {

    def getSessionId: Option[String] = session.get(SessionKeys.sessionId).fold[Option[String]](headers.get(HeaderNames.xSessionId))(Some(_))

    HeaderCarrier(
      authorization = session.get(SessionKeys.authToken).map(Authorization),
      userId = session.get(SessionKeys.userId).map(UserId),
      token = session.get(SessionKeys.token).map(Token),
      forwarded = ((headers.get(HeaderNames.trueClientIp), headers.get(HeaderNames.xForwardedFor)) match {
        case (tcip, None) => tcip
        case (None | Some(""), xff) => xff
        case (Some(tcip), Some(xff)) if xff.startsWith(tcip) => Some(xff)
        case (Some(tcip), Some(xff)) => Some(s"$tcip, $xff")
      }).map(ForwardedFor),
      sessionId = getSessionId.map(SessionId),
      requestId = headers.get(HeaderNames.xRequestId).map(RequestId),
      requestChain = buildRequestChain(headers.get(HeaderNames.xRequestChain)),
      nsStamp = requestTimestamp(headers)
    )
  }

  def buildRequestChain(currentChain: Option[String]): RequestChain = {
    currentChain match {
      case None => RequestChain.init
      case Some(chain) => RequestChain(chain).extend
    }
  }

  def requestTimestamp(headers: Headers): Long =
    headers
      .get(HeaderNames.xRequestTimestamp)
      .flatMap(tsAsString => Try(tsAsString.toLong).toOption)
      .getOrElse(System.nanoTime())
}
