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
 * Implementations translate another data representations into events claimed from the {@link RingBuffer}
 *
 * <p>实现将另一个数据表示转换为从{@link RingBuffer}中声明的事件</p>
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 * @param <A> type first user specified argument to the translator.
 * @see EventTranslator
 */
public interface EventTranslatorOneArg<T, A>
{
    /**
     * Translate a data representation into fields set in given event
     *
     * @param event    into which the data should be translated.
     * @param sequence that is assigned to event.
     * @param arg0     The first user specified argument to the translator
     */
    void translateTo(T event, long sequence, A arg0);
}
