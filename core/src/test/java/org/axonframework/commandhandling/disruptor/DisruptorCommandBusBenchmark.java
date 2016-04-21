/*
 * Copyright (c) 2010-2012. Axon Framework
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

package org.axonframework.commandhandling.disruptor;

import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.GenericCommandMessage;
import org.axonframework.commandhandling.annotation.TargetAggregateIdentifier;
import org.axonframework.commandhandling.model.AggregateLifecycle;
import org.axonframework.commandhandling.model.Repository;
import org.axonframework.common.Registration;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventsourcing.DomainEventMessage;
import org.axonframework.eventsourcing.GenericAggregateFactory;
import org.axonframework.eventsourcing.GenericDomainEventMessage;
import org.axonframework.eventsourcing.annotation.AggregateIdentifier;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventstore.EventStore;
import org.axonframework.messaging.MessageDispatchInterceptor;
import org.axonframework.messaging.MessageHandler;
import org.axonframework.messaging.unitofwork.UnitOfWork;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;

/**
 * @author Allard Buijze
 */
public class DisruptorCommandBusBenchmark {

    private static final int COMMAND_COUNT = 50 * 1000 * 1000;

    public static void main(String[] args) throws InterruptedException {
        CountingEventBus eventBus = new CountingEventBus();
        StubHandler stubHandler = new StubHandler();
        InMemoryEventStore inMemoryEventStore = new InMemoryEventStore();
        DisruptorCommandBus commandBus = new DisruptorCommandBus(inMemoryEventStore, eventBus);
        commandBus.subscribe(StubCommand.class.getName(), stubHandler);
        stubHandler.setRepository(commandBus.createRepository(new GenericAggregateFactory<>(StubAggregate.class)));
        final String aggregateIdentifier = "MyID";
        inMemoryEventStore.appendEvents(singletonList(new GenericDomainEventMessage<>(aggregateIdentifier, 0,
                                                                                      new StubDomainEvent(), type)));

        long start = System.currentTimeMillis();
        for (int i = 0; i < COMMAND_COUNT; i++) {
            CommandMessage<StubCommand> command = new GenericCommandMessage<>(
                    new StubCommand(aggregateIdentifier));
            commandBus.dispatch(command);
        }
        System.out.println("Finished dispatching!");

        inMemoryEventStore.countDownLatch.await(5, TimeUnit.SECONDS);
        long end = System.currentTimeMillis();
        try {
            assertEquals("Seems that some events are not published", 0, eventBus.publisherCountDown.getCount());
            assertEquals("Seems that some events are not stored", 0, inMemoryEventStore.countDownLatch.getCount());
            System.out.println("Did " + ((COMMAND_COUNT * 1000L) / (end - start)) + " commands per second");
        } finally {
            commandBus.stop();
        }
    }

    private static class StubAggregate {

        @AggregateIdentifier
        private String identifier;

        public String getIdentifier() {
            return identifier;
        }

        public void doSomething() {
            AggregateLifecycle.apply(new SomethingDoneEvent());
        }

        @EventSourcingHandler
        protected void handle(EventMessage event) {
            identifier = ((DomainEventMessage<?>) event).getAggregateIdentifier();
        }
    }

    private static class InMemoryEventStore implements EventStore {

        private final Map<String, DomainEventMessage> storedEvents = new HashMap<>();
        private final CountDownLatch countDownLatch = new CountDownLatch((int) (COMMAND_COUNT + 1L));

        @Override
        public void appendEvents(List<DomainEventMessage<?>> events) {
            if (events == null || events.isEmpty()) {
                return;
            }
            String key = events.get(0).getAggregateIdentifier();
            DomainEventMessage<?> lastEvent = null;
            for (EventMessage<?> event : events) {
                countDownLatch.countDown();
                lastEvent = (DomainEventMessage<?>) event;
            }
            storedEvents.put(key, lastEvent);
        }

        @Override
        public DomainEventStream readEvents(String identifier) {
            return new SimpleDomainEventStream(singletonList(storedEvents.get(identifier)));
        }

        @Override
        public DomainEventStream readEvents(String identifier, long firstSequenceNumber, long lastSequenceNumber) {
            throw new UnsupportedOperationException("Not implemented");
        }
    }

    private static class StubCommand {

        @TargetAggregateIdentifier
        private String aggregateIdentifier;

        public StubCommand(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }
    }

    private static class StubHandler implements MessageHandler<CommandMessage<?>> {

        private Repository<StubAggregate> repository;

        private StubHandler() {
        }

        @Override
        public Object handle(CommandMessage<?> message, UnitOfWork<? extends CommandMessage<?>> unitOfWork) throws Exception {
            StubCommand payload = (StubCommand) message.getPayload();
            repository.load(payload.getAggregateIdentifier()).execute(StubAggregate::doSomething);
            return null;
        }

        public void setRepository(Repository<StubAggregate> repository) {
            this.repository = repository;
        }
    }

    private static class StubDomainEvent {

    }

    private static class CountingEventBus implements EventBus {

        private final CountDownLatch publisherCountDown = new CountDownLatch(COMMAND_COUNT);

        @Override
        public void publish(List<EventMessage<?>> events) {
            publisherCountDown.countDown();
        }

        @Override
        public Registration subscribe(Consumer<List<? extends EventMessage<?>>> eventProcessor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Registration registerDispatchInterceptor(MessageDispatchInterceptor<EventMessage<?>> dispatchInterceptor) {
            throw new UnsupportedOperationException();
        }
    }
}
