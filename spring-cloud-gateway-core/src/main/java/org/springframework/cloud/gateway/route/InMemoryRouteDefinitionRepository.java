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

package org.springframework.cloud.gateway.route;

import org.springframework.cloud.gateway.support.NotFoundException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Collections.synchronizedMap;

/**
 * 基于内存为存储器的 RouteDefinitionLocator
 * @author Spencer Gibb
 */
public class InMemoryRouteDefinitionRepository implements RouteDefinitionRepository {


	/**
	 * 路由配置映射
	 * key: 路由编号  {@link RouteDefinition#id}
	 */
	private final Map<String, RouteDefinition> routes = synchronizedMap(new LinkedHashMap<>());

	/**
	 * {@link org.springframework.cloud.gateway.actuate.GatewayControllerEndpoint#save }
	 * @param route 路由配置
	 */
	@Override
	public Mono<Void> save(Mono<RouteDefinition> route) {
		return route.flatMap( r -> {
			routes.put(r.getId(), r);
			return Mono.empty();
		});
	}

	@Override
	public Mono<Void> delete(Mono<String> routeId) {
		return routeId.flatMap(id -> {
			if (routes.containsKey(id)) {
				routes.remove(id);
				return Mono.empty();
			}
			return Mono.defer(() -> Mono.error(new NotFoundException("RouteDefinition not found: "+routeId)));
		});
	}

	/**
	 * {@link CompositeRouteDefinitionLocator#getRouteDefinitions()}
	 */
	@Override
	public Flux<RouteDefinition> getRouteDefinitions() {
		return Flux.fromIterable(routes.values());
	}
}
