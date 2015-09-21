package com.predic8.membrane.core.interceptor.balancer.faultmonitoring;

import com.predic8.membrane.annot.MCElement;
import com.predic8.membrane.core.config.AbstractXmlElement;
import com.predic8.membrane.core.exchange.AbstractExchange;
import com.predic8.membrane.core.interceptor.balancer.*;
import com.predic8.membrane.core.transport.http.HttpClientStatusEventBus;
import com.predic8.membrane.core.transport.http.HttpClientStatusEventListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * Monitors the outcome of requests to each node to quickly disable/re-enable faulty ones.
 *
 * <h2>WHY THIS CLASS</h2>
 * <p>This is a drop-in replacement for the default {@link RoundRobinStrategy}. In fact, as long as all nodes
 * respond correctly, it works the same. Things only start changing once nodes fail.
 * Whereas the RoundRobin keeps dispatching to faulty nodes, causing delays for the service user, this
 * strategy detects the issue and favours functional nodes.
 * </p>
 *
 * <h2>FORECAST PREDICTION USING "PERSISTENCE METHOD"</h2>
 * <p>Predicting tomorrow's weather by saying it will be equal to today's works well in many areas of the
 * world. Similarly, predicting that another service request to a node will succeed after a success, or fail
 * after a failure, is very likely.
 * That's the main concept behind the logic and algorithm used within this class.
 * </p>
 *
 * <h2>HOW IT WORKS</h2>
 * <p>Once a node returns with a failure, a fault profile for that node is created. The node is instantly
 * considered to be faulty. From then on, all status (success/failure) of that node is monitored.
 * If enough successive calls are successful, the node is cleared from its bad reputation.
 * If the node is not used anymore (either not requests at all, or none to that node because it's
 * faulty and there are enough good nodes) then the node is cleared from its bad reputation after
 * a configurable amount of time.
 * When there are enough functional nodes (configurable ratio), only the functional ones are used
 * with the simplistic round-robin strategy. If not, then all nodes are used, and the selection is a
 * weighted chance operation by recent success rate.
 * </p>
 *
 * <h2>WHAT IS A FAULT</h2>
 * <p>A fault is when the destination replied with a valid 5xx HTTP status code, or when an exception
 * (such as a ConnectException) was thrown. Everything else, including 4xx codes, is considered a success.</p>
 *
 * <h2>PER NODE, NOT PER DESTINATION</h2>
 * <p>Success status could be monitored per node (host/port) or per destination (host/port/servicename).
 * In practice, most failures affect all services. Rarely only one service on a node is faulty, but it's
 * also very possible. This implementation currently monitors per node. This is for technical reasons,
 * per destination is currently not possible with the information that is present. If it was available,
 * I'm not sure which one is better to choose. Maybe configurable would be nice.
 * If monitoring happens per destination, and one service is detected to be faulty, but in fact the
 * whole node is down, this information will then not be freely available for the other destinations...
 * they have to figure it out independently. Unless some more complicated functionality is built in.
 * </p>
 *
 * <h2>GOALS OF THIS CLASS</h2>
 * <p>Simple super-fast, super-low memory use. The goal is to provide a great service experience to the api
 * user. Over-complicating things in here and introducing possible bugs or bottlenecks must be avoided.
 * </p>
 *
 * <h2>WHAT IT'S NOT</h2>
 * <p>It does not do response time monitoring to identify slow/laggy servers, or to favour faster ones.
 * There can be different kinds of services on the same hosts, simple and complex expensive ones,
 * and we don't have that kind of information here. Another similar DispatchingStrategy could do
 * such logic.
 * </p>
 *
 * <h2>LIMITATIONS</h2>
 * <p>No background checking of faulty nodes.
 * For certain kinds of failures, including ConnectException and UnknownHostException, a background service
 * could keep checking a faulty node, and only re-enable it once that works again.
 * For other cases, there could be a pluggable BackgroundUptimeCheck interface. This would allow a
 * service implementor to write his own check that fits his needs. For example by sending a real
 * service request that does not harm.
 * Automatic background retry of previous failed requests to see if the service is back online is a bad
 * idea... think payment service.
 * </p>
 *
 * <h2>TODO</h2>
 * <p>This class must be used as a singleton. For now it's pretty hacky plugged into the system.
 * Somebody who is familiar with the architecture of Membrane please make it nicer/configurable ;-)
 * </p>
 *
 *
 * @author Fabian Kessler / Optimaize
 */
