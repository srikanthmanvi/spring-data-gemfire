/*
 * Copyright 2016 the original author or authors.
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

package org.springframework.data.gemfire.config.annotation;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.geode.cache.GemFireCache;
import org.apache.geode.internal.security.SecurityService;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.spring.LifecycleBeanPostProcessor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.OrderComparator;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.gemfire.util.CollectionUtils;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * The {@link ApacheShiroSecurityConfiguration} class is a Spring {@link Configuration @Configuration} component
 * responsible for configuring and initializing the Apache Shiro security framework in order to secure Apache Geode
 * administrative and data access operations.
 *
 * @author John Blum
 * @see org.apache.geode.cache.GemFireCache
 * @see org.apache.geode.internal.security.SecurityService
 * @see org.apache.shiro.mgt.DefaultSecurityManager
 * @see org.apache.shiro.realm.Realm
 * @see org.apache.shiro.spring.LifecycleBeanPostProcessor
 * @see org.springframework.beans.factory.BeanFactoryAware
 * @see org.springframework.beans.factory.ListableBeanFactory
 * @see org.springframework.context.annotation.Bean
 * @see org.springframework.context.annotation.Condition
 * @see org.springframework.context.annotation.Conditional
 * @see org.springframework.context.annotation.Configuration
 * @see org.springframework.data.gemfire.config.annotation.ApacheShiroSecurityConfiguration.ApacheShiroPresentCondition
 * @since 1.9.0
 */
@Configuration
@Conditional(ApacheShiroSecurityConfiguration.ApacheShiroPresentCondition.class)
@SuppressWarnings("unused")
public class ApacheShiroSecurityConfiguration implements BeanFactoryAware {

	private ListableBeanFactory beanFactory;

