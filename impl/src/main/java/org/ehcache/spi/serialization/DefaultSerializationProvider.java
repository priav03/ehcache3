/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehcache.spi.serialization;

import org.ehcache.config.SerializerConfiguration;
import org.ehcache.config.serializer.DefaultSerializerConfiguration;
import org.ehcache.config.serializer.DefaultSerializationProviderConfiguration;
import org.ehcache.exceptions.CachePersistenceException;
import org.ehcache.spi.ServiceLocator;
import org.ehcache.spi.ServiceProvider;
import org.ehcache.spi.service.FileBasedPersistenceContext;
import org.ehcache.spi.service.LocalPersistenceService;
import org.ehcache.spi.service.LocalPersistenceService.PersistenceSpaceIdentifier;
import org.ehcache.spi.service.ServiceConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.ehcache.internal.serialization.CompactJavaSerializer;
import org.ehcache.internal.serialization.CompactPersistentJavaSerializer;

import static org.ehcache.spi.ServiceLocator.findSingletonAmongst;

/**
 * @author Ludovic Orban
 */
public class DefaultSerializationProvider implements SerializationProvider {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultSerializationProvider.class);

  private final TransientProvider transientProvider;
  private final PersistentProvider persistentProvider;
  
  public DefaultSerializationProvider(DefaultSerializationProviderConfiguration configuration) {
    if (configuration != null) {
      transientProvider = new TransientProvider(configuration.getTransientSerializers());
      persistentProvider = new PersistentProvider(configuration.getPersistentSerializers());
    } else {
      transientProvider = new TransientProvider(Collections.<Class<?>, Class<? extends Serializer<?>>>emptyMap());
      persistentProvider = new PersistentProvider(Collections.<Class<?>, Class<? extends Serializer<?>>>emptyMap());
    }
  }

  @Override
  public <T> Serializer<T> createKeySerializer(Class<T> clazz, ClassLoader classLoader, ServiceConfiguration<?>... configs) throws UnsupportedTypeException {
    if (findSingletonAmongst(PersistenceSpaceIdentifier.class, (Object[]) configs) == null) {
      return transientProvider.createKeySerializer(clazz, classLoader, configs);
    } else {
      return persistentProvider.createKeySerializer(clazz, classLoader, configs);
    }
  }

  @Override
  public <T> Serializer<T> createValueSerializer(Class<T> clazz, ClassLoader classLoader, ServiceConfiguration<?>... configs) throws UnsupportedTypeException {
    if (findSingletonAmongst(PersistenceSpaceIdentifier.class, (Object[]) configs) == null) {
      return transientProvider.createValueSerializer(clazz, classLoader, configs);
    } else {
      return persistentProvider.createValueSerializer(clazz, classLoader, configs);
    }
  }

  @Override
  public void start(ServiceProvider serviceProvider) {
    transientProvider.start(serviceProvider);
    persistentProvider.start(serviceProvider);
  }

  @Override
  public void stop() {
    transientProvider.stop();
    persistentProvider.stop();
  }

  static class TransientProvider extends AbstractProvider {

    public TransientProvider(Map<Class<?>, Class<? extends Serializer<?>>> serializers) {
      super(serializers);
    }

    @Override
    protected <T> Serializer<T> createSerializer(String suffix, Class<T> clazz, ClassLoader classLoader, DefaultSerializerConfiguration<T> config, ServiceConfiguration<?>... configs) throws UnsupportedTypeException {
      try {
        Class<? extends Serializer<T>> klazz = getClassFor(clazz, config, classLoader);
        return constructSerializer(clazz, klazz.getConstructor(ClassLoader.class), classLoader);
      } catch (NoSuchMethodException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void start(ServiceProvider serviceProvider) {
      if (!serializers.containsKey(Serializable.class)) {
        serializers.put(Serializable.class, (Class) CompactJavaSerializer.class);
      }
    }
  }

  static class PersistentProvider extends AbstractProvider {

    private volatile LocalPersistenceService persistence;
    
    private PersistentProvider(Map<Class<?>, Class<? extends Serializer<?>>> serializers) {
      super(serializers);
    }

    @Override
    protected <T> Serializer<T> createSerializer(String suffix, Class<T> clazz, ClassLoader classLoader, DefaultSerializerConfiguration<T> config, ServiceConfiguration<?>... configs) throws UnsupportedTypeException {
      Class<? extends Serializer<T>> klazz = getClassFor(clazz, config, classLoader);
      try {
        Constructor<? extends Serializer<T>> constructor = klazz.getConstructor(ClassLoader.class, FileBasedPersistenceContext.class);
        PersistenceSpaceIdentifier space = findSingletonAmongst(PersistenceSpaceIdentifier.class, (Object[]) configs);
        FileBasedPersistenceContext context = persistence.createPersistenceContextWithin(space, DefaultSerializationProvider.class.getSimpleName() + suffix);
        return constructSerializer(clazz, constructor, classLoader, context);
      } catch (NoSuchMethodException e) {
        throw new RuntimeException(e);
      } catch (CachePersistenceException e) {
        throw new RuntimeException(e);
      }
    }
    
    @Override
    public void start(ServiceProvider serviceProvider) {
      persistence = serviceProvider.getService(LocalPersistenceService.class);
      if (!serializers.containsKey(Serializable.class)) {
        serializers.put(Serializable.class, (Class) CompactPersistentJavaSerializer.class);
      }
    }
  }

  static abstract class AbstractProvider implements SerializationProvider  {

    protected final Map<Class<?>, Class<? extends Serializer<?>>> serializers;

    private AbstractProvider(Map<Class<?>, Class<? extends Serializer<?>>> serializers) {
      this.serializers = new LinkedHashMap<Class<?>, Class<? extends Serializer<?>>>(serializers);
    }

    @Override
    public <T> Serializer<T> createKeySerializer(Class<T> clazz, ClassLoader classLoader, ServiceConfiguration<?>... configs) throws UnsupportedTypeException {
      DefaultSerializerConfiguration<T> conf = find(SerializerConfiguration.Type.KEY, configs);
      return createSerializer("-Key", clazz, classLoader, conf, configs);
    }

    @Override
    public <T> Serializer<T> createValueSerializer(Class<T> clazz, ClassLoader classLoader, ServiceConfiguration<?>... configs) throws UnsupportedTypeException {
      DefaultSerializerConfiguration<T> conf = find(SerializerConfiguration.Type.VALUE, configs);
      return createSerializer("-Value", clazz, classLoader, conf, configs);
    }

    protected abstract <T> Serializer<T> createSerializer(String suffix, Class<T> clazz, ClassLoader classLoader, DefaultSerializerConfiguration<T> config, ServiceConfiguration<?>... configs) throws UnsupportedTypeException;

    protected <T> Class<? extends Serializer<T>> getClassFor(Class<T> clazz, DefaultSerializerConfiguration<T> config, ClassLoader classLoader) throws UnsupportedTypeException {
      if (config != null) {
        Class<? extends Serializer<T>> configured = config.getClazz();
        if (configured != null) {
          return configured;
        }
      }
      
      Class<? extends Serializer<T>> direct = (Class<? extends Serializer<T>>) serializers.get(clazz);
      if (direct != null) {
        return direct;
      }
      for (Map.Entry<Class<?>, Class<? extends Serializer<?>>> entry : serializers.entrySet()) {
        if (entry.getKey().isAssignableFrom(clazz)) {
          return (Class<? extends Serializer<T>>) entry.getValue();
        }
      }
      throw new UnsupportedTypeException("No serializer found for type '" + clazz.getName() + "'");
    }

    protected <T> Serializer<T> constructSerializer(Class<T> clazz, Constructor<? extends Serializer<T>> constructor, Object ... args) {
      try {
        Serializer<T> serializer = constructor.newInstance(args);
        LOG.info("Serializer for <{}> : {}", clazz.getName(), serializer);
        return serializer;
      } catch (InstantiationException e) {
        throw new RuntimeException(e);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      } catch (IllegalArgumentException e) {
        throw new AssertionError(e);
      } catch (InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void stop() {
      // no-op
    }

    
  }

  @SuppressWarnings("unchecked")
  private static <T> DefaultSerializerConfiguration<T> find(SerializerConfiguration.Type type, ServiceConfiguration<?>... serviceConfigurations) {
    DefaultSerializerConfiguration<T> result = null;

    Collection<DefaultSerializerConfiguration> serializationProviderConfigurations = ServiceLocator.findAmongst(DefaultSerializerConfiguration.class, (Object[]) serviceConfigurations);
    for (DefaultSerializerConfiguration serializationProviderConfiguration : serializationProviderConfigurations) {
      if (serializationProviderConfiguration.getType() == type) {
        if (result != null) {
          throw new IllegalArgumentException("Duplicate " + type + " serialization provider : " + serializationProviderConfiguration);
        }
        result = serializationProviderConfiguration;
      }
    }

    return result;
  }
}
