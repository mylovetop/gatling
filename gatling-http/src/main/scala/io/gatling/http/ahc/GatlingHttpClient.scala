/**
 * Copyright 2011-2013 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
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
package io.gatling.http.ahc

import java.util.concurrent.{ Executors, ThreadFactory }

import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.logging.{ InternalLoggerFactory, Slf4JLoggerFactory }

import com.ning.http.client.{ AsyncHttpClient, AsyncHttpClientConfig }
import com.ning.http.client.providers.netty.{ NettyAsyncHttpProviderConfig, NettyConnectionsPool }
import com.typesafe.scalalogging.slf4j.Logging

import io.gatling.core.ConfigurationConstants._
import io.gatling.core.action.system
import io.gatling.core.config.GatlingConfiguration.configuration
import io.gatling.core.session.{ Session, SessionPrivateAttributes }
import io.gatling.http.util.SSLHelper.{ RichAsyncHttpClientConfigBuilder, newKeyManagers, newTrustManagers }

object GatlingHttpClient extends Logging {

	val httpClientAttributeName = SessionPrivateAttributes.privateAttributePrefix + "http.client"

	// set up Netty LoggerFactory for slf4j instead of default JDK
	InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

	object SharedResources {

		val reaper = Executors.newScheduledThreadPool(Runtime.getRuntime.availableProcessors, new ThreadFactory {
			override def newThread(r: Runnable): Thread = {
				val t = new Thread(r, "AsyncHttpClient-Reaper")
				t.setDaemon(true)
				t
			}
		})

		val applicationThreadPool = Executors.newCachedThreadPool(new ThreadFactory {
			override def newThread(r: Runnable) = {
				val t = new Thread(r, "AsyncHttpClient-Callback")
				t.setDaemon(true)
				t
			}
		})

		val connectionsPool = new NettyConnectionsPool(configuration.http.maximumConnectionsTotal,
			configuration.http.maximumConnectionsPerHost,
			configuration.http.idleConnectionInPoolTimeOutInMs,
			configuration.http.allowSslConnectionPool)

		val nettyConfig = {
			val numWorkers = configuration.http.ioThreadMultiplier * Runtime.getRuntime.availableProcessors
			val socketChannelFactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool, applicationThreadPool, numWorkers)
			system.registerOnTermination(socketChannelFactory.releaseExternalResources)
			new NettyAsyncHttpProviderConfig().addProperty(NettyAsyncHttpProviderConfig.SOCKET_CHANNEL_FACTORY, socketChannelFactory)
		}
	}

	val defaultAhcConfig = {
		val ahcConfigBuilder = new AsyncHttpClientConfig.Builder()
			.setAllowPoolingConnection(configuration.http.allowPoolingConnection)
			.setAllowSslConnectionPool(configuration.http.allowSslConnectionPool)
			.setCompressionEnabled(configuration.http.compressionEnabled)
			.setConnectionTimeoutInMs(configuration.http.connectionTimeOut)
			.setIdleConnectionInPoolTimeoutInMs(configuration.http.idleConnectionInPoolTimeOutInMs)
			.setIdleConnectionTimeoutInMs(configuration.http.idleConnectionTimeOutInMs)
			.setIOThreadMultiplier(configuration.http.ioThreadMultiplier)
			.setMaximumConnectionsPerHost(configuration.http.maximumConnectionsPerHost)
			.setMaximumConnectionsTotal(configuration.http.maximumConnectionsTotal)
			.setMaxRequestRetry(configuration.http.maxRetry)
			.setRequestCompressionLevel(configuration.http.requestCompressionLevel)
			.setRequestTimeoutInMs(configuration.http.requestTimeOutInMs)
			.setUseProxyProperties(configuration.http.useProxyProperties)
			.setUserAgent(configuration.http.userAgent)
			.setUseRawUrl(configuration.http.useRawUrl)
			.setExecutorService(SharedResources.applicationThreadPool)
			.setScheduledExecutorService(SharedResources.reaper)
			.setAsyncHttpClientProviderConfig(SharedResources.nettyConfig)
			.setConnectionsPool(SharedResources.connectionsPool)

		val trustManagers = configuration.http.ssl.trustStore
			.map { config => newTrustManagers(config.storeType, config.file, config.password, config.algorithm) }

		val keyManagers = configuration.http.ssl.keyStore
			.map { config => newKeyManagers(config.storeType, config.file, config.password, config.algorithm) }

		if (trustManagers.isDefined || keyManagers.isDefined)
			ahcConfigBuilder.setSSLContext(trustManagers, keyManagers)

		ahcConfigBuilder.build
	}

	def newClient(session: Session): AsyncHttpClient = newClient(Some(session))
	def newClient(session: Option[Session]) = {

		val ahcConfig = session.map { session =>

			val trustManagers = for {
				storeType <- session.get[String](CONF_HTTP_SSS_TRUST_STORE_TYPE)
				file <- session.get[String](CONF_HTTP_SSS_TRUST_STORE_FILE)
				password <- session.get[String](CONF_HTTP_SSS_TRUST_STORE_PASSWORD)
				algorithm = session.get[String](CONF_HTTP_SSS_TRUST_STORE_ALGORITHM)
			} yield newTrustManagers(storeType, file, password, algorithm)

			val keyManagers = for {
				storeType <- session.get[String](CONF_HTTP_SSS_TRUST_STORE_TYPE)
				file <- session.get[String](CONF_HTTP_SSS_TRUST_STORE_FILE)
				password <- session.get[String](CONF_HTTP_SSS_TRUST_STORE_PASSWORD)
				algorithm = session.get[String](CONF_HTTP_SSS_TRUST_STORE_ALGORITHM)
			} yield newKeyManagers(storeType, file, password, algorithm)

			if (trustManagers.isDefined || keyManagers.isDefined) {
				logger.info(s"Setting a custom SSLContext for user ${session.userId}")
				new AsyncHttpClientConfig.Builder(defaultAhcConfig).setSSLContext(trustManagers, keyManagers).build
			} else
				defaultAhcConfig

		}.getOrElse(defaultAhcConfig)

		val client = new AsyncHttpClient(ahcConfig)
		system.registerOnTermination(client.close)
		client
	}

	lazy val defaultClient = newClient(None)
}
