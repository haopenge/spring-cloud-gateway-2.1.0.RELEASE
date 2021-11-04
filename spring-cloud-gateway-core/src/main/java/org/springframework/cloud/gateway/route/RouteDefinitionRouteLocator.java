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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.event.FilterArgsEvent;
import org.springframework.cloud.gateway.event.PredicateArgsEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.support.ConfigurationUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.convert.ConversionService;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.validation.Validator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/**
 * {@link RouteLocator} that loads routes from a {@link RouteDefinitionLocator}
 * @author Spencer Gibb
 */
public class RouteDefinitionRouteLocator implements RouteLocator, BeanFactoryAware, ApplicationEventPublisherAware {
	protected final Log logger = LogFactory.getLog(getClass());

	public static final String DEFAULT_FILTERS = "defaultFilters";
	private final RouteDefinitionLocator routeDefinitionLocator;
	private final ConversionService conversionService;

	/**
	 * RoutePredicateFactory 映射
	 * key: {@link RoutePredicateFactory#name()}
	 */
	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();

	/**
	 * GateFilterFactory 映射
	 * key: {@link GatewayFilterFactory#name()}
	 */
	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();


	private final GatewayProperties gatewayProperties;
	private final SpelExpressionParser parser = new SpelExpressionParser();
	private BeanFactory beanFactory;
	private ApplicationEventPublisher publisher;

	public RouteDefinitionRouteLocator(
										// 1. 一个 RouteDefinitionLocator 对象
										RouteDefinitionLocator routeDefinitionLocator,

										// 2. Predicate 工厂列表，会被映射成 key 为 name, value 为 factory 的 Map。
									   // 可以猜想出 gateway 是如何根据 PredicateDefinition 中定义的 name 来匹配到相对应的 factory 了
									   List<RoutePredicateFactory> predicates,

									   // 3. Gateway Filter 工厂列表，同样会被映射成 key 为 name, value 为 factory 的 Map
									   List<GatewayFilterFactory> gatewayFilterFactories,

										// 4.外部化配置类
									   GatewayProperties gatewayProperties,

										ConversionService conversionService) {

		// 设置 RouteDefinitionLocator
		this.routeDefinitionLocator = routeDefinitionLocator;

		this.conversionService = conversionService;

		// 初始化 RoutePredicateFactory
		initFactories(predicates);

		// 初始化 RoutePredicateFactory
		gatewayFilterFactories.forEach(factory -> this.gatewayFilterFactories.put(factory.name(), factory));

		// 设置 GatewayProperties
		this.gatewayProperties = gatewayProperties;
	}

	@Autowired
	private Validator validator;

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	private void initFactories(List<RoutePredicateFactory> predicates) {
		predicates.forEach(factory -> {
			String key = factory.name();
			if (this.predicates.containsKey(key)) {
				this.logger.warn("A RoutePredicateFactory named "+ key
						+ " already exists, class: " + this.predicates.get(key)
						+ ". It will be overwritten.");
			}
			this.predicates.put(key, factory);
			if (logger.isInfoEnabled()) {
				logger.info("Loaded RoutePredicateFactory [" + key + "]");
			}
		});
	}

