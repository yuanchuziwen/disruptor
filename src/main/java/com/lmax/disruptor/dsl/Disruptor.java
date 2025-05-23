/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor.dsl;

import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.BatchEventProcessorBuilder;
import com.lmax.disruptor.BatchRewindStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventHandlerIdentity;
import com.lmax.disruptor.EventProcessor;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.EventTranslatorThreeArg;
import com.lmax.disruptor.EventTranslatorTwoArg;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RewindableEventHandler;
import com.lmax.disruptor.RewindableException;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.util.Util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A DSL-style API for setting up the disruptor pattern around a ring buffer
 * (aka the Builder pattern).
 *
 * <p>一个用于在环形缓冲区周围设置 disruptor 模式的 DSL 风格 API（也称为构建器模式）。
 *
 * <p>A simple example of setting up the disruptor with two event handlers that
 * must process events in order:
 *
 * <pre>
 * <code>Disruptor&lt;MyEvent&gt; disruptor = new Disruptor&lt;MyEvent&gt;(MyEvent.FACTORY, 32, Executors.newCachedThreadPool());
 * EventHandler&lt;MyEvent&gt; handler1 = new EventHandler&lt;MyEvent&gt;() { ... };
 * EventHandler&lt;MyEvent&gt; handler2 = new EventHandler&lt;MyEvent&gt;() { ... };
 * disruptor.handleEventsWith(handler1);
 * disruptor.after(handler1).handleEventsWith(handler2);
 *
 * RingBuffer ringBuffer = disruptor.start();</code>
 * </pre>
 *
 * @param <T> the type of event used.
 */
public class Disruptor<T>
{

    // --- 成员变量 ↓↓↓↓↓ ---

    // 环形缓冲区
    private final RingBuffer<T> ringBuffer;
    // 线程工厂，用于创建 consumer 线程
    private final ThreadFactory threadFactory;
    // consumer 仓库，记录了监听 disruptor 的所有 consumer
    private final ConsumerRepository consumerRepository = new ConsumerRepository();
    // 布尔值，明确记录了 disruptor 的启动状态
    private final AtomicBoolean started = new AtomicBoolean(false);
    // 异常处理器
    private ExceptionHandler<? super T> exceptionHandler = new ExceptionHandlerWrapper<>();

    // --- 成员变量 ↑↑↑↑↑ ---

    // --- 构造函数 ↓↓↓↓↓ ---

    /**
     * Create a new Disruptor. Will default to {@link com.lmax.disruptor.BlockingWaitStrategy} and
     * {@link ProducerType}.MULTI
     *
     * <p>创建一个新的 disruptor 对象。
     * 会默认使用 BlockingWaitStrategy 和 ProducerType.MULTI 参数
     *
     * @param eventFactory   the factory to create events in the ring buffer.
     * @param ringBufferSize the size of the ring buffer.
     * @param threadFactory  a {@link ThreadFactory} to create threads to for processors.
     */
    public Disruptor(
            final EventFactory<T> eventFactory,
            final int ringBufferSize,
            final ThreadFactory threadFactory)
    {
        // 对比下面的方法，少了两个参数；即没有指定 producerType 和 waitStrategy
        // 此时 RingBuffer 会使用默认的 BlockingWaitStrategy 和 ProducerType.MULTI
        this(
                RingBuffer.createMultiProducer(eventFactory, ringBufferSize),
                threadFactory);
    }

    /**
     * Create a new Disruptor.
     *
     * <p>创建一个新的 disruptor 对象。
     * 它允许指定所有的参数
     *
     * @param eventFactory   the factory to create events in the ring buffer.
     * @param ringBufferSize the size of the ring buffer, must be power of 2.
     * @param threadFactory  a {@link ThreadFactory} to create threads for processors.
     * @param producerType   the claim strategy to use for the ring buffer.
     * @param waitStrategy   the wait strategy to use for the ring buffer.
     */
    public Disruptor(
            final EventFactory<T> eventFactory,
            final int ringBufferSize,
            final ThreadFactory threadFactory,
            final ProducerType producerType,
            final WaitStrategy waitStrategy)
    {
        this(
                RingBuffer.create(producerType, eventFactory, ringBufferSize, waitStrategy),
                threadFactory);
    }

