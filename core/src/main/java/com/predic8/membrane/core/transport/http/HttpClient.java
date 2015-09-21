/* Copyright 2009, 2012 predic8 GmbH, www.predic8.com

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package com.predic8.membrane.core.transport.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

import javax.annotation.concurrent.GuardedBy;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.predic8.membrane.core.Constants;
import com.predic8.membrane.core.config.security.SSLParser;
import com.predic8.membrane.core.exchange.Exchange;
import com.predic8.membrane.core.http.ChunkedBodyTransferrer;
import com.predic8.membrane.core.http.Header;
import com.predic8.membrane.core.http.PlainBodyTransferrer;
import com.predic8.membrane.core.http.Request;
import com.predic8.membrane.core.http.Response;
import com.predic8.membrane.core.model.AbstractExchangeViewerListener;
import com.predic8.membrane.core.transport.http.client.AuthenticationConfiguration;
import com.predic8.membrane.core.transport.http.client.HttpClientConfiguration;
import com.predic8.membrane.core.transport.http.client.ProxyConfiguration;
import com.predic8.membrane.core.transport.ssl.SSLContext;
import com.predic8.membrane.core.transport.ssl.SSLProvider;
import com.predic8.membrane.core.util.EndOfStreamException;
import com.predic8.membrane.core.util.HttpUtil;
import com.predic8.membrane.core.util.Util;

/**
 * HttpClient with possibly multiple selectable destinations, with internal logic to auto-retry and to
 * switch destinations on failures.
 *
 * Instances are thread-safe.
 */
public class HttpClient {

	private static Log log = LogFactory.getLog(HttpClient.class.getName());
	@GuardedBy("HttpClient.class")
	private static SSLProvider defaultSSLProvider;

	private final ProxyConfiguration proxy;
	private final AuthenticationConfiguration authentication;

	/**
	 * How long to wait between calls to the same destination, in milliseconds.
	 * To prevent hammering one target.
	 * Between calls to different targets (think servers) this waiting time is not applied.
	 *
	 * Note: for reasons of code simplicity, this sleeping time is only applied between direct successive calls
	 * to the same target. If there are multiple targets like one, two, one and it all goes very fast, then
	 * it's possible that the same server gets hit with less time in between.
	 */
	private final int timeBetweenTriesMs = 250;
	/**
	 * See {@link HttpClientConfiguration#setMaxRetries(int)}
	 */
	private final int maxRetries;
	private final int connectTimeout;
	private final String localAddr;

	private final ConnectionManager conMgr;
	private StreamPump.StreamPumpStats streamPumpStats;

	/**
	 * TODO make injectable, make it an optional feature, don't pay for what you don't use.
	 */
	private HttpClientStatusEventBus httpClientStatusEventBus = HttpClientStatusEventBus.getService();


	public HttpClient() {
		this(new HttpClientConfiguration());
	}

	public HttpClient(HttpClientConfiguration configuration) {
		proxy = configuration.getProxy();
		authentication = configuration.getAuthentication();
		maxRetries = configuration.getMaxRetries();

		connectTimeout = configuration.getConnection().getTimeout();
		localAddr = configuration.getConnection().getLocalAddr();

		conMgr = new ConnectionManager(configuration.getConnection().getKeepAliveTimeout());
	}

	public void setStreamPumpStats(StreamPump.StreamPumpStats streamPumpStats) {
		this.streamPumpStats = streamPumpStats;
	}

	@Override
	protected void finalize() throws Throwable {
		conMgr.shutdownWhenDone();
	}

	private void setRequestURI(Request req, String dest) throws MalformedURLException {
		if (proxy != null || req.isCONNECTRequest())
			req.setUri(dest);
		else {
			if (!dest.startsWith("http"))
				throw new MalformedURLException("The exchange's destination URL ("+dest+") does not start with 'http'. Please specify a <target> within your <serviceProxy>.");
			req.setUri(HttpUtil.getPathAndQueryString(dest));
		}
	}

	private HostColonPort getTargetHostAndPort(boolean connect, String dest) throws MalformedURLException, UnknownHostException {
		if (proxy != null)
			return new HostColonPort(false, proxy.getHost(), proxy.getPort());

		if (connect)
			return new HostColonPort(false, dest);

		return new HostColonPort(new URL(dest));
	}

	private HostColonPort init(Exchange exc, String dest, boolean adjustHostHeader) throws UnknownHostException, IOException, MalformedURLException {
		setRequestURI(exc.getRequest(), dest);
		HostColonPort target = getTargetHostAndPort(exc.getRequest().isCONNECTRequest(), dest);

		if (proxy != null && proxy.isAuthentication()) {
			exc.getRequest().getHeader().setProxyAutorization(proxy.getCredentials());
		}

		if (authentication != null)
			exc.getRequest().getHeader().setAuthorization(authentication.getUsername(), authentication.getPassword());

		if (adjustHostHeader && (exc.getRule() == null || exc.getRule().isTargetAdjustHostHeader())) {
			URL d = new URL(dest);
			exc.getRequest().getHeader().setHost(d.getHost() + ":" + HttpUtil.getPort(d));
		}
		return target;
	}

