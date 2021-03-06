/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.springframework.cloud.gateway.handler;

import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

/**
 * @author Spencer Gibb
 */
public class RoutePredicateHandlerMapping extends AbstractHandlerMapping {

	private final FilteringWebHandler webHandler;
	private final RouteLocator routeLocator;
	private final Integer managmentPort;

	public RoutePredicateHandlerMapping(FilteringWebHandler webHandler, RouteLocator routeLocator, GlobalCorsProperties globalCorsProperties, Environment environment) {
		this.webHandler = webHandler;
		this.routeLocator = routeLocator;

		if (environment.containsProperty("management.server.port")) {
			managmentPort = new Integer(Objects.requireNonNull(environment.getProperty("management.server.port")));
		} else {
			managmentPort = null;
		}
		setOrder(1);		
		setCorsConfigurations(globalCorsProperties.getCorsConfigurations());
	}

	@Override
	protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
		// don't handle requests on the management port if set
		if (managmentPort != null && exchange.getRequest().getURI().getPort() == managmentPort) {
			return Mono.empty();
		}
		exchange.getAttributes().put(GATEWAY_HANDLER_MAPPER_ATTR, getSimpleName());

		/**
		 * ???????????????request ?????????????????? Route,??????attributes, key: GATEWAY_ROUTE_ATTR
		 * ??? {@link FilteringWebHandler#handle(org.springframework.web.server.ServerWebExchange)} ?????????????????????
		 */
		return lookupRoute(exchange)
				.flatMap((Function<Route, Mono<?>>) r -> {
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);

					if (logger.isDebugEnabled()) {
						logger.debug("Mapping [" + getExchangeDesc(exchange) + "] to " + r);
					}

					exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, r);

					return Mono.just(webHandler);
				}).switchIfEmpty(Mono.empty().then(Mono.fromRunnable(() -> {
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
					if (logger.isTraceEnabled()) {
						logger.trace("No RouteDefinition found for [" + getExchangeDesc(exchange) + "]");
					}
				})));
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler, ServerWebExchange exchange) {
		// TODO: support cors configuration via properties on a route see gh-229
		// see RequestMappingHandlerMapping.initCorsConfiguration()
		// also see https://github.com/spring-projects/spring-framework/blob/master/spring-web/src/test/java/org/springframework/web/cors/reactive/CorsWebFilterTests.java	        
	    
		return super.getCorsConfiguration(handler, exchange);
	}

	//TODO: get desc from factory?
	private String getExchangeDesc(ServerWebExchange exchange) {
		StringBuilder out = new StringBuilder();
		out.append("Exchange: ");
		out.append(exchange.getRequest().getMethod());
		out.append(" ");
		out.append(exchange.getRequest().getURI());
		return out.toString();
	}

	protected Mono<Route> lookupRoute(ServerWebExchange exchange) {
		Mono<Route> mono = this.routeLocator
				.getRoutes()
				//individually filter routes so that filterWhen error delaying is not a problem
				.concatMap(route ->
						Mono.just(route)
								.filterWhen(r -> {
									// add the current route we are testing
									exchange.getAttributes().put(GATEWAY_PREDICATE_ROUTE_ATTR, r.getId());

									return r.getPredicate().apply(exchange);
								})


								//instead of immediately stopping main flux due to error, log and swallow it
								.doOnError(e -> logger.error("Error applying predicate for route: " + route.getId(), e))
								.onErrorResume(e -> Mono.empty())
				)
				.next()
				//TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("Route matched: " + route.getId());
					}
					validateRoute(route, exchange);
					return route;
				});
		return mono;
	}

	/**
	 * Validate the given handler against the current request.
	 * <p>The default implementation is empty. Can be overridden in subclasses,
	 * for example to enforce specific preconditions expressed in URL mappings.
	 * @param route the Route object to validate
	 * @param exchange current exchange
	 * @throws Exception if validation failed
	 */
	@SuppressWarnings("UnusedParameters")
	protected void validateRoute(Route route, ServerWebExchange exchange) {
	}

	protected String getSimpleName() {
		return "RoutePredicateHandlerMapping";
	}
}