    /**
     * Private constructor helper
     */
    private Disruptor(final RingBuffer<T> ringBuffer, final ThreadFactory threadFactory)
    {
        // 私有的构造方法，用于初始化 Disruptor 对象
        // 它的入参只有 ringBuffer 和 threadFactory，说明 ringBuffer 内部才会感知其他的那些参数
        this.ringBuffer = ringBuffer;
        this.threadFactory = threadFactory;
    }

    // --- 构造函数 ↑↑↑↑↑ ---

    // --- 编排 consumer ↓↓↓↓ ---

    /**
     * <p>Set up event handlers to handle events from the ring buffer. These handlers will process events
     * as soon as they become available, in parallel.</p>
     *
     * <p>设置 event handlers 以处理 ring buffer 中的 events。这些处理器将在 events 一旦可用就立即处理，以并行方式。</p>
     *
     * <p>This method can be used as the start of a chain. For example if the handler <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * <p>此方法可以用作链的开始。例如，如果处理程序 <code>A</code> 必须在处理程序 <code>B</code> 之前处理 events：</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * <p>This call is additive, but generally should only be called once when setting up the Disruptor instance</p>
     * <p>此调用是可累加的，但通常在设置 Disruptor 实例时只应调用一次</p>
     *
     * @param handlers the event handlers that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    @SuppressWarnings("varargs")
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventHandler<? super T>... handlers)
    {
        // 这里传入的是一个空数组，表示没有其他依赖的消费者；即消费者之间没有依赖
        return createEventProcessors(new Sequence[0], handlers);
    }

    /**
     * <p>Set up event handlers to handle events from the ring buffer. These handlers will process events
     * as soon as they become available, in parallel.</p>
     *
     * <p>设置 event handlers 以处理 ring buffer 中的 events。
     * 这些处理器将在 events 一旦可用就立即处理，以并行方式。</p>
     *
     * <p>This method can be used as the start of a chain. For example if the handler <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * <p>此方法可以用作链的开始。例如，如果处理程序 <code>A</code> 必须在处理程序 <code>B</code> 之前处理 events：</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * <p>This call is additive, but generally should only be called once when setting up the Disruptor instance</p>
     *
     * <p>此调用是可累加的，但通常在设置 Disruptor 实例时只应调用一次</p>
     *
     * @param batchRewindStrategy a {@link BatchRewindStrategy} for customizing how to handle a {@link RewindableException}.
     * @param handlers            the rewindable event handlers that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    @SuppressWarnings("varargs")
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final BatchRewindStrategy batchRewindStrategy,
                                                       final RewindableEventHandler<? super T>... handlers)
    {
        // 指定了 BatchRewindStrategy 的情况下，创建 event processors
        // BatchRewindStrategy 用于处理 RewindableException，代表是否、如何处理一个 consume 时抛出 RewindableException 的 event
        return createEventProcessors(new Sequence[0], batchRewindStrategy, handlers);
    }

    /**
     * <p>Set up custom event processors to handle events from the ring buffer. The Disruptor will
     * automatically start these processors when {@link #start()} is called.</p>
     *
     * <p>This method can be used as the start of a chain. For example if the handler <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * <p>Since this is the start of the chain, the processor factories will always be passed an empty <code>Sequence</code>
     * array, so the factory isn't necessary in this case. This method is provided for consistency with
     * {@link EventHandlerGroup#handleEventsWith(EventProcessorFactory...)} and {@link EventHandlerGroup#then(EventProcessorFactory...)}
     * which do have barrier sequences to provide.</p>
     *
     * <p>This call is additive, but generally should only be called once when setting up the Disruptor instance</p>
     *
     * @param eventProcessorFactories the event processor factories to use to create the event processors that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventProcessorFactory<T>... eventProcessorFactories)
    {
        // 传入了 EventProcessorFactory 的情况下，创建 event processors
        // EventProcessorFactory 用于创建 event processor，即消费者
        final Sequence[] barrierSequences = new Sequence[0];
        return createEventProcessors(barrierSequences, eventProcessorFactories);
    }

    /**
     * <p>Set up custom event processors to handle events from the ring buffer. The Disruptor will
     * automatically start this processors when {@link #start()} is called.</p>
     *
     * <p>设置 event processors 以处理 ring buffer 中的事件。
     * 当调用 {@link #start()} 时，Disruptor 将自动启动这些处理器。</p>
     *
     * <p>This method can be used as the start of a chain. For example if the processor <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * @param processors the event processors that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    public EventHandlerGroup<T> handleEventsWith(final EventProcessor... processors)
    {
        // 遍历 processors，将每个 processor 添加到 consumerRepository 中
        for (final EventProcessor processor : processors)
        {
            consumerRepository.add(processor);
        }

        // 获取 processors 的 sequences
        final Sequence[] sequences = Util.getSequencesFor(processors);
        // 将 processors 的 sequences 添加到 ringBuffer 的 gatingSequences 中
        ringBuffer.addGatingSequences(sequences);

        return new EventHandlerGroup<>(this, consumerRepository, sequences);
    }


    /**
     * <p>Specify an exception handler to be used for any future event handlers.</p>
     *
     * <p>Note that only event handlers set up after calling this method will use the exception handler.</p>
     *
     * @param exceptionHandler the exception handler to use for any future {@link EventProcessor}.
     * @deprecated This method only applies to future event handlers. Use setDefaultExceptionHandler instead which applies to existing and new event handlers.
     */
    @Deprecated
    public void handleExceptionsWith(final ExceptionHandler<? super T> exceptionHandler)
    {
        // 其他更新 exceptionHandler 的方法实现是通过 ExceptionHandlerWrapper 的 switchTo 方法，这里是直接更新了成员变量
        // 因此在其他方法中都会检测是否是 ExceptionHandlerWrapper 的实例，如果是则不允许再次更新
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * <p>Specify an exception handler to be used for event handlers and worker pools created by this Disruptor.</p>
     *
     * <p>指定一个异常处理器，用于由此 Disruptor 创建的事件处理器和工作池。</p>
     *
     * <p>The exception handler will be used by existing and future event handlers and worker pools created by this Disruptor instance.</p>
     *
     * <p>此异常处理器将用于由此 Disruptor 实例创建的现有和未来的事件处理器和工作池。</p>
     *
     * @param exceptionHandler the exception handler to use.
     */
    @SuppressWarnings("unchecked")
    public void setDefaultExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        checkNotStarted();
        // 如果 exceptionHandler 不是 ExceptionHandlerWrapper 的实例
        // 说明它已经被其他方法变更了（handleExceptionsWith），此时不允许再次变更
        if (!(this.exceptionHandler instanceof ExceptionHandlerWrapper))
        {
            throw new IllegalStateException("setDefaultExceptionHandler can not be used after handleExceptionsWith");
        }
        ((ExceptionHandlerWrapper<T>) this.exceptionHandler).switchTo(exceptionHandler);
    }