@MCElement(name="faultMonitoringStrategy")
public class FaultMonitoringStrategy extends AbstractXmlElement implements DispatchingStrategy {

	private static Log log = LogFactory.getLog(FaultMonitoringStrategy.class.getName());


	/**
	 * If at least this many servers in relation to the total number of servers are "flawless", then only the
	 * flawless servers are used with a round-robin strategy. The faulty ones are ignored.
	 * If too many servers had some issues, then it goes back to using all servers (faulty and flawless)
	 * using the weighted-chance strategy.
	 *
	 * TODO make this configurable by XML.
	 */
	private static final double MIN_FLAWLESS_SERVER_RATIO_FOR_ROUND_ROBIN = 0.5d;

	/**
	 * When this much time has passed, and there was no more fault, then the node is cleared from the bad history.
	 * It is possible that the node served requests successfully since then, or that it was not called at all.
	 * If it was not called, it's very possible that the node is still faulty. But we don't know.
	 * What happens is that the node is used again, but if it fails it will instantly have a fault profile.
	 *
	 * TODO make this configurable by XML.
	 */
	private static final long CLEAR_FAULTY_PROFILES_BY_TIMER_AFTER_LAST_FAILURE_SECONDS = 5 *60 *1000; //five minutes

	/**
	 * Every this much time a TimerTask runs to see if there are any fault profiles to clear.
	 *
	 * TODO make this configurable by XML.
	 */
	private static final long CLEAR_FAULTY_TIMER_INTERVAL_SECONDS = 30 * 1000; //30 seconds



	/**
	 * Lazy initialized singleton.
	 *
	 * Because the state is externalized in the FaultMonitoringState, it should be safe to refactor this
	 * to create a new instance on context reloads.
	 */
	private static FaultMonitoringStrategy INSTANCE;
	public static synchronized FaultMonitoringStrategy getInstance() {
		if (INSTANCE==null) {
			INSTANCE = new FaultMonitoringStrategy();
		}
		return INSTANCE;
	}



	/**
	 * Key = destination "host:port"
	 */
	private final FaultMonitoringState state;


	private FaultMonitoringStrategy() {
		HttpClientStatusEventBus.getService().registerListener(new HttpClientStatusEventListener() {
			@Override
			public void onResponse(long timestamp, String destination, int responseCode) {
				log.debug("onResponse for "+destination+" with code "+responseCode+" at time "+timestamp);
				String hostAndPort = extractHostAndPort(destination);
				NodeFaultProfile nodeFaultProfile = state.getMap().get(hostAndPort);
				boolean is5xx = responseCode >= 500 && responseCode < 600;
				if (!is5xx) {
					if (informSuccess(timestamp, nodeFaultProfile)) {
						//clear from bad history:
						state.getMap().remove(hostAndPort);
						log.debug("Self-cleared from bad history: "+hostAndPort);
					}
				} else {
					informFailure(timestamp, hostAndPort, nodeFaultProfile);
				}
			}

			@Override
			public void onException(long timestamp, String destination, Exception exception) {
				log.debug("onException for " + destination + " with ex " + exception.getMessage() + " at time " + timestamp);
				String hostAndPort = extractHostAndPort(destination);
				NodeFaultProfile nodeFaultProfile = state.getMap().get(hostAndPort);
				//have to inform profile about this failure. if there's no profile yet, create one.
				informFailure(timestamp, hostAndPort, nodeFaultProfile);
			}

			/**
			 * @return true to remove the destinationProfile (to clear him from bad history),
			 *         false to do nothing (either because there is none or because it's still bad).
			 */
			private boolean informSuccess(long timestamp, NodeFaultProfile nodeFaultProfile) {
				//treat as success (from our side), server handled it well.
				if (nodeFaultProfile ==null) {
					//fine, the server still works correctly.
					return false;
				} else {
					//inform about that good result.
					return nodeFaultProfile.informSuccess(timestamp);
				}
			}

			private void informFailure(long timestamp, String hostAndPort, NodeFaultProfile nodeFaultProfile) {
				//have to inform profile about this failure. if there's no profile yet, create one.
				if (nodeFaultProfile ==null) {
                    nodeFaultProfile = new NodeFaultProfile(timestamp);
					state.getMap().putIfAbsent(hostAndPort, nodeFaultProfile); //worst case we have a race condition, and another thread just set it. then one division/2 got lost. that's fine.
					log.debug("Created bad history profile for: "+hostAndPort);
                } else {
					nodeFaultProfile.informFailure(timestamp);
				}
			}

			private String extractHostAndPort(String destination) {
				URI uri;
				try {
					uri = new URI(destination);
				} catch (URISyntaxException e) {
					throw new RuntimeException(e);
				}
				return uri.getHost() +":"+ uri.getPort();
			}
		});

		state = FaultMonitoringState.getInstance();
		state.scheduleRemoval(CLEAR_FAULTY_PROFILES_BY_TIMER_AFTER_LAST_FAILURE_SECONDS, CLEAR_FAULTY_TIMER_INTERVAL_SECONDS);
	}

