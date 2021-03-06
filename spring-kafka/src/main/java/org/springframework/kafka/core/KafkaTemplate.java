/*
 * Copyright 2015-2020 the original author or authors.
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
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.TransactionSupport;
import org.springframework.kafka.support.converter.MessageConverter;
import org.springframework.kafka.support.converter.MessagingMessageConverter;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.micrometer.MicrometerHolder;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * 用于执行底层操作的模板类
 * @param <K> the key type.
 * @param <V> the value type.
 * @author Marius Bogoevici
 * @author Gary Russell
 * @author Igor Stepanov
 * @author Artem Bilan
 * @author Biju Kunjummen
 * @author Endika Guti?rrez
 */
public class KafkaTemplate<K, V> implements KafkaOperations<K, V>, ApplicationContextAware, BeanNameAware,
		DisposableBean {

	protected final LogAccessor logger = new LogAccessor(LogFactory.getLog(this.getClass())); //NOSONAR

	private final ProducerFactory<K, V> producerFactory;
	/**
	 * 是否开启自动刷新
	 */
	private final boolean autoFlush;
	/**
	 * 是否开启事务
	 */
	private final boolean transactional;

	private final ThreadLocal<Producer<K, V>> producers = new ThreadLocal<>();

	private final Map<String, String> micrometerTags = new HashMap<>();
	/**
	 * bean名称
	 */
	private String beanName = "kafkaTemplate";

	private ApplicationContext applicationContext;
	/**
	 * record消息转换器
	 */
	private RecordMessageConverter messageConverter = new MessagingMessageConverter();
	/**
	 * 默认发送的topic，默认false
	 */
	private String defaultTopic;

	private ProducerListener<K, V> producerListener = new LoggingProducerListener<K, V>();

	private String transactionIdPrefix;

	private Duration closeTimeout = ProducerFactoryUtils.DEFAULT_CLOSE_TIMEOUT;
	/**
	 * 允许开启非事务型的producer
	 */
	private boolean allowNonTransactional;
	/**
	 * 是否开启Listener的计时器
	 */
	private volatile boolean micrometerEnabled = true;

	private volatile MicrometerHolder micrometerHolder;

	/**
	 * Create an instance using the supplied producer factory and autoFlush false.
	 * @param producerFactory the producer factory.
	 */
	public KafkaTemplate(ProducerFactory<K, V> producerFactory) {
		this(producerFactory, false);
	}

	/**
	 * Create an instance using the supplied producer factory and autoFlush setting.
	 * <p>
	 * Set autoFlush to {@code true} if you have configured the producer's
	 * {@code linger.ms} to a non-default value and wish send operations on this template
	 * to occur immediately, regardless of that setting, or if you wish to block until the
	 * broker has acknowledged receipt according to the producer's {@code acks} property.
	 * @param producerFactory the producer factory.
	 * @param autoFlush true to flush after each send.
	 * @see Producer#flush()
	 */
	public KafkaTemplate(ProducerFactory<K, V> producerFactory, boolean autoFlush) {
		this.producerFactory = producerFactory;
		this.autoFlush = autoFlush;
		this.transactional = producerFactory.transactionCapable();
		this.micrometerEnabled = KafkaUtils.MICROMETER_PRESENT;
	}

	@Override
	public void setBeanName(String name) {
		this.beanName = name;
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	/**
	 * The default topic for send methods where a topic is not
	 * provided.
	 * @return the topic.
	 */
	public String getDefaultTopic() {
		return this.defaultTopic;
	}

	/**
	 * Set the default topic for send methods where a topic is not
	 * provided.
	 * @param defaultTopic the topic.
	 */
	public void setDefaultTopic(String defaultTopic) {
		this.defaultTopic = defaultTopic;
	}

	/**
	 * Set a {@link ProducerListener} which will be invoked when Kafka acknowledges
	 * a send operation. By default a {@link LoggingProducerListener} is configured
	 * which logs errors only.
	 * @param producerListener the listener; may be {@code null}.
	 */
	public void setProducerListener(@Nullable ProducerListener<K, V> producerListener) {
		this.producerListener = producerListener;
	}

	/**
	 * Return the message converter.
	 * @return the message converter.
	 */
	public MessageConverter getMessageConverter() {
		return this.messageConverter;
	}

	/**
	 * Set the message converter to use.
	 * @param messageConverter the message converter.
	 */
	public void setMessageConverter(RecordMessageConverter messageConverter) {
		Assert.notNull(messageConverter, "'messageConverter' cannot be null");
		this.messageConverter = messageConverter;
	}

	@Override
	public boolean isTransactional() {
		return this.transactional;
	}

	public String getTransactionIdPrefix() {
		return this.transactionIdPrefix;
	}

	/**
	 * Set a transaction id prefix to override the prefix in the producer factory.
	 * @param transactionIdPrefix the prefix.
	 * @since 2.3
	 */
	public void setTransactionIdPrefix(String transactionIdPrefix) {
		this.transactionIdPrefix = transactionIdPrefix;
	}

	/**
	 * Set the maximum time to wait when closing a producer; default 5 seconds.
	 * @param closeTimeout the close timeout.
	 * @since 2.1.14
	 */
	public void setCloseTimeout(Duration closeTimeout) {
		Assert.notNull(closeTimeout, "'closeTimeout' cannot be null");
		this.closeTimeout = closeTimeout;
	}

	/**
	 * Set to true to allow a non-transactional send when the template is transactional.
	 * @param allowNonTransactional true to allow.
	 * @since 2.4.3
	 */
	public void setAllowNonTransactional(boolean allowNonTransactional) {
		this.allowNonTransactional = allowNonTransactional;
	}

	@Override
	public boolean isAllowNonTransactional() {
		return this.allowNonTransactional;
	}

	/**
	 * Set to false to disable micrometer timers, if micrometer is on the class path.
	 * @param micrometerEnabled false to disable.
	 * @since 2.5
	 */
	public void setMicrometerEnabled(boolean micrometerEnabled) {
		this.micrometerEnabled = micrometerEnabled;
	}

	/**
	 * Set additional tags for the Micrometer listener timers.
	 * @param tags the tags.
	 * @since 2.5
	 */
	public void setMicrometerTags(Map<String, String> tags) {
		if (tags != null) {
			this.micrometerTags.putAll(tags);
		}
	}

	/**
	 * Return the producer factory used by this template.
	 * @return the factory.
	 * @since 2.2.5
	 */
	public ProducerFactory<K, V> getProducerFactory() {
		return this.producerFactory;
	}

	@Override
	public ListenableFuture<SendResult<K, V>> sendDefault(@Nullable V data) {
		return send(this.defaultTopic, data);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> sendDefault(K key, @Nullable V data) {
		return send(this.defaultTopic, key, data);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> sendDefault(Integer partition, K key, @Nullable V data) {
		return send(this.defaultTopic, partition, key, data);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> sendDefault(Integer partition, Long timestamp, K key, @Nullable V data) {
		return send(this.defaultTopic, partition, timestamp, key, data);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> send(String topic, @Nullable V data) {
		ProducerRecord<K, V> producerRecord = new ProducerRecord<>(topic, data);
		return doSend(producerRecord);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> send(String topic, K key, @Nullable V data) {
		ProducerRecord<K, V> producerRecord = new ProducerRecord<>(topic, key, data);
		return doSend(producerRecord);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> send(String topic, Integer partition, K key, @Nullable V data) {
		ProducerRecord<K, V> producerRecord = new ProducerRecord<>(topic, partition, key, data);
		return doSend(producerRecord);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> send(String topic, Integer partition, Long timestamp, K key,
			@Nullable V data) {

		ProducerRecord<K, V> producerRecord = new ProducerRecord<>(topic, partition, timestamp, key, data);
		return doSend(producerRecord);
	}

	@Override
	public ListenableFuture<SendResult<K, V>> send(ProducerRecord<K, V> record) {
		return doSend(record);
	}

	@SuppressWarnings("unchecked")
	@Override
	public ListenableFuture<SendResult<K, V>> send(Message<?> message) {
		ProducerRecord<?, ?> producerRecord = this.messageConverter.fromMessage(message, this.defaultTopic);
		if (!producerRecord.headers().iterator().hasNext()) { // possibly no Jackson
			byte[] correlationId = message.getHeaders().get(KafkaHeaders.CORRELATION_ID, byte[].class);
			if (correlationId != null) {
				producerRecord.headers().add(KafkaHeaders.CORRELATION_ID, correlationId);
			}
		}
		return doSend((ProducerRecord<K, V>) producerRecord);
	}


	@Override
	public List<PartitionInfo> partitionsFor(String topic) {
		Producer<K, V> producer = getTheProducer();
		try {
			return producer.partitionsFor(topic);
		}
		finally {
			closeProducer(producer, inTransaction());
		}
	}

	@Override
	public Map<MetricName, ? extends Metric> metrics() {
		Producer<K, V> producer = getTheProducer();
		try {
			return producer.metrics();
		}
		finally {
			closeProducer(producer, inTransaction());
		}
	}

	@Override
	public <T> T execute(ProducerCallback<K, V, T> callback) {
		Assert.notNull(callback, "'callback' cannot be null");
		Producer<K, V> producer = getTheProducer();
		try {
			return callback.doInKafka(producer);
		}
		finally {
			closeProducer(producer, inTransaction());
		}
	}

	@Override
	public <T> T executeInTransaction(OperationsCallback<K, V, T> callback) {
		Assert.notNull(callback, "'callback' cannot be null");
		Assert.state(this.transactional, "Producer factory does not support transactions");
		Producer<K, V> producer = this.producers.get();
		Assert.state(producer == null, "Nested calls to 'executeInTransaction' are not allowed");
		String transactionIdSuffix;
		if (this.producerFactory.isProducerPerConsumerPartition()) {
			transactionIdSuffix = TransactionSupport.getTransactionIdSuffix();
			TransactionSupport.clearTransactionIdSuffix();
		}
		else {
			transactionIdSuffix = null;
		}

		producer = this.producerFactory.createProducer(this.transactionIdPrefix);

		try {
			producer.beginTransaction();
		}
		catch (Exception e) {
			closeProducer(producer, false);
			throw e;
		}

		this.producers.set(producer);
		try {
			T result = callback.doInOperations(this);
			try {
				producer.commitTransaction();
			}
			catch (Exception e) {
				throw new SkipAbortException(e);
			}
			return result;
		}
		catch (SkipAbortException e) { // NOSONAR - exception flow control
			throw ((RuntimeException) e.getCause()); // NOSONAR - lost stack trace
		}
		catch (Exception e) {
			producer.abortTransaction();
			throw e;
		}
		finally {
			if (transactionIdSuffix != null) {
				TransactionSupport.setTransactionIdSuffix(transactionIdSuffix);
			}
			this.producers.remove();
			closeProducer(producer, false);
		}
	}

	/**
	 * {@inheritDoc}
	 * <p><b>Note</b> It only makes sense to invoke this method if the
	 * {@link ProducerFactory} serves up a singleton producer (such as the
	 * {@link DefaultKafkaProducerFactory}).
	 */
	@Override
	public void flush() {
		// 获取producer实例
		Producer<K, V> producer = getTheProducer();
		try {
			// 尝试清空record所在的缓冲区
			producer.flush();
		} finally {
			// 关闭producer，无论是否处于事务状态
			closeProducer(producer, inTransaction());
		}
	}


	@Override
	public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets) {
		sendOffsetsToTransaction(offsets, KafkaUtils.getConsumerGroupId());
	}

	@Override
	public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets, String consumerGroupId) {
		Producer<K, V> producer = this.producers.get();
		if (producer == null) {
			@SuppressWarnings("unchecked")
			KafkaResourceHolder<K, V> resourceHolder = (KafkaResourceHolder<K, V>) TransactionSynchronizationManager
					.getResource(this.producerFactory);
			Assert.isTrue(resourceHolder != null, "No transaction in process");
			producer = resourceHolder.getProducer();
		}
		producer.sendOffsetsToTransaction(offsets, consumerGroupId);
	}

	protected void closeProducer(Producer<K, V> producer, boolean inTx) {
		if (!inTx) {
			producer.close(this.closeTimeout);
		}
	}

	/**
	 * 发送组装好的ProducerRecord
	 * @param producerRecord 带有应用需要送的消息及信息的record
	 * @return 存储 {@link org.apache.kafka.clients.producer.RecordMetadata RecordMetadata}的ListenableFuture对象
	 */
	protected ListenableFuture<SendResult<K, V>> doSend(final ProducerRecord<K, V> producerRecord) {
		// 获取对应类型的producer实例
		final Producer<K, V> producer = getTheProducer();
		this.logger.trace(() -> "Sending: " + producerRecord);
		final SettableListenableFuture<SendResult<K, V>> future = new SettableListenableFuture<>();
		Object sample = null;
		// 开启listener的计时器
		if (this.micrometerEnabled && this.micrometerHolder == null) {
			this.micrometerHolder = obtainMicrometerHolder();
		}
		if (this.micrometerHolder != null) {
			sample = this.micrometerHolder.start();
		}
		// 使用producer发送消息，并构造回调方法
		producer.send(producerRecord, buildCallback(producerRecord, producer, future, sample));
		// 如果开启了autoflush，则执行flush操作
		if (this.autoFlush) {
			flush();
		}
		this.logger.trace(() -> "Sent: " + producerRecord);
		return future;
	}

	private Callback buildCallback(final ProducerRecord<K, V> producerRecord, final Producer<K, V> producer,
			final SettableListenableFuture<SendResult<K, V>> future, Object sample) {
		// 对record发送响应进行校验
		return (metadata, exception) -> {
			try {
				if (exception == null) {
					// exception为null，证明record发送成功
					// 开启计时器的情况下，记录请求成功时间
					if (sample != null) {
						this.micrometerHolder.success(sample);
					}
					// 向Spring包装的future对象中添加发送成功响应对象
					future.set(new SendResult<>(producerRecord, metadata));
					// 如果实现了producerListener，还会触发listener的监听
					if (KafkaTemplate.this.producerListener != null) {
						KafkaTemplate.this.producerListener.onSuccess(producerRecord, metadata);
					}
					KafkaTemplate.this.logger.trace(() -> "Sent ok: " + producerRecord + ", metadata: " + metadata);
				} else {
					// 此时在发送record过程中出现了异常
					if (sample != null) {
						this.micrometerHolder.failure(sample, exception.getClass().getSimpleName());
					}
					// 则在Spring包装的future对象中设置异常信息
					future.setException(new KafkaProducerException(producerRecord, "Failed to send", exception));
					// 触发listener的失败操作
					if (KafkaTemplate.this.producerListener != null) {
						KafkaTemplate.this.producerListener.onError(producerRecord, exception);
					}
					KafkaTemplate.this.logger.debug(exception, () -> "Failed to send: " + producerRecord);
				}
			}
			finally {
				// 如果未开启事务，则需要关闭producer实例
				if (!KafkaTemplate.this.transactional) {
					closeProducer(producer, false);
				}
			}
		};
	}


	/**
	 * 当前调用线程已经运行与事务中
	 * @return 运行于事务中，返回true
	 * @since 2.2.1
	 */
	@Override
	public boolean inTransaction() {
		// 当前已经配置开启事务
		// 并且可以可以从调用线程的ThreadLocal中获取producer实例，或者已有实例化的producer工厂，或者事务管理器已经确认事务处于活跃状态
		return this.transactional && (this.producers.get() != null
				|| TransactionSynchronizationManager.getResource(this.producerFactory) != null
				|| TransactionSynchronizationManager.isActualTransactionActive());
	}

	/**
	 * @return 对应类型的producer实例
	 */
	private Producer<K, V> getTheProducer() {
		// 是否已经配置开启producer的事务性
		boolean transactionalProducer = this.transactional;
		if (transactionalProducer) {
			// 如果已经配置开启prodcuer的事务性，则判断当前场景下，是否已经进入事务
			boolean inTransaction = inTransaction();
			// 校验事务的状态，如果不允许非事务性的生产过长，并且当前调用线程没有处于事务中
			// 进行提醒，建议使用KafkaTemplate#executeInTransaction()或@Transactinal注解
			Assert.state(this.allowNonTransactional || inTransaction,
					"No transaction is in process; "
							+ "possible solutions: run the template operation within the scope of a "
							+ "template.executeInTransaction() operation, start a transaction with @Transactional "
							+ "before invoking the template method, "
							+ "run in a transaction started by a listener container when consuming a record");
			// 如果允许非事务的生产过程，并且当前调用线程也不处于事务中，则可以不使用事务producer
			if (!inTransaction) {
				transactionalProducer = false;
			}
		}
		// 在当前调用线程处于事务中时，需要使用事务型producer
		if (transactionalProducer) {
			// 如果已经存在producer实例，直接从KafkaTemplate的ThreadLocal中获取producer实例即可
			Producer<K, V> producer = this.producers.get();
			if (producer != null) {
				return producer;
			}
			// 如果事务是刚刚开启，还没有创建producer实例，则使用producerFactory工厂，配置的transactinId和事务默认等待时间创建新的producer实例
			KafkaResourceHolder<K, V> holder = ProducerFactoryUtils
					.getTransactionalResourceHolder(this.producerFactory, this.transactionIdPrefix, this.closeTimeout);
			return holder.getProducer();
		}
		// 接下来的两种情况，均由DefaultKafkaProducerFactory提供实现
		// 如果允许非事务型的producer，则创建非实物型producer实例
		else if (this.allowNonTransactional) {
			return this.producerFactory.createNonTransactionalProducer();
		} else {
			// 根据实际情况创建producer实例
			return this.producerFactory.createProducer();
		}
	}

	@Nullable
	private MicrometerHolder obtainMicrometerHolder() {
		MicrometerHolder holder = null;
		try {
			if (KafkaUtils.MICROMETER_PRESENT) {
				holder = new MicrometerHolder(this.applicationContext, this.beanName,
						"spring.kafka.template", "KafkaTemplate Timer",
						this.micrometerTags);
			}
		}
		catch (@SuppressWarnings("unused") IllegalStateException ex) {
			this.micrometerEnabled = false;
		}
		return holder;
	}

	@Override
	public void destroy() {
		if (this.micrometerHolder != null) {
			this.micrometerHolder.destroy();
		}
	}

	@SuppressWarnings("serial")
	private static final class SkipAbortException extends RuntimeException {

		SkipAbortException(Throwable cause) {
			super(cause);
		}

	}

}