	/**
	 * @inheritDoc
	 */
	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		Assert.isInstanceOf(ListableBeanFactory.class, beanFactory);
		this.beanFactory = (ListableBeanFactory) beanFactory;
	}

	/**
	 * Returns a reference to the Spring {@link BeanFactory}.
	 *
	 * @return a reference to the Spring {@link BeanFactory}.
	 * @throws IllegalStateException if the Spring {@link BeanFactory} was not properly initialized.
	 * @see org.springframework.beans.factory.BeanFactory
	 */
	protected ListableBeanFactory getBeanFactory() {
		Assert.state(this.beanFactory != null, "BeanFactory was not properly initialized");
		return this.beanFactory;
	}

	/**
	 * {@link Bean} definition to define, configure and register an Apache Shiro Spring
	 * {@link LifecycleBeanPostProcessor} to automatically call lifecycle callback methods
	 * on Shiro security components during Spring container initialization and destruction phases.
	 *
	 * @return an instance of the Apache Shiro Spring {@link LifecycleBeanPostProcessor} to handle the lifecycle
	 * of Apache Shiro security framework components.
	 * @see org.apache.shiro.spring.LifecycleBeanPostProcessor
	 */
	@Bean
	public BeanPostProcessor shiroLifecycleBeanPostProcessor() {
		return new LifecycleBeanPostProcessor();
	}

	/**
	 * {@link Bean} definition to define, configure and register an Apache Shiro
	 * {@link org.apache.shiro.mgt.SecurityManager} implementation to secure Apache Geode.
	 *
	 * The registration of this {@link Bean} definition is dependent upon whether the user is using Apache Shiro
	 * to secure Apache Geode, which is determined by the presence of Apache Shiro {@link Realm Realms}
	 * declared in the Spring {@link org.springframework.context.ApplicationContext}.
	 *
	 * This {@link Bean} definition declares a dependency on the Apache Geode {@link GemFireCache} instance
	 * in order to ensure the Geode cache is created and initialized first.  This ensures that any internal Geode
	 * security configuration logic is evaluated and processed before SDG attempts to configure Apache Shiro
	 * as Apache Geode's security provider.
	 *
	 * Additionally, this {@link Bean} definition will register the Apache Shiro
	 * {@link org.apache.geode.security.SecurityManager} with the Apache Shiro security framework
	 *
	 * Finally, this method proceeds to enable Apache Geode security.

	 * @return an Apache Shiro {@link org.apache.shiro.mgt.SecurityManager} implementation used to secure Apache Geode.
	 * @throws IllegalStateException if an Apache Shiro {@link org.apache.shiro.mgt.SecurityManager} was registered
	 * with the Apache Shiro security framework but Apache Geode security could not be enabled.
	 * @see org.apache.shiro.mgt.SecurityManager
	 * @see #registerSecurityManager(org.apache.shiro.mgt.SecurityManager)
	 * @see #enableApacheGeodeSecurity()
	 * @see #resolveRealms()
	 * @see #registerSecurityManager(org.apache.shiro.mgt.SecurityManager)
	 * @see #enableApacheGeodeSecurity()
	 */
	@Bean
	public org.apache.shiro.mgt.SecurityManager shiroSecurityManager(GemFireCache gemfireCache) {
		org.apache.shiro.mgt.SecurityManager shiroSecurityManager = null;

		List<Realm> realms = resolveRealms();

		if (!realms.isEmpty()) {
			shiroSecurityManager = registerSecurityManager(new DefaultSecurityManager(realms));

			if (!enableApacheGeodeSecurity()) {
				throw new IllegalStateException("Failed to enable security services in Apache Geode");
			}
		}

		return shiroSecurityManager;
	}

	/**
	 * Resolves all the Apache Shiro {@link Realm Realms} declared and configured as Spring managed beans
	 * in the Spring {@link org.springframework.context.ApplicationContext}.
	 *
	 * This method will order the Realms according to priority order to ensure that the Apache Shiro Realms
	 * are applied in the correct sequence, as declared/configured.
	 *
	 * @return a {@link List} of all Apache Shiro {@link Realm Realms} declared and configured as Spring managed beans
	 * in the Spring {@link org.springframework.context.ApplicationContext}.
	 * @see org.springframework.beans.factory.ListableBeanFactory#getBeansOfType(Class, boolean, boolean)
	 * @see org.springframework.core.OrderComparator
	 * @see org.apache.shiro.realm.Realm
	 */
	protected List<Realm> resolveRealms() {
		try {
			Map<String, Realm> realmBeans = getBeanFactory().getBeansOfType(Realm.class, false, true);
			List<Realm> realms = new ArrayList<>(CollectionUtils.nullSafeMap(realmBeans).values());
			Collections.sort(realms, OrderComparator.INSTANCE);
			return realms;
		}
		catch (Exception ignore) {
			return Collections.emptyList();
		}
	}

	/**
	 * Registers the given Apache Shiro {@link org.apache.shiro.mgt.SecurityManager} with the Apache Shiro
	 * security framework.
	 *
	 * @param securityManager {@link org.apache.shiro.mgt.SecurityManager} to register.
	 * @return the given {@link org.apache.shiro.mgt.SecurityManager} reference.
	 * @throws IllegalArgumentException if {@link org.apache.shiro.mgt.SecurityManager} is {@literal null}.
	 * @see org.apache.shiro.SecurityUtils#setSecurityManager(org.apache.shiro.mgt.SecurityManager)
	 * @see org.apache.shiro.mgt.SecurityManager
	 */
	protected org.apache.shiro.mgt.SecurityManager registerSecurityManager(
			org.apache.shiro.mgt.SecurityManager securityManager) {

		Assert.notNull(securityManager, "The Apache Shiro SecurityManager to register must not be null");

		SecurityUtils.setSecurityManager(securityManager);

		return securityManager;
	}

	/**
	 * Sets the Apache Geode, Integrated Security {@link SecurityService} property {@literal isIntegratedSecurity}
	 * to {@literal true} to indicate that Apache Geode security is enabled.
	 *
	 * @return a boolean value indicating whether Apache Geode's Integrated Security framework services
	 * were successfully enabled.
	 * @see org.apache.geode.internal.security.SecurityService#getSecurityService()
	 */
	protected boolean enableApacheGeodeSecurity() {
		SecurityService securityService = SecurityService.getSecurityService();

		if (securityService != null) {
			String isIntegratedSecurityFieldName = "isIntegratedSecurity";

			Field isIntegratedSecurity = ReflectionUtils.findField(securityService.getClass(),
				isIntegratedSecurityFieldName, Boolean.class);

			isIntegratedSecurity = (isIntegratedSecurity != null ? isIntegratedSecurity
				: ReflectionUtils.findField(securityService.getClass(), isIntegratedSecurityFieldName, Boolean.TYPE));

			if (isIntegratedSecurity != null) {
				ReflectionUtils.makeAccessible(isIntegratedSecurity);
				ReflectionUtils.setField(isIntegratedSecurity, securityService, Boolean.TRUE);

				return true;
			}
		}

		return false;
	}

	/**
	 * A Spring {@link Condition} to determine whether the user has included (declared) the 'shiro-spring' dependency
	 * on their application's classpath, which is necessary for configuring Apache Shiro to secure Apache Geode
	 * in a Spring context.
	 *
	 * @see org.springframework.context.annotation.Condition
	 */
	public static class ApacheShiroPresentCondition implements Condition {

		protected static final String APACHE_SHIRO_LIFECYCLE_BEAN_POST_PROCESSOR_CLASS_NAME =
			"org.apache.shiro.spring.LifecycleBeanPostProcessor";

		/**
		 * @inheritDoc
		 */
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return ClassUtils.isPresent(APACHE_SHIRO_LIFECYCLE_BEAN_POST_PROCESSOR_CLASS_NAME,
				context.getClassLoader());
		}
	}
}