	private SSLProvider getOutboundSSLProvider(Exchange exc, HostColonPort hcp) {
		if (exc.getRule() != null)
			return exc.getRule().getSslOutboundContext();
		if (hcp.useSSL)
			return getDefaultSSLProvider();
		return null;
	}

	private static synchronized SSLProvider getDefaultSSLProvider() {
		if (defaultSSLProvider == null)
			defaultSSLProvider = new SSLContext(new SSLParser(), null, null);
		return defaultSSLProvider;
	}

	public Exchange call(Exchange exc) throws Exception {
		return call(exc, true, true);
	}

	public Exchange call(Exchange exc, boolean adjustHostHeader, boolean failOverOn5XX) throws Exception {
		if (exc.getDestinations().isEmpty())
			throw new IllegalStateException("List of destinations is empty. Please specify at least one destination.");

		int counter = 0;
		Exception exception = null;
		while (counter < maxRetries) {
			Connection con = null;
			String dest = getDestination(exc, counter);
			HostColonPort target = null;
			Integer responseStatusCode = null;
			try {
				log.debug("try # " + counter + " to " + dest);
				target = init(exc, dest, adjustHostHeader);
				InetAddress targetAddr = InetAddress.getByName(target.host);
				if (counter == 0) {
					con = exc.getTargetConnection();
					if (con != null) {
						if (!con.isSame(targetAddr, target.port)) {
							con.close();
							con = null;
						} else {
							con.setKeepAttachedToExchange(true);
						}
					}
				}
				if (con == null) {
					con = conMgr.getConnection(targetAddr, target.port, localAddr, getOutboundSSLProvider(exc, target), connectTimeout);
					con.setKeepAttachedToExchange(exc.getRequest().isBindTargetConnectionToIncoming());
					exc.setTargetConnection(con);
				}
				Response response;
				String newProtocol = null;

				if (exc.getRequest().isCONNECTRequest()) {
					handleConnectRequest(exc, con);
					response = Response.ok().build();
					newProtocol = "CONNECT";
					//TODO should we report to the httpClientStatusEventBus here somehow?
				} else {
					response = doCall(exc, con);
					if (exc.getProperty(Exchange.ALLOW_WEBSOCKET) == Boolean.TRUE && isUpgradeToWebSocketsResponse(response)) {
						log.debug("Upgrading to WebSocket protocol.");
						newProtocol = "WebSocket";
						//TODO should we report to the httpClientStatusEventBus here somehow?
					}
				}

				if (newProtocol != null) {
					setupConnectionForwarding(exc, con, newProtocol, streamPumpStats);
					exc.getDestinations().clear();
					exc.getDestinations().add(dest);
					con.setExchange(exc);
					exc.setResponse(response);
					return exc;
				}

				responseStatusCode = response.getStatusCode();

				httpClientStatusEventBus.reportResponse(dest, responseStatusCode);

				if (!failOverOn5XX || !is5xx(responseStatusCode) || counter == maxRetries-1) {
					applyKeepAliveHeader(response, con);
					exc.getDestinations().clear();
					exc.getDestinations().add(dest);
					con.setExchange(exc);
					response.addObserver(con);
					exc.setResponse(response);
					//TODO should we report to the httpClientStatusEventBus here somehow?
					return exc;
				}

				// java.net.SocketException: Software caused connection abort: socket write error
			} catch (ConnectException e) {
				exception = e;
				log.info("Connection to " + (target == null ? dest : target) + " refused.");
			} catch(SocketException e){
				exception = e;
				if ( e.getMessage().contains("Software caused connection abort")) {
					log.info("Connection to " + dest + " was aborted externally. Maybe by the server or the OS Membrane is running on.");
				} else if (e.getMessage().contains("Connection reset") ) {
					log.info("Connection to " + dest + " was reset externally. Maybe by the server or the OS Membrane is running on.");
				} else {
					logException(exc, counter, e);
				}
			} catch (UnknownHostException e) {
				exception = e;
				log.warn("Unknown host: " + (target == null ? dest : target ));
			} catch (EOFWhileReadingFirstLineException e) {
				exception = e;
				log.debug("Server connection to " + dest + " terminated before line was read. Line so far: " + e.getLineSoFar());
			} catch (NoResponseException e) {
				exception = e;
			} catch (Exception e) {
				exception = e;
				logException(exc, counter, e);
			}

			//we have an error. either in the form of an exception, or as a 5xx response code.
			if (exception!=null) {
				httpClientStatusEventBus.reportException(dest, exception);
			} else {
				assert responseStatusCode!=null && is5xx(responseStatusCode);
				httpClientStatusEventBus.reportResponse(dest, responseStatusCode);
			}

			if (exception instanceof UnknownHostException) {
				if (exc.getDestinations().size() < 2) {
					//don't retry this host, it's useless. (it's very unlikely that it will work after timeBetweenTriesMs)
					break;
				}
			} else if (exception instanceof NoResponseException) {
				//TODO explain why we give up here, don't even retry another host.
				//maybe it means we ourselves lost network connection?
				throw exception;
			}

			counter++;
			if (exc.getDestinations().size() == 1) {
				//as documented above, the sleep timeout is only applied between successive calls to the SAME destination.
				Thread.sleep(timeBetweenTriesMs);
			}
		}
		throw exception;
	}