    /**
     * Override the default exception handler for a specific handler.
     * <pre>disruptorWizard.handleExceptionsIn(eventHandler).with(exceptionHandler);</pre>
     *
     * <p>为特定处理程序覆盖默认异常处理程序。</p>
     * <pre>disruptorWizard.handleExceptionsIn(eventHandler).with(exceptionHandler);</pre>
     *
     * @param eventHandler the event handler to set a different exception handler for.
     * @return an ExceptionHandlerSetting dsl object - intended to be used by chaining the with method call.
     */
    public ExceptionHandlerSetting<T> handleExceptionsFor(final EventHandlerIdentity eventHandler)
    {
        // 这个场景暂时还不清楚，内部是直接针对两个入参封装了一个 ExceptionHandlerSetting 对象就结束了
        return new ExceptionHandlerSetting<>(eventHandler, consumerRepository);
    }

    /**
     * <p>Create a group of event handlers to be used as a dependency.
     * For example if the handler <code>A</code> must process events before handler <code>B</code>:</p>
     *
     * <p>创建一组事件处理程序以用作依赖关系。
     * 例如，如果处理程序 <code>A</code> 必须在处理程序 <code>B</code> 之前处理 events：</p>
     *
     * <pre><code>dw.after(A).handleEventsWith(B);</code></pre>
     *
     * @param handlers the event handlers, previously set up with {@link #handleEventsWith(EventHandler[])},
     *                 that will form the barrier for subsequent handlers or processors.
     *                 eventHandlers，之前必须使用 {@link #handleEventsWith(EventHandler[])} 设置过的事件处理程序，
     *                 将形成后续处理程序或处理器的屏障。
     *
     * @return an {@link EventHandlerGroup} that can be used to setup a dependency barrier over the specified event handlers.
     */
    public final EventHandlerGroup<T> after(final EventHandlerIdentity... handlers)
    {
        // 注意这里的前提是入参 handlers 已经被正确的添加过了
        final Sequence[] sequences = new Sequence[handlers.length];
        for (int i = 0, handlersLength = handlers.length; i < handlersLength; i++)
        {
            sequences[i] = consumerRepository.getSequenceFor(handlers[i]);
        }

        return new EventHandlerGroup<>(this, consumerRepository, sequences);
    }

