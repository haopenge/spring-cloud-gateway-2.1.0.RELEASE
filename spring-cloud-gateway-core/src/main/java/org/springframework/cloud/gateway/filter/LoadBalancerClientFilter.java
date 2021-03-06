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

package org.springframework.cloud.gateway.filter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.gateway.config.LoadBalancerProperties;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.Ordered;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * @author Spencer Gibb
 * @author Tim Ysewyn
 */
public class LoadBalancerClientFilter implements GlobalFilter, Ordered {

	private static final Log log = LogFactory.getLog(LoadBalancerClientFilter.class);
	public static final int LOAD_BALANCER_CLIENT_FILTER_ORDER = 10100;

	protected final LoadBalancerClient loadBalancer;

	private LoadBalancerProperties properties;

	public LoadBalancerClientFilter(LoadBalancerClient loadBalancer, LoadBalancerProperties properties) {
		this.loadBalancer = loadBalancer;
		this.properties = properties;
	}

	@Override
	public int getOrder() {
		return LOAD_BALANCER_CLIENT_FILTER_ORDER;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

		//  ??????URL

		/**
		 * ???{@link RouteToRequestUrlFilter#filter(org.springframework.web.server.ServerWebExchange, org.springframework.cloud.gateway.filter.GatewayFilterChain)} ??? ????????????
		 */
		URI url = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
		String schemePrefix = exchange.getAttribute(GATEWAY_SCHEME_PREFIX_ATTR);

		if (url == null || (!"lb".equals(url.getScheme()) && !"lb".equals(schemePrefix))) {
			return chain.filter(exchange);
		}

		// ??????????????????URI ??? GATEWAY_ORIGINAL_REQUEST_URL_ATTR
		//preserve the original url
		addOriginalRequestUrl(exchange, url);

		log.trace("LoadBalancerClientFilter url before: " + url);

		// ????????????????????????( ServiceInstance ) ???????????????????????????
		final ServiceInstance instance = choose(exchange);

		if (instance == null) {
			String msg = "Unable to find instance for " + url.getHost();
			if(properties.isUse404()) {
				throw new FourOFourNotFoundException(msg);
			}
			throw new NotFoundException(msg);
		}

		URI uri = exchange.getRequest().getURI();

		// if the `lb:<scheme>` mechanism was used, use `<scheme>` as the default,
		// if the loadbalancer doesn't provide one.
		String overrideScheme = instance.isSecure() ? "https" : "http";
		if (schemePrefix != null) {
			overrideScheme = url.getScheme();
		}

		URI requestUrl = loadBalancer.reconstructURI(new DelegatingServiceInstance(instance, overrideScheme), uri);

		log.trace("LoadBalancerClientFilter url chosen: " + requestUrl);

		// ?????? requestUrl ??? GATEWAY_REQUEST_URL_ATTR ????????? Routing ????????? GatewayFilter ????????????????????????????????????
		exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, requestUrl);

		// ??????????????????????????????
		return chain.filter(exchange);
	}

	protected ServiceInstance choose(ServerWebExchange exchange) {
		return loadBalancer.choose(((URI) exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR)).getHost());
	}
	@ResponseStatus(value = NOT_FOUND, reason = "The service was not found.")
	static class FourOFourNotFoundException extends RuntimeException {
		public FourOFourNotFoundException(String msg) {
			super(msg);
		}
	}

	class DelegatingServiceInstance implements ServiceInstance {
		final ServiceInstance delegate;
		private String overrideScheme;

		DelegatingServiceInstance(ServiceInstance delegate, String overrideScheme) {
			this.delegate = delegate;
			this.overrideScheme = overrideScheme;
		}

		@Override
		public String getServiceId() {
			return delegate.getServiceId();
		}

		@Override
		public String getHost() {
			return delegate.getHost();
		}

		@Override
		public int getPort() {
			return delegate.getPort();
		}

		@Override
		public boolean isSecure() {
			return delegate.isSecure();
		}

		@Override
		public URI getUri() {
			return delegate.getUri();
		}

		@Override
		public Map<String, String> getMetadata() {
			return delegate.getMetadata();
		}

		@Override
		public String getScheme() {
			String scheme = delegate.getScheme();
			if (scheme != null) {
				return scheme;
			}
			return this.overrideScheme;
		}

	}
}
