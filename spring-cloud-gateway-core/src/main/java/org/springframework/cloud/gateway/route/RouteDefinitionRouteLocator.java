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
	 * RoutePredicateFactory ??????
	 * key: {@link RoutePredicateFactory#name()}
	 */
	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();

	/**
	 * GateFilterFactory ??????
	 * key: {@link GatewayFilterFactory#name()}
	 */
	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();


	private final GatewayProperties gatewayProperties;
	private final SpelExpressionParser parser = new SpelExpressionParser();
	private BeanFactory beanFactory;
	private ApplicationEventPublisher publisher;

	public RouteDefinitionRouteLocator(
										// 1. ?????? RouteDefinitionLocator ??????
										RouteDefinitionLocator routeDefinitionLocator,

										// 2. Predicate ?????????????????????????????? key ??? name, value ??? factory ??? Map???
									   // ??????????????? gateway ??????????????? PredicateDefinition ???????????? name ???????????????????????? factory ???
									   List<RoutePredicateFactory> predicates,

									   // 3. Gateway Filter ???????????????????????????????????? key ??? name, value ??? factory ??? Map
									   List<GatewayFilterFactory> gatewayFilterFactories,

										// 4.??????????????????
									   GatewayProperties gatewayProperties,

										ConversionService conversionService) {

		// ?????? RouteDefinitionLocator
		this.routeDefinitionLocator = routeDefinitionLocator;

		this.conversionService = conversionService;

		// ????????? RoutePredicateFactory
		initFactories(predicates);

		// ????????? RoutePredicateFactory
		gatewayFilterFactories.forEach(factory -> this.gatewayFilterFactories.put(factory.name(), factory));

		// ?????? GatewayProperties
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

	// ?????? RouteLocator ??? getRoutes() ??????
	@Override
	public Flux<Route> getRoutes() {
		return this.routeDefinitionLocator.getRouteDefinitions()
				.map(this::convertToRoute) // ???
				 // TODO: error handling
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

	/**
	 * ???Definition ????????? Route ??????
	 */
	// ??? ??????????????????
	private Route convertToRoute(RouteDefinition routeDefinition) {

		// ?????????predicate
		AsyncPredicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);

		//  ?????????filter
		List<GatewayFilter> gatewayFilters = getFilters(routeDefinition);

		return Route.async(routeDefinition)  // ???
				.asyncPredicate(predicate)
				.replaceFilters(gatewayFilters)
				.build();
	}

	@SuppressWarnings("unchecked")
	private List<GatewayFilter> loadGatewayFilters(String id, List<FilterDefinition> filterDefinitions) {

		// ???filterDefinition ?????????filter
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


		// ???filter ????????? OrderFilter
		// ? ????????????????????? ??????????????????
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
	 * ??? FilterDefinition ????????? GatewayFilter
	 */
	private List<GatewayFilter> getFilters(RouteDefinition routeDefinition) {
		List<GatewayFilter> filters = new ArrayList<>();

		//TODO: support option to apply defaults after route specific filters?

		// ???  FilterDefinition???????????? GatewayFilter???
		if (!this.gatewayProperties.getDefaultFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(DEFAULT_FILTERS, this.gatewayProperties.getDefaultFilters()));
		}

		// ??? FilterDefinition ????????? GatewayFilter???
		if (!routeDefinition.getFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(routeDefinition.getId(), routeDefinition.getFilters()));
		}

		// ??? ??? GatewayFilter ????????????????????????????????????????????? spring ?????? Ordered ??????
		AnnotationAwareOrderComparator.sort(filters);
		return filters;
	}

	private AsyncPredicate<ServerWebExchange> combinePredicates(RouteDefinition routeDefinition) {
		List<PredicateDefinition> predicates = routeDefinition.getPredicates();

		// ??? ?????????????????? PredicateDefinition ????????? AsyncPredicate
		AsyncPredicate<ServerWebExchange> predicate = lookup(routeDefinition, predicates.get(0));

		for (PredicateDefinition andPredicate : predicates.subList(1, predicates.size())) {

			// ??? ???????????????????????????????????? PredicateDefinition ???????????? AsyncPredicate
			AsyncPredicate<ServerWebExchange> found = lookup(routeDefinition, andPredicate);
			// ??? ??????and????????????????????? AsyncPredicate ??????????????? AsyncPredicate ?????????
			predicate = predicate.and(found);
		}

		return predicate;
	}

	@SuppressWarnings("unchecked")
	private AsyncPredicate<ServerWebExchange> lookup(RouteDefinition route, PredicateDefinition predicate) {

		// ??? ??????predicate factory
		RoutePredicateFactory<Object> factory = this.predicates.get(predicate.getName());
		if (factory == null) {
            throw new IllegalArgumentException("Unable to find RoutePredicateFactory with name " + predicate.getName());
		}

		// ??? ?????? PredicateDefinition ?????? Map ???????????????key ??????????????????_genkey_ + ?????????????????????
		Map<String, String> args = predicate.getArgs();
		if (logger.isDebugEnabled()) {
			logger.debug("RouteDefinition " + route.getId() + " applying " + args + " to " + predicate.getName());
		}

		// ??? ?????? ??? ???????????????????????????????????????key??? config ?????????????????????????????????????????????????????????
        Map<String, Object> properties = factory.shortcutType().normalize(args, factory, this.parser, this.beanFactory);

		// ??? ???????????? config ?????????
        Object config = factory.newConfig();

		// ??? ?????? ??? ?????????????????????????????? config ????????????
        ConfigurationUtils.bind(config, properties, factory.shortcutFieldPrefix(), predicate.getName(), validator, conversionService);

        if (this.publisher != null) {
            this.publisher.publishEvent(new PredicateArgsEvent(this, route.getId(), properties));
        }
		// ??? ??? cofing ???????????????????????? factory ??? applyAsync ???????????? AsyncPredicate ??????
        return factory.applyAsync(config);
	}
}
