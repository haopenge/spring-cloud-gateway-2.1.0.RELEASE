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

import org.springframework.cloud.gateway.support.NameUtils;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.springframework.util.StringUtils.tokenizeToStringArray;

/**
 * @author Spencer Gibb
 */
@Validated
public class FilterDefinition {

	/**
	 * 过滤器定义名字
	 */
	@NotNull
	private String name;

	/**
	 * 参数数组
	 */
	private Map<String, String> args = new LinkedHashMap<>();

	public FilterDefinition() {
	}

	/**
	 * 根据 text 创建 FilterDefinition
	 * @param text    格式 ${name}=${args[0]},${args[1]}...${args[n]}
	 *                例如 AddRequestParameter=foo, bar
	 */
	public FilterDefinition(String text) {
		int eqIdx = text.indexOf('=');
		if (eqIdx <= 0) {
			setName(text);
			return;
		}
		setName(text.substring(0, eqIdx));

		String[] args = tokenizeToStringArray(text.substring(eqIdx+1), ",");

		for (int i=0; i < args.length; i++) {
			this.args.put(NameUtils.generateName(i), args[i]);
		}
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Map<String, String> getArgs() {
		return args;
	}

	public void setArgs(Map<String, String> args) {
		this.args = args;
	}

	public void addArg(String key, String value) {
		this.args.put(key, value);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		FilterDefinition that = (FilterDefinition) o;
		return Objects.equals(name, that.name) &&
				Objects.equals(args, that.args);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, args);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("FilterDefinition{");
		sb.append("name='").append(name).append('\'');
		sb.append(", args=").append(args);
		sb.append('}');
		return sb.toString();
	}
}
