/*
 * Copyright 2016-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.core;

import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextStoppedEvent;
import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.support.TransactionSupport;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The {@link ProducerFactory} implementation for a {@code singleton} shared {@link Producer} instance.
 * <p>
 * This implementation will return the same {@link Producer} instance (if transactions are
 * not enabled) for the provided {@link Map} {@code configs} and optional {@link Serializer}
 * implementations on each {@link #createProducer()} invocation.
 * <p>
 * If you are using {@link Serializer}s that have no-arg constructors and require no setup, then simplest to
 * specify {@link Serializer} classes against {@link ProducerConfig#KEY_SERIALIZER_CLASS_CONFIG} and
 * {@link ProducerConfig#VALUE_SERIALIZER_CLASS_CONFIG} keys in the {@code configs} passed to the
 * {@link DefaultKafkaProducerFactory} constructor.
 * <p>
 * If that is not possible, but you are sure that at least one of the following is true:
 * <ul>
 *     <li>only one {@link Producer} will use the {@link Serializer}s</li>
 *     <li>you are using {@link Serializer}s that may be shared between {@link Producer} instances (and specifically
 *     that their close() method is a no-op)</li>
 *     <li>you are certain that there is no risk of any single {@link Producer} being closed while other
 *     {@link Producer} instances with the same {@link Serializer}s are in use</li>
 * </ul>
 * then you can pass in {@link Serializer} instances for one or both of the key and value serializers.
 * <p>
 * If none of the above is true then you may provide a {@link Supplier} function for one or both {@link Serializer}s
 * which will be used to obtain {@link Serializer}(s) each time a {@link Producer} is created by the factory.
 * <p>
 * The {@link Producer} is wrapped and the underlying {@link KafkaProducer} instance is
 * not actually closed when {@link Producer#close()} is invoked. The {@link KafkaProducer}
 * is physically closed when {@link DisposableBean#destroy()} is invoked or when the
 * application context publishes a {@link ContextStoppedEvent}. You can also invoke
 * {@link #reset()}.
 * <p>
 * Setting {@link #setTransactionIdPrefix(String)} enables transactions; in which case, a
 * cache of producers is maintained; closing a producer returns it to the cache. The
 * producers are closed and the cache is cleared when the factory is destroyed, the
 * application context stopped, or the {@link #reset()} method is called.
 *
 * @param <K> the key type.
 * @param <V> the value type.
 *
 * @author Gary Russell
 * @author Murali Reddy
 * @author Nakul Mishra
 * @author Artem Bilan
 * @author Chris Gilbert
 */