	private boolean is5xx(Integer responseStatusCode) {
		return 500 <= responseStatusCode && responseStatusCode < 600;
	}

	private void applyKeepAliveHeader(Response response, Connection con) {
		String value = response.getHeader().getFirstValue(Header.KEEP_ALIVE);
		if (value == null)
			return;

		long timeoutSeconds = Header.parseKeepAliveHeader(value, Header.TIMEOUT);
		if (timeoutSeconds != -1)
			con.setTimeout(timeoutSeconds * 1000);

		long max = Header.parseKeepAliveHeader(value, Header.MAX);
		if (max != -1 && max < con.getMaxExchanges())
			con.setMaxExchanges((int)max);
	}

	/**
	 * Returns the target destination to use for this attempt.
	 * @param counter starting at 0 meaning the first.
	 */
	private String getDestination(Exchange exc, int counter) {
		return exc.getDestinations().get(counter % exc.getDestinations().size());
	}

	private void logException(Exchange exc, int counter, Exception e) throws IOException {
		if (log.isDebugEnabled()) {
			StringBuilder msg = new StringBuilder();
			msg.append("try # ");
			msg.append(counter);
			msg.append(" failed\n");

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			exc.getRequest().writeStartLine(baos);
			exc.getRequest().getHeader().write(baos);
			msg.append(Constants.ISO_8859_1_CHARSET.decode(ByteBuffer.wrap(baos.toByteArray())));

			if (e != null)
				log.debug(msg, e);
			else
				log.debug(msg);
		}
	}

	private Response doCall(Exchange exc, Connection con) throws IOException, EndOfStreamException {
		exc.getRequest().write(con.out);
		exc.setTimeReqSent(System.currentTimeMillis());

		if (exc.getRequest().isHTTP10()) {
			shutDownRequestInputOutput(exc, con);
		}

		Response res = new Response();
		res.read(con.in, !exc.getRequest().isHEADRequest());

		if (res.getStatusCode() == 100) {
			do100ExpectedHandling(exc, res, con);
		}

		exc.setReceived();
		exc.setTimeResReceived(System.currentTimeMillis());
		return res;
	}

	public static void setupConnectionForwarding(Exchange exc, final Connection con, final String protocol, StreamPump.StreamPumpStats streamPumpStats) throws SocketException {
		final HttpServerHandler hsr = (HttpServerHandler)exc.getHandler();
		String source = hsr.getSourceSocket().getRemoteSocketAddress().toString();
		String dest = con.toString();
		final StreamPump a = new StreamPump(con.in, hsr.getSrcOut(), streamPumpStats, protocol + " " + source + " <- " + dest, exc.getRule());
		final StreamPump b = new StreamPump(hsr.getSrcIn(), con.out, streamPumpStats, protocol + " " + source + " -> " + dest, exc.getRule());

		hsr.getSourceSocket().setSoTimeout(0);

		exc.addExchangeViewerListener(new AbstractExchangeViewerListener() {

			@Override
			public void setExchangeFinished() {
				String threadName = Thread.currentThread().getName();
				new Thread(a, threadName + " " + protocol + " Backward Thread").start();
				try {
					Thread.currentThread().setName(threadName + " " + protocol + " Onward Thread");
					b.run();
				} finally {
					try {
						con.close();
					} catch (IOException e) {
						log.debug("", e);
					}
				}
			}
		});
	}

	private boolean isUpgradeToWebSocketsResponse(Response res) {
		return res.getStatusCode() == 101 &&
				"upgrade".equalsIgnoreCase(res.getHeader().getFirstValue(Header.CONNECTION)) &&
				"websocket".equalsIgnoreCase(res.getHeader().getFirstValue(Header.UPGRADE));
	}

	private void handleConnectRequest(Exchange exc, Connection con) throws IOException, EndOfStreamException {
		if (proxy != null) {
			exc.getRequest().write(con.out);
			Response response = new Response();
			response.read(con.in, false);
			log.debug("Status code response on CONNECT request: " + response.getStatusCode());
		}
		exc.getRequest().setUri(Constants.N_A);
	}

	private void do100ExpectedHandling(Exchange exc, Response response, Connection con) throws IOException, EndOfStreamException {
		exc.getRequest().getBody().write(exc.getRequest().getHeader().isChunked() ? new ChunkedBodyTransferrer(con.out) : new PlainBodyTransferrer(con.out));
		con.out.flush();
		response.read(con.in, !exc.getRequest().isHEADRequest());
	}

	private void shutDownRequestInputOutput(Exchange exc, Connection con) throws IOException {
		exc.getHandler().shutdownInput();
		Util.shutdownOutput(con.socket);
	}

	ConnectionManager getConnectionManager() {
		return conMgr;
	}
}
