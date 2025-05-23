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
package com.lmax.disruptor;

/**
 * Callback handler for uncaught exceptions in the event processing cycle of the {@link BatchEventProcessor}
 *
 * <p>在{@link BatchEventProcessor}的事件处理周期中捕获未捕获的异常的回调处理程序</p>
 *
 * @param <T> implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public interface ExceptionHandler<T>
{
    /**
     * <p>Strategy for handling uncaught exceptions when processing an event.</p>
     *
     * <p>处理事件时处理未捕获异常的策略。</p>
     *
     * <p>If the strategy wishes to terminate further processing by the {@link BatchEventProcessor}
     * then it should throw a {@link RuntimeException}.</p>
     *
     * <p>如果策略希望通过{@link BatchEventProcessor}终止进一步处理，则应抛出{@link RuntimeException}。</p>
     *
     * @param ex       the exception that propagated from the {@link EventHandler}.
     * @param sequence of the event which cause the exception.
     * @param event    being processed when the exception occurred.  This can be null.
     */
    void handleEventException(Throwable ex, long sequence, T event);

    /**
     * Callback to notify of an exception during {@link EventHandler#onStart()}
     *
     * <p>回调通知在{@link EventHandler#onStart()}期间发生异常</p>
     *
     * @param ex throw during the starting process.
     */
    void handleOnStartException(Throwable ex);

    /**
     * Callback to notify of an exception during {@link EventHandler#onShutdown()}
     *
     * <p>回调通知在{@link EventHandler#onShutdown()}期间发生异常</p>
     *
     * @param ex throw during the shutdown process.
     */
    void handleOnShutdownException(Throwable ex);
}