public class DefaultKafkaProducerFactory<K, V> implements ProducerFactory<K, V>, ApplicationContextAware,
		ApplicationListener<ContextStoppedEvent>, DisposableBean {

	/**
	 * The default close timeout duration as 30 seconds.
	 */
	public static final Duration DEFAULT_PHYSICAL_CLOSE_TIMEOUT = Duration.ofSeconds(30);

	private static final LogAccessor LOGGER = new LogAccessor(LogFactory.getLog(DefaultKafkaProducerFactory.class));

	private final Map<String, Object> configs;

	private final AtomicInteger transactionIdSuffix = new AtomicInteger();

	private final Map<String, BlockingQueue<CloseSafeProducer<K, V>>> cache = new ConcurrentHashMap<>();

	private final Map<String, CloseSafeProducer<K, V>> consumerProducers = new HashMap<>();

	private final AtomicInteger clientIdCounter = new AtomicInteger();

	private Supplier<Serializer<K>> keySerializerSupplier;

	private Supplier<Serializer<V>> valueSerializerSupplier;

	private Duration physicalCloseTimeout = DEFAULT_PHYSICAL_CLOSE_TIMEOUT;

	private String transactionIdPrefix;

	private ApplicationContext applicationContext;
	/**
	 *
	 */
	private boolean producerPerConsumerPartition = true;

	private boolean producerPerThread;

	private ThreadLocal<CloseSafeProducer<K, V>> threadBoundProducers;

	private String clientIdPrefix;

	private volatile CloseSafeProducer<K, V> producer;

	/**
	 * Construct a factory with the provided configuration.
	 * @param configs the configuration.
	 */
	public DefaultKafkaProducerFactory(Map<String, Object> configs) {
		this(configs, () -> null, () -> null);
	}

	/**
	 * Construct a factory with the provided configuration and {@link Serializer}s.
	 * Also configures a {@link #transactionIdPrefix} as a value from the
	 * {@link ProducerConfig#TRANSACTIONAL_ID_CONFIG} if provided.
	 * This config is going to be overridden with a suffix for target {@link Producer} instance.
	 * @param configs the configuration.
	 * @param keySerializer the key {@link Serializer}.
	 * @param valueSerializer the value {@link Serializer}.
	 */
	public DefaultKafkaProducerFactory(Map<String, Object> configs,
			@Nullable Serializer<K> keySerializer,
			@Nullable Serializer<V> valueSerializer) {

		this(configs, () -> keySerializer, () -> valueSerializer);
	}

	/**
	 * Construct a factory with the provided configuration and {@link Serializer} Suppliers.
	 * Also configures a {@link #transactionIdPrefix} as a value from the
	 * {@link ProducerConfig#TRANSACTIONAL_ID_CONFIG} if provided.
	 * This config is going to be overridden with a suffix for target {@link Producer} instance.
	 * @param configs the configuration.
	 * @param keySerializerSupplier the key {@link Serializer} supplier function.
	 * @param valueSerializerSupplier the value {@link Serializer} supplier function.
	 * @since 2.3
	 */
	public DefaultKafkaProducerFactory(Map<String, Object> configs,
			@Nullable Supplier<Serializer<K>> keySerializerSupplier,
			@Nullable Supplier<Serializer<V>> valueSerializerSupplier) {

		this.configs = new HashMap<>(configs);
		this.keySerializerSupplier = keySerializerSupplier == null ? () -> null : keySerializerSupplier;
		this.valueSerializerSupplier = valueSerializerSupplier == null ? () -> null : valueSerializerSupplier;
		if (this.clientIdPrefix == null && configs.get(ProducerConfig.CLIENT_ID_CONFIG) instanceof String) {
			this.clientIdPrefix = (String) configs.get(ProducerConfig.CLIENT_ID_CONFIG);
		}

		String txId = (String) this.configs.get(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
		if (StringUtils.hasText(txId)) {
			setTransactionIdPrefix(txId);
			LOGGER.info(() -> "If 'setTransactionIdPrefix()' is not configured, "
					+ "the existing 'transactional.id' config with value: '" + txId
					+ "' will be suffixed for concurrent transaction support.");
			this.configs.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
		}
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	public void setKeySerializer(@Nullable Serializer<K> keySerializer) {
		this.keySerializerSupplier = () -> keySerializer;
	}

	public void setValueSerializer(@Nullable Serializer<V> valueSerializer) {
		this.valueSerializerSupplier = () -> valueSerializer;
	}

	/**
	 * The time to wait when physically closing the producer via the factory rather than
	 * closing the producer itself (when {@link #reset()}, {@link #destroy()
	 * #closeProducerFor(String)}, or {@link #closeThreadBoundProducer()} are invoked).
	 * Specified in seconds; default {@link #DEFAULT_PHYSICAL_CLOSE_TIMEOUT}.
	 * @param physicalCloseTimeout the timeout in seconds.
	 * @since 1.0.7
	 */
	public void setPhysicalCloseTimeout(int physicalCloseTimeout) {
		this.physicalCloseTimeout = Duration.ofSeconds(physicalCloseTimeout);
	}

	/**
	 * Set a prefix for the {@link ProducerConfig#TRANSACTIONAL_ID_CONFIG} config. By
	 * default a {@link ProducerConfig#TRANSACTIONAL_ID_CONFIG} value from configs is used
	 * as a prefix in the target producer configs.
	 * @param transactionIdPrefix the prefix.
	 * @since 1.3
	 */
	public final void setTransactionIdPrefix(String transactionIdPrefix) {
		Assert.notNull(transactionIdPrefix, "'transactionIdPrefix' cannot be null");
		this.transactionIdPrefix = transactionIdPrefix;
		enableIdempotentBehaviour();
	}

	protected String getTransactionIdPrefix() {
		return this.transactionIdPrefix;
	}

	/**
	 * Set to true to create a producer per thread instead of singleton that is shared by
	 * all clients. Clients <b>must</b> call {@link #closeThreadBoundProducer()} to
	 * physically close the producer when it is no longer needed. These producers will not
	 * be closed by {@link #destroy()} or {@link #reset()}.
	 * @param producerPerThread true for a producer per thread.
	 * @since 2.3
	 * @see #closeThreadBoundProducer()
	 */
	public void setProducerPerThread(boolean producerPerThread) {
		this.producerPerThread = producerPerThread;
		this.threadBoundProducers = new ThreadLocal<>();
	}

	/**
	 * Set to false to revert to the previous behavior of a simple incrementing
	 * transactional.id suffix for each producer instead of maintaining a producer
	 * for each group/topic/partition.
	 * @param producerPerConsumerPartition false to revert.
	 * @since 1.3.7
	 */
	public void setProducerPerConsumerPartition(boolean producerPerConsumerPartition) {
		this.producerPerConsumerPartition = producerPerConsumerPartition;
	}

	/**
	 * Return the producerPerConsumerPartition.
	 * @return the producerPerConsumerPartition.
	 * @since 1.3.8
	 */
	@Override
	public boolean isProducerPerConsumerPartition() {
		return this.producerPerConsumerPartition;
	}

	/**
	 * Return an unmodifiable reference to the configuration map for this factory.
	 * Useful for cloning to make a similar factory.
	 * @return the configs.
	 * @since 1.3
	 */
	public Map<String, Object> getConfigurationProperties() {
		return Collections.unmodifiableMap(this.configs);
	}

	/**
	 * When set to 'true', the producer will ensure that exactly one copy of each message is written in the stream.
	 */
	private void enableIdempotentBehaviour() {
		Object previousValue = this.configs.putIfAbsent(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
		if (Boolean.FALSE.equals(previousValue)) {
			LOGGER.debug(() -> "The '" + ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG
					+ "' is set to false, may result in duplicate messages");
		}
	}

	@Override
	public boolean transactionCapable() {
		return this.transactionIdPrefix != null;
	}

	@SuppressWarnings("resource")
	@Override
	public void destroy() {
		CloseSafeProducer<K, V> producerToClose = this.producer;
		this.producer = null;
		if (producerToClose != null) {
			producerToClose.getDelegate().close(this.physicalCloseTimeout);
		}
		this.cache.values().forEach(queue -> {
			CloseSafeProducer<K, V> next = queue.poll();
			while (next != null) {
				try {
					next.getDelegate().close(this.physicalCloseTimeout);
				}
				catch (Exception e) {
					LOGGER.error(e, "Exception while closing producer");
				}
				next = queue.poll();
			}
		});
		this.cache.clear();
		synchronized (this.consumerProducers) {
			this.consumerProducers.forEach(
					(k, v) -> v.getDelegate().close(this.physicalCloseTimeout));
			this.consumerProducers.clear();
		}
	}

	@Override
	public void onApplicationEvent(ContextStoppedEvent event) {
		if (event.getApplicationContext().equals(this.applicationContext)) {
			reset();
		}
	}

	/**
	 * Close the {@link Producer}(s) and clear the cache of transactional
	 * {@link Producer}(s).
	 * @since 2.2
	 */
	@Override
	public void reset() {
		try {
			destroy();
		}
		catch (Exception e) {
			LOGGER.error(e, "Exception while closing producer");
		}
	}

	@Override
	public Producer<K, V> createProducer() {
		return createProducer(this.transactionIdPrefix);
	}

	@Override
	public Producer<K, V> createProducer(@Nullable String txIdPrefixArg) {
		String txIdPrefix = txIdPrefixArg == null ? this.transactionIdPrefix : txIdPrefixArg;
		return doCreateProducer(txIdPrefix);
	}

	@Override
	public Producer<K, V> createNonTransactionalProducer() {
		// 创建非事务型producer实例则不需要事务ID前缀
		return doCreateProducer(null);
	}

	private Producer<K, V> doCreateProducer(@Nullable String txIdPrefix) {
		// 如果存在事务ID前缀
		if (txIdPrefix != null) {
			// 如果需要避免僵尸实例问题，则可以在ProducerFactory中开启producerPerConsumerPartition配置
			// 僵尸实例问题：某个producer实例临时失联，新的producer会代替失联的实例，而失联的producer恢复后，就会出现两条同样的消息
			if (this.producerPerConsumerPartition) {
				return createTransactionalProducerForPartition(txIdPrefix);
			} else {
				// 如果开发者可以自己处理重复调用问题，则直接创建事务型producer实例
				return createTransactionalProducer(txIdPrefix);
			}
		}
		// 如果需要为每个线程创建producer
		// 为什么需要为每个线程创建producer
		// 多个线程调用同一个producer实例时，一个线程进行flush()操作，会阻塞其他的线程
		if (this.producerPerThread) {
			CloseSafeProducer<K, V> tlProducer = this.threadBoundProducers.get();
			// 则为每个线程单独创建producer
			if (tlProducer == null) {
				tlProducer = new CloseSafeProducer<>(createKafkaProducer());
				this.threadBoundProducers.set(tlProducer);
			}
			return tlProducer;
		}
		// 此种情况默认为我们经常使用的producer实例
		// 即进程内的线程共用同一个producer
		if (this.producer == null) {
			synchronized (this) {
				// 需要双重校验所保证线程安全
				if (this.producer == null) {
					// 创建producer实例
					this.producer = new CloseSafeProducer<>(createKafkaProducer());
				}
			}
		}
		return this.producer;
	}

	/**
	 * 子类的实现需要将原生KafkaProducer包装为{@link CloseSafeProducer}
	 * @return producer实例
	 */
	protected Producer<K, V> createKafkaProducer() {
		// 判断是否指定了client.id配置，并以自定义的client.id或者默认的client.id创建producer实例
		if (this.clientIdPrefix == null) {
			return createRawProducer(this.configs);
		} else {
			Map<String, Object> newConfigs = new HashMap<>(this.configs);
			// 如果指定了client.id前缀配置，则producer实例名称将会按照递增的顺序依次增加
			newConfigs.put(ProducerConfig.CLIENT_ID_CONFIG,
					this.clientIdPrefix + "-" + this.clientIdCounter.incrementAndGet());
			return createRawProducer(newConfigs);
		}
	}

	protected Producer<K, V> createTransactionalProducerForPartition() {
		return createTransactionalProducerForPartition(this.transactionIdPrefix);
	}

	protected Producer<K, V> createTransactionalProducerForPartition(String txIdPrefix) {
		// 获取事务ID的尾缀
		String suffix = TransactionSupport.getTransactionIdSuffix();
		if (suffix == null) {
			// 不存在事务ID尾缀的情况下，直接使用事务ID创建producer实例
			return createTransactionalProducer(txIdPrefix);
		} else {
			// 存在事务ID尾缀的情况下，则需要对已创建的producer实例缓存进行加锁
			synchronized (this.consumerProducers) {
				// 没有给定尾缀的producer实例，需要根据事务ID前缀和尾缀分别创建一个producer实例，并放入producer实例缓存中
				if (!this.consumerProducers.containsKey(suffix)) {
					CloseSafeProducer<K, V> newProducer = doCreateTxProducer(txIdPrefix, suffix,
							this::removeConsumerProducer);
					this.consumerProducers.put(suffix, newProducer);
					return newProducer;
				} else {
					// 如果已经创建事务ID尾缀的producer实例，则直接返回此实例
					return this.consumerProducers.get(suffix);
				}
			}
		}
	}

	private void removeConsumerProducer(CloseSafeProducer<K, V> producerToRemove) {
		synchronized (this.consumerProducers) {
			Iterator<Entry<String, CloseSafeProducer<K, V>>> iterator = this.consumerProducers.entrySet().iterator();
			while (iterator.hasNext()) {
				if (iterator.next().getValue().equals(producerToRemove)) {
					iterator.remove();
					break;
				}
			}
		}
	}

	/**
	 * Subclasses must return a producer from the {@link #getCache()} or a
	 * new raw producer wrapped in a {@link CloseSafeProducer}.
	 * @return the producer - cannot be null.
	 * @since 1.3
	 */
	protected Producer<K, V> createTransactionalProducer() {
		return createTransactionalProducer(this.transactionIdPrefix);
	}

	protected Producer<K, V> createTransactionalProducer(String txIdPrefix) {
		BlockingQueue<CloseSafeProducer<K, V>> queue = getCache(txIdPrefix);
		Assert.notNull(queue, () -> "No cache found for " + txIdPrefix);
		// 是否已经创建producer实例
		Producer<K, V> cachedProducer = queue.poll();
		if (cachedProducer == null) {
			// 创建producer实例
			return doCreateTxProducer(txIdPrefix, "" + this.transactionIdSuffix.getAndIncrement(), null);
		} else {
			return cachedProducer;
		}
	}

	private CloseSafeProducer<K, V> doCreateTxProducer(String prefix, String suffix,
			@Nullable Consumer<CloseSafeProducer<K, V>> remover) {
		Producer<K, V> newProducer;
		// producuer的配置
		Map<String, Object> newProducerConfigs = new HashMap<>(this.configs);
		newProducerConfigs.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, prefix + suffix);
		if (this.clientIdPrefix != null) {
			newProducerConfigs.put(ProducerConfig.CLIENT_ID_CONFIG,
					this.clientIdPrefix + "-" + this.clientIdCounter.incrementAndGet());
		}
		// 创建producer实例
		newProducer = createRawProducer(newProducerConfigs);
		// 开启producer的事务
		newProducer.initTransactions();
		// 使用Spring包装的一层，可友好关闭的producer实例
		return new CloseSafeProducer<>(newProducer, getCache(prefix), remover,
				(String) newProducerConfigs.get(ProducerConfig.TRANSACTIONAL_ID_CONFIG));
	}

	/**
	 * 创建原始的KafkaProducer实例
	 * @param configs Kafka producer实例
	 * @return 原生的KafkaProducer实例
	 */
	protected Producer<K, V> createRawProducer(Map<String, Object> configs) {
		// 直接实例化Kafka底层的API
		return new KafkaProducer<>(configs, this.keySerializerSupplier.get(), this.valueSerializerSupplier.get());
	}

	@Nullable
	protected BlockingQueue<CloseSafeProducer<K, V>> getCache() {
		return getCache(this.transactionIdPrefix);
	}

	@Nullable
	protected BlockingQueue<CloseSafeProducer<K, V>> getCache(String txIdPrefix) {
		if (txIdPrefix == null) {
			return null;
		}
		return this.cache.computeIfAbsent(txIdPrefix, txId -> new LinkedBlockingQueue<>());
	}

	@Override
	public void closeProducerFor(String suffix) {
		if (this.producerPerConsumerPartition) {
			synchronized (this.consumerProducers) {
				CloseSafeProducer<K, V> removed = this.consumerProducers.remove(suffix);
				if (removed != null) {
					removed.getDelegate().close(this.physicalCloseTimeout);
				}
			}
		}
	}

	/**
	 * When using {@link #setProducerPerThread(boolean)} (true), call this method to close
	 * and release this thread's producer. Thread bound producers are <b>not</b> closed by
	 * {@link #destroy()} or {@link #reset()} methods.
	 * @since 2.3
	 * @see #setProducerPerThread(boolean)
	 */
	@Override
	public void closeThreadBoundProducer() {
		CloseSafeProducer<K, V> tlProducer = this.threadBoundProducers.get();
		if (tlProducer != null) {
			tlProducer.getDelegate().close(this.physicalCloseTimeout);
			this.threadBoundProducers.remove();
		}
	}

	/**
	 * A wrapper class for the delegate.
	 *
	 * @param <K> the key type.
	 * @param <V> the value type.
	 *
	 */
	protected static class CloseSafeProducer<K, V> implements Producer<K, V> {

		private static final Duration CLOSE_TIMEOUT_AFTER_TX_TIMEOUT = Duration.ofMillis(0);

		private final Producer<K, V> delegate;

		private final BlockingQueue<CloseSafeProducer<K, V>> cache;

		private final Consumer<CloseSafeProducer<K, V>> removeConsumerProducer;
		/**
		 * 事务ID
		 */
		private final String txId;

		private volatile Exception txFailed;

		CloseSafeProducer(Producer<K, V> delegate) {
			this(delegate, null, null);
			Assert.isTrue(!(delegate instanceof CloseSafeProducer), "Cannot double-wrap a producer");
		}

		CloseSafeProducer(Producer<K, V> delegate, BlockingQueue<CloseSafeProducer<K, V>> cache) {
			this(delegate, cache, null);
		}

		CloseSafeProducer(Producer<K, V> delegate, @Nullable BlockingQueue<CloseSafeProducer<K, V>> cache,
				@Nullable Consumer<CloseSafeProducer<K, V>> removeConsumerProducer) {

			this(delegate, cache, removeConsumerProducer, null);
		}

		CloseSafeProducer(Producer<K, V> delegate, @Nullable BlockingQueue<CloseSafeProducer<K, V>> cache,
				@Nullable Consumer<CloseSafeProducer<K, V>> removeConsumerProducer, @Nullable String txId) {

			this.delegate = delegate;
			this.cache = cache;
			this.removeConsumerProducer = removeConsumerProducer;
			this.txId = txId;
			LOGGER.debug(() -> "Created new Producer: " + this);
		}

		Producer<K, V> getDelegate() {
			return this.delegate;
		}

		@Override
		public Future<RecordMetadata> send(ProducerRecord<K, V> record) {
			LOGGER.trace(() -> toString() + " send(" + record + ")");
			return this.delegate.send(record);
		}

		@Override
		public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback) {
			LOGGER.trace(() -> toString() + " send(" + record + ")");
			return this.delegate.send(record, callback);
		}

		@Override
		public void flush() {
			LOGGER.trace(() -> toString() + " flush()");
			this.delegate.flush();
		}

		@Override
		public List<PartitionInfo> partitionsFor(String topic) {
			return this.delegate.partitionsFor(topic);
		}

		@Override
		public Map<MetricName, ? extends Metric> metrics() {
			return this.delegate.metrics();
		}

		@Override
		public void initTransactions() {
			this.delegate.initTransactions();
		}

		@Override
		public void beginTransaction() throws ProducerFencedException {
			LOGGER.debug(() -> toString() + " beginTransaction()");
			try {
				this.delegate.beginTransaction();
			}
			catch (RuntimeException e) {
				LOGGER.error(e, () -> "beginTransaction failed: " + this);
				this.txFailed = e;
				throw e;
			}
		}

		@Override
		public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets, String consumerGroupId)
				throws ProducerFencedException {

			LOGGER.trace(() -> toString() + " sendOffsetsToTransaction(" + offsets + ", " + consumerGroupId + ")");
			this.delegate.sendOffsetsToTransaction(offsets, consumerGroupId);
		}

		@Override
		public void commitTransaction() throws ProducerFencedException {
			LOGGER.debug(() -> toString() + " commitTransaction()");
			try {
				this.delegate.commitTransaction();
			}
			catch (RuntimeException e) {
				LOGGER.error(e, () -> "commitTransaction failed: " + this);
				this.txFailed = e;
				throw e;
			}
		}

		@Override
		public void abortTransaction() throws ProducerFencedException {
			LOGGER.debug(() -> toString() + " abortTransaction()");
			if (this.txFailed != null) {
				LOGGER.debug(() -> "abortTransaction ignored - previous txFailed: " + this.txFailed.getMessage()
					+ ": " + this);
			}
			else {
				try {
					this.delegate.abortTransaction();
				}
				catch (RuntimeException e) {
					LOGGER.error(e, () -> "Abort failed: " + this);
					this.txFailed = e;
					throw e;
				}
			}
		}

		@Override
		public void close() {
			close(null);
		}

		@Override
		public void close(@Nullable Duration timeout) {
			LOGGER.trace(() -> toString() + " close(" + (timeout == null ? "null" : timeout) + ")");
			if (this.cache != null) {
				Duration closeTimeout = this.txFailed instanceof TimeoutException
						? CLOSE_TIMEOUT_AFTER_TX_TIMEOUT
						: timeout;
				if (this.txFailed != null) {
					LOGGER.warn(() -> "Error during transactional operation; producer removed from cache; "
							+ "possible cause: "
							+ "broker restarted during transaction: " + this);
					this.delegate.close(closeTimeout);
					if (this.removeConsumerProducer != null) {
						this.removeConsumerProducer.accept(this);
					}
				}
				else {
					if (this.removeConsumerProducer == null) { // dedicated consumer producers are not cached
						synchronized (this) {
							if (!this.cache.contains(this)
									&& !this.cache.offer(this)) {
								this.delegate.close(closeTimeout);
							}
						}
					}
				}
			}
		}

		@Override
		public String toString() {
			return "CloseSafeProducer [delegate=" + this.delegate + ""
					+ (this.txId != null ? ", txId=" + this.txId : "")
					+ "]";
		}

	}

}