    /**
     * Create a group of event processors to be used as a dependency.
     *
     * <p>创建一组事件处理程序以用作依赖关系。</p>
     *
     * @param processors the event processors, previously set up with {@link #handleEventsWith(com.lmax.disruptor.EventProcessor...)},
     *                   that will form the barrier for subsequent handlers or processors.
     * @return an {@link EventHandlerGroup} that can be used to setup a {@link SequenceBarrier} over the specified event processors.
     * @see #after(EventHandlerIdentity[])
     */
    public EventHandlerGroup<T> after(final EventProcessor... processors)
    {
        return new EventHandlerGroup<>(this, consumerRepository, Util.getSequencesFor(processors));
    }

    // --- 编排 consumer ↑↑↑↑↑ ---

    // --- publisher 发消息 ↓↓↓↓↓ ---
    /**
     * Publish an event to the ring buffer.
     *
     * @param eventTranslator the translator that will load data into the event.
     */
    public void publishEvent(final EventTranslator<T> eventTranslator)
    {
        ringBuffer.publishEvent(eventTranslator);
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param <A>             Class of the user supplied argument.
     * @param eventTranslator the translator that will load data into the event.
     * @param arg             A single argument to load into the event
     */
    public <A> void publishEvent(final EventTranslatorOneArg<T, A> eventTranslator, final A arg)
    {
        ringBuffer.publishEvent(eventTranslator, arg);
    }

    /**
     * Publish a batch of events to the ring buffer.
     *
     * @param <A>             Class of the user supplied argument.
     * @param eventTranslator the translator that will load data into the event.
     * @param arg             An array single arguments to load into the events. One Per event.
     */
    public <A> void publishEvents(final EventTranslatorOneArg<T, A> eventTranslator, final A[] arg)
    {
        // 全部都交给 ringBuffer 来处理
        ringBuffer.publishEvents(eventTranslator, arg);
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param <A>             Class of the user supplied argument.
     * @param <B>             Class of the user supplied argument.
     * @param eventTranslator the translator that will load data into the event.
     * @param arg0            The first argument to load into the event
     * @param arg1            The second argument to load into the event
     */
    public <A, B> void publishEvent(final EventTranslatorTwoArg<T, A, B> eventTranslator, final A arg0, final B arg1)
    {
        ringBuffer.publishEvent(eventTranslator, arg0, arg1);
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param eventTranslator the translator that will load data into the event.
     * @param <A>             Class of the user supplied argument.
     * @param <B>             Class of the user supplied argument.
     * @param <C>             Class of the user supplied argument.
     * @param arg0            The first argument to load into the event
     * @param arg1            The second argument to load into the event
     * @param arg2            The third argument to load into the event
     */
    public <A, B, C> void publishEvent(final EventTranslatorThreeArg<T, A, B, C> eventTranslator, final A arg0, final B arg1, final C arg2)
    {
        ringBuffer.publishEvent(eventTranslator, arg0, arg1, arg2);
    }

    // --- publisher 发消息 ↑↑↑↑↑ ---

    /**
     * <p>Starts the event processors and returns the fully configured ring buffer.</p>
     *
     * <p>The ring buffer is set up to prevent overwriting any entry that is yet to
     * be processed by the slowest event processor.</p>
     *
     * <p>This method must only be called once after all event processors have been added.</p>
     *
     * @return the configured ring buffer.
     */
    public RingBuffer<T> start()
    {
        checkOnlyStartedOnce();
        consumerRepository.startAll(threadFactory);

        return ringBuffer;
    }

    /**
     * Calls {@link com.lmax.disruptor.EventProcessor#halt()} on all of the event processors created via this disruptor.
     */
    public void halt()
    {
        consumerRepository.haltAll();
    }

    /**
     * <p>Waits until all events currently in the disruptor have been processed by all event processors
     * and then halts the processors.  It is critical that publishing to the ring buffer has stopped
     * before calling this method, otherwise it may never return.</p>
     *
     * <p>This method will not shutdown the executor, nor will it await the final termination of the
     * processor threads.</p>
     */
    public void shutdown()
    {
        try
        {
            shutdown(-1, TimeUnit.MILLISECONDS);
        }
        catch (final TimeoutException e)
        {
            exceptionHandler.handleOnShutdownException(e);
        }
    }

    /**
     * <p>Waits until all events currently in the disruptor have been processed by all event processors
     * and then halts the processors.</p>
     *
     * <p>等待所有当前在 disruptor 中的事件被所有事件处理器处理完毕，然后停止处理器。</p>
     *
     * <p>This method will not shutdown the executor, nor will it await the final termination of the
     * processor threads.</p>
     *
     * <p>此方法不会关闭执行器，也不会等待处理器线程的最终终止。</p>
     *
     * @param timeout  the amount of time to wait for all events to be processed. <code>-1</code> will give an infinite timeout
     * @param timeUnit the unit the timeOut is specified in
     * @throws TimeoutException if a timeout occurs before shutdown completes.
     */
    public void shutdown(final long timeout, final TimeUnit timeUnit) throws TimeoutException
    {
        // 计算等待时间
        final long timeOutAt = System.nanoTime() + timeUnit.toNanos(timeout);
        // 循环等待消息均被消费
        while (hasBacklog())
        {
            // 如果超时，抛出异常
            if (timeout >= 0 && System.nanoTime() > timeOutAt)
            {
                throw TimeoutException.INSTANCE;
            }
            // Busy spin
        }
        // 标记 consumer 暂停
        halt();
    }

    /**
     * The {@link RingBuffer} used by this Disruptor.  This is useful for creating custom
     * event processors if the behaviour of {@link BatchEventProcessor} is not suitable.
     *
     * @return the ring buffer used by this Disruptor.
     */
    public RingBuffer<T> getRingBuffer()
    {
        return ringBuffer;
    }

    /**
     * Get the value of the cursor indicating the published sequence.
     *
     * @return value of the cursor for events that have been published.
     */
    public long getCursor()
    {
        return ringBuffer.getCursor();
    }

    /**
     * The capacity of the data structure to hold entries.
     *
     * @return the size of the RingBuffer.
     * @see com.lmax.disruptor.Sequencer#getBufferSize()
     */
    public long getBufferSize()
    {
        return ringBuffer.getBufferSize();
    }

    /**
     * Get the event for a given sequence in the RingBuffer.
     *
     * @param sequence for the event.
     * @return event for the sequence.
     * @see RingBuffer#get(long)
     */
    public T get(final long sequence)
    {
        return ringBuffer.get(sequence);
    }

    /**
     * Get the {@link SequenceBarrier} used by a specific handler. Note that the {@link SequenceBarrier}
     * may be shared by multiple event handlers.
     *
     * @param handler the handler to get the barrier for.
     * @return the SequenceBarrier used by <i>handler</i>.
     */
    public SequenceBarrier getBarrierFor(final EventHandlerIdentity handler)
    {
        return consumerRepository.getBarrierFor(handler);
    }

    /**
     * Gets the sequence value for the specified event handlers.
     *
     * @param handler eventHandler to get the sequence for.
     * @return eventHandler's sequence
     */
    public long getSequenceValueFor(final EventHandlerIdentity handler)
    {
        return consumerRepository.getSequenceFor(handler).get();
    }

    /**
     * Confirms if all messages have been consumed by all event processors
     *
     * <p>确认所有消息是否已被所有事件处理器消耗</p>
     */
    private boolean hasBacklog()
    {
        final long cursor = ringBuffer.getCursor();

        return consumerRepository.hasBacklog(cursor, false);
    }

    /**
     * Checks if disruptor has been started
     *
     * @return true when start has been called on this instance; otherwise false
     */
    public boolean hasStarted()
    {
        return started.get();
    }

    EventHandlerGroup<T> createEventProcessors(
            final Sequence[] barrierSequences, // barrierSequences 代表当前消费者依赖的前置消费者对应的消费进度，即当前消费者的消费进度永远小于等于 barrierSequences 中的最小进度
            final EventHandler<? super T>[] eventHandlers)
    {
        // 确认 disruptor 还没有启动
        checkNotStarted();

        // 为消费者构造一个 processorSequences 数组
        final Sequence[] processorSequences = new Sequence[eventHandlers.length];
        // 基于需要同步的 barrier 创建一个 SequenceBarrier（即为依赖的 sequence 们创建一个数组）
        final SequenceBarrier barrier = ringBuffer.newBarrier(barrierSequences);

        // 遍历每一个入参的消费者 handler
        for (int i = 0, eventHandlersLength = eventHandlers.length; i < eventHandlersLength; i++)
        {
            final EventHandler<? super T> eventHandler = eventHandlers[i];

            // 封账为 BatchEventProcessor，代表实际作为线程的消费方法
            final BatchEventProcessor<T> batchEventProcessor =
                    new BatchEventProcessorBuilder().build(ringBuffer, barrier, eventHandler);

            if (exceptionHandler != null)
            {
                batchEventProcessor.setExceptionHandler(exceptionHandler);
            }

            // 将消费者信息放入 consumerRepository 中统一管理
            consumerRepository.add(batchEventProcessor, eventHandler, barrier);
            // 将消费者对应的消费进度序列添加到数组中
            processorSequences[i] = batchEventProcessor.getSequence();
        }

        // 更新 ringBuffer 中记录的 gatingSequences
        updateGatingSequencesForNextInChain(barrierSequences, processorSequences);

        return new EventHandlerGroup<>(this, consumerRepository, processorSequences);
    }

    EventHandlerGroup<T> createEventProcessors(
            final Sequence[] barrierSequences,
            final BatchRewindStrategy batchRewindStrategy,
            final RewindableEventHandler<? super T>[] eventHandlers)
    {
        checkNotStarted();

        final Sequence[] processorSequences = new Sequence[eventHandlers.length];
        final SequenceBarrier barrier = ringBuffer.newBarrier(barrierSequences);

        for (int i = 0, eventHandlersLength = eventHandlers.length; i < eventHandlersLength; i++)
        {
            final RewindableEventHandler<? super T> eventHandler = eventHandlers[i];

            final BatchEventProcessor<T> batchEventProcessor =
                    new BatchEventProcessorBuilder().build(ringBuffer, barrier, eventHandler, batchRewindStrategy);

            if (exceptionHandler != null)
            {
                batchEventProcessor.setExceptionHandler(exceptionHandler);
            }

            consumerRepository.add(batchEventProcessor, eventHandler, barrier);
            processorSequences[i] = batchEventProcessor.getSequence();
        }

        updateGatingSequencesForNextInChain(barrierSequences, processorSequences);

        return new EventHandlerGroup<>(this, consumerRepository, processorSequences);
    }

    private void updateGatingSequencesForNextInChain(final Sequence[] barrierSequences, final Sequence[] processorSequences)
    {
        // 添加 processorSequences 到 ringBuffer 的 gatingSequences 中；即添加新增消费者对应的消费进度序列
        // 移除 barrierSequences 从 ringBuffer 的 gatingSequences 中；即移除依赖的 sequence
        if (processorSequences.length > 0)
        {
            ringBuffer.addGatingSequences(processorSequences);
            for (final Sequence barrierSequence : barrierSequences)
            {
                ringBuffer.removeGatingSequence(barrierSequence);
            }
            // 取消标记 barrierSequences 作为某一消费链路的最后一个消费者
            consumerRepository.unMarkEventProcessorsAsEndOfChain(barrierSequences);
        }
    }

    EventHandlerGroup<T> createEventProcessors(
            final Sequence[] barrierSequences, final EventProcessorFactory<T>[] processorFactories)
    {
        final EventProcessor[] eventProcessors = new EventProcessor[processorFactories.length];
        for (int i = 0; i < processorFactories.length; i++)
        {
            eventProcessors[i] = processorFactories[i].createEventProcessor(ringBuffer, barrierSequences);
        }

        return handleEventsWith(eventProcessors);
    }

    private void checkNotStarted()
    {
        if (started.get())
        {
            throw new IllegalStateException("All event handlers must be added before calling starts.");
        }
    }

    private void checkOnlyStartedOnce()
    {
        if (!started.compareAndSet(false, true))
        {
            throw new IllegalStateException("Disruptor.start() must only be called once.");
        }
    }

    @Override
    public String toString()
    {
        return "Disruptor{" +
                "ringBuffer=" + ringBuffer +
                ", started=" + started +
                ", threadFactory=" + threadFactory +
                '}';
    }
}