	// 实现 RouteLocator 的 getRoutes() 方法
	@Override
	public Flux<Route> getRoutes() {
		return this.routeDefinitionLocator.getRouteDefinitions()
				.map(this::convertToRoute) // ①
				//TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("RouteDefinition matched: " + route.getId());
					}
					return route;
				});


		/* TODO: trace logging
			if (logger.isTraceEnabled()) {
				logger.trace("RouteDefinition did not match: " + routeDefinition.getId());
			}*/
	}

	// ① 所调用的方法
	private Route convertToRoute(RouteDefinition routeDefinition) {
		AsyncPredicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);  // ②
		List<GatewayFilter> gatewayFilters = getFilters(routeDefinition); // ③

		return Route.async(routeDefinition)  // ④
				.asyncPredicate(predicate)
				.replaceFilters(gatewayFilters)
				.build();
	}

	@SuppressWarnings("unchecked")
	private List<GatewayFilter> loadGatewayFilters(String id, List<FilterDefinition> filterDefinitions) {
		List<GatewayFilter> filters = filterDefinitions.stream()
				.map(definition -> {
					GatewayFilterFactory factory = this.gatewayFilterFactories.get(definition.getName());
					if (factory == null) {
                        throw new IllegalArgumentException("Unable to find GatewayFilterFactory with name " + definition.getName());
					}
					Map<String, String> args = definition.getArgs();
					if (logger.isDebugEnabled()) {
						logger.debug("RouteDefinition " + id + " applying filter " + args + " to " + definition.getName());
					}

                    Map<String, Object> properties = factory.shortcutType().normalize(args, factory, this.parser, this.beanFactory);

                    Object configuration = factory.newConfig();

                    ConfigurationUtils.bind(configuration, properties, factory.shortcutFieldPrefix(),
							definition.getName(), validator, conversionService);

                    GatewayFilter gatewayFilter = factory.apply(configuration);
                    if (this.publisher != null) {
                        this.publisher.publishEvent(new FilterArgsEvent(this, id, properties));
                    }
                    return gatewayFilter;
				})
				.collect(Collectors.toList());

		ArrayList<GatewayFilter> ordered = new ArrayList<>(filters.size());
		for (int i = 0; i < filters.size(); i++) {
			GatewayFilter gatewayFilter = filters.get(i);
			if (gatewayFilter instanceof Ordered) {
				ordered.add(gatewayFilter);
			}
			else {
				ordered.add(new OrderedGatewayFilter(gatewayFilter, i + 1));
			}
		}

		return ordered;
	}

	/**
	 * 将 FilterDefinition 转化成 GatewayFilter
	 */
	private List<GatewayFilter> getFilters(RouteDefinition routeDefinition) {
		List<GatewayFilter> filters = new ArrayList<>();

		//TODO: support option to apply defaults after route specific filters?

		// ① 处理 GatewayProperties 中定义的默认的 FilterDefinition，转换成 GatewayFilter。
		if (!this.gatewayProperties.getDefaultFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(DEFAULT_FILTERS, this.gatewayProperties.getDefaultFilters()));
		}

		// ② 将 RouteDefinition 中定义的 FilterDefinition 转换成 GatewayFilter。
		if (!routeDefinition.getFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(routeDefinition.getId(), routeDefinition.getFilters()));
		}

		// ③ 对 GatewayFilter 进行排序，排序的详细逻辑请查阅 spring 中的 Ordered 接口
		AnnotationAwareOrderComparator.sort(filters);
		return filters;
	}

	private AsyncPredicate<ServerWebExchange> combinePredicates(RouteDefinition routeDefinition) {
		List<PredicateDefinition> predicates = routeDefinition.getPredicates();

		// ① 调用 lookup 方法，将列表中第一个 PredicateDefinition 转换成 AsyncPredicate
		AsyncPredicate<ServerWebExchange> predicate = lookup(routeDefinition, predicates.get(0));

		for (PredicateDefinition andPredicate : predicates.subList(1, predicates.size())) {

			// ② 循环调用，将列表中每一个 PredicateDefinition 都转换成 AsyncPredicate
			AsyncPredicate<ServerWebExchange> found = lookup(routeDefinition, andPredicate);
			// ③ 应用and操作，将所有的 AsyncPredicate 组合成一个 AsyncPredicate 对象。
			predicate = predicate.and(found);
		}

		return predicate;
	}

	@SuppressWarnings("unchecked")
	private AsyncPredicate<ServerWebExchange> lookup(RouteDefinition route, PredicateDefinition predicate) {

		// ① 根据 predicate 名称获取对应的 predicate factory
		RoutePredicateFactory<Object> factory = this.predicates.get(predicate.getName());
		if (factory == null) {
            throw new IllegalArgumentException("Unable to find RoutePredicateFactory with name " + predicate.getName());
		}

		// ② 获取 PredicateDefinition 中的 Map 类型参数，key 是固定字符串_genkey_ + 数字拼接而成。
		Map<String, String> args = predicate.getArgs();
		if (logger.isDebugEnabled()) {
			logger.debug("RouteDefinition " + route.getId() + " applying " + args + " to " + predicate.getName());
		}

		// ③ 对第 ② 步获得的参数作进一步转换，key为 config 类（工厂类中通过范型指定）的属性名称。
        Map<String, Object> properties = factory.shortcutType().normalize(args, factory, this.parser, this.beanFactory);

		// ④ 调用 factory 的 newConfig 方法创建一个 config 类对象
        Object config = factory.newConfig();
		// ⑤ 将第 ③ 步中产生的参数绑定到 config 对象上。
        ConfigurationUtils.bind(config, properties, factory.shortcutFieldPrefix(), predicate.getName(),
				validator, conversionService);
        if (this.publisher != null) {
            this.publisher.publishEvent(new PredicateArgsEvent(this, route.getId(), properties));
        }
		// ⑥ 将 cofing 作参数代入，调用 factory 的 applyAsync 方法创建 AsyncPredicate 对象
        return factory.applyAsync(config);
	}
}