	private List<Node> filterBySuccessProfile(List<Node> endpoints) {
		if (state.getMap().isEmpty()) return endpoints;
		List<Node> filtered = new ArrayList<Node>(endpoints.size());
		for (Node endpoint : endpoints) {
			NodeFaultProfile nodeFaultProfile = state.getMap().get(makeHostAndPort(endpoint));
			if (nodeFaultProfile != null) {
				if (nodeFaultProfile.getScore() < 1d) { //in a race condition the profile could still exist, but have a perfect score.
					continue;
				}
			}
			filtered.add(endpoint);
		}
		return filtered;
	}

	private String makeHostAndPort(Node endpoint) {
		return endpoint.getHost()+":"+endpoint.getPort();
	}


	public void done(AbstractExchange exc) {
		//nothing to do here, we get the information through the event bus.
	}


	public synchronized Node dispatch(LoadBalancingInterceptor interceptor) throws EmptyNodeListException {
		//getting a decoupled copy to avoid index out of bounds in case of concurrent modification (dynamic config files reload...)
		List<Node> endpoints = interceptor.getEndpoints(); //this calls synchronizes access internally.
		if (endpoints.isEmpty()) {
			//there's nothing we can do here. no nodes configured, or all nodes were reported to be offline.
			throw new EmptyNodeListException();
		} else if (endpoints.size()==1) {
			//there's nothing else we can do.
			return endpoints.get(0);
		}

		List<Node> endpointsFiltered = filterBySuccessProfile(endpoints);

		if (endpointsFiltered.size() >= 1) {
			double ratio = endpointsFiltered.size() / (double)endpoints.size();
			if (ratio >= MIN_FLAWLESS_SERVER_RATIO_FOR_ROUND_ROBIN) {
				//got enough valid nodes. go into simple round-robin strategy
				log.trace("Selecting round robin for "+endpointsFiltered.size()+"/"+endpoints.size()+" endpoints.");
				return applyRoundRobinStrategy(endpointsFiltered);
			}
		}

		//using all servers.
		log.trace("Selecting by chance");
		return returnByChance(endpoints);
	}

	/**
	 * This returns a random node computed by the chance in relation to the recent success rate.
	 * @param endpoints containing at least 2 entries
	 */
	private Node returnByChance(List<Node> endpoints) {
		assert endpoints.size() >= 2;

		RandomGroup<Node> selector = RandomGroup.create();
		for (Node endpoint : endpoints) {
			double score;
			NodeFaultProfile nodeFaultProfile = state.getMap().get(makeHostAndPort(endpoint));
			if (nodeFaultProfile ==null) {
				score = 1d;
			} else {
				score = nodeFaultProfile.getScore();
				if (score == 0d) {
					//The number 0 may theoretically occur if there are 10 thousand successive failures
					//(dividing the number 1.0d by half 10k times results in 0d).
					//This adjustment is necessary because the selector does not accept zero chance.
					//Another option would be to exclude it, but then we have to check whether all are excluded, and so on.
					//That would get complicated also. For now this is good enough.
					score = 0.0001d;
				}
			}
			selector.add(endpoint, score);
		}
		return selector.next();
	}


	/**
	 * Using the "last" variable is a bit dirty, we can't be sure to always get the same nodes.
	 * But in practice it is irrelevant, worst case this returns the same Node twice in a row...
	 */
	private int last = -1;
	private Node applyRoundRobinStrategy(List<Node> endpoints) {
		int i = incrementAndGet(endpoints.size());
		return endpoints.get(i);
	}
	/**
	 * Must be atomic, therefore synchronized.
	 */
	private synchronized int incrementAndGet(int numEndpoints) {
		last ++;
		if (last >= numEndpoints) {
			last = 0;
		}
		return last;
	}




	@Override
	public void write(XMLStreamWriter out) throws XMLStreamException {
		out.writeStartElement("faultMonitoringStrategy");
		out.writeEndElement();
	}

	@Override
	protected String getElementName() {
		return "faultMonitoringStrategy";
	}

}
