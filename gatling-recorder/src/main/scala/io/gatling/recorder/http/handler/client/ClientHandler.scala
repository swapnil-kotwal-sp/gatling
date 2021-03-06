/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.recorder.http.handler.client

import io.gatling.recorder.http.handler.server.SslHandlerSetter

import com.typesafe.scalalogging.slf4j.StrictLogging
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.http.util.HttpHelper.OkCodes
import io.gatling.recorder.controller.RecorderController
import io.gatling.recorder.http.channel.BootstrapFactory._
import io.gatling.recorder.http.handler.ScalaChannelHandler
import io.gatling.recorder.http.ssl.SSLEngineFactory
import org.jboss.netty.channel._
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.handler.ssl.SslHandler

case class TimedHttpRequest(httpRequest: HttpRequest, sendTime: Long = nowMillis)

class ClientHandler(controller: RecorderController, serverChannel: Channel, var performConnect: Boolean)
    extends SimpleChannelHandler with ScalaChannelHandler with StrictLogging {

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent): Unit = {

      def handleConnect(response: HttpResponse): Unit = {

          def upgradeClientPipeline(clientPipeline: ChannelPipeline, clientSslHandler: SslHandler): Unit = {
            // the HttpClientCodec has to be regenerated, don't ask me why...
            clientPipeline.replace(CodecHandlerName, CodecHandlerName, new HttpClientCodec)
            clientPipeline.addFirst(SslHandlerName, clientSslHandler)
          }

        if (response.getStatus == HttpResponseStatus.OK) {
          performConnect = false
          val clientSslHandler = new SslHandler(SSLEngineFactory.newClientSSLEngine)
          upgradeClientPipeline(ctx.getChannel.getPipeline, clientSslHandler)

          clientSslHandler.handshake.addListener { handshakeFuture: ChannelFuture =>
            // TODO here, we could generate a certificate for this given peer, even based on Session principal if it could be authenticated
            serverChannel.getPipeline.addFirst(SslHandlerName, new SslHandlerSetter)
            serverChannel.write(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK))
          }
        } else
          throw new UnsupportedOperationException(s"Outgoing proxy refused to connect: ${response.getStatus}")
      }

      def handleRequest(response: HttpResponse): Unit = {

          def isKeepAlive(headers: HttpHeaders) = Option(headers.get(HttpHeaders.Names.CONNECTION)).exists(HttpHeaders.Values.KEEP_ALIVE.equalsIgnoreCase)

        ctx.getAttachment match {
          case request: TimedHttpRequest =>
            val keepAlive = isKeepAlive(request.httpRequest.headers) && isKeepAlive(response.headers)

            controller.receiveResponse(request, response)

            ctx.setAttachment(null)

            serverChannel.write(response).addListener { future: ChannelFuture =>

              if (keepAlive && OkCodes.contains(response.getStatus.getCode)) {
                logger.debug("Both request and response are willing to keep the connection alive, reusing channels")
              } else {
                logger.debug("Request and/or response is not willing to keep the connection alive, closing both channels")
                serverChannel.close()
                ctx.getChannel.close()
              }
            }

          case _ => throw new IllegalStateException("Couldn't find request attachment")
        }
      }

    event.getMessage match {
      case response: HttpResponse =>
        if (performConnect)
          handleConnect(response)
        else
          handleRequest(response)

      case unknown => logger.warn(s"Received unknown message: $unknown")
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent): Unit = {
    serverChannel.close()
  }
}
