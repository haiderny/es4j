/**
 * Copyright (c) 2016, All Contributors (see CONTRIBUTORS file)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.eventsourcing.repository;

import com.eventsourcing.*;
import com.eventsourcing.events.CommandTerminatedExceptionally;
import com.eventsourcing.events.EventCausalityEstablished;
import com.eventsourcing.events.JavaExceptionOccurred;
import com.eventsourcing.hlc.HybridTimestamp;
import com.eventsourcing.hlc.NTPServerTimeProvider;
import com.eventsourcing.hlc.PhysicalTimeProvider;
import com.eventsourcing.index.IndexEngine;
import com.eventsourcing.migrations.events.EntityLayoutIntroduced;
import com.eventsourcing.repository.commands.IntroduceEntityLayouts;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.ServiceManager;
import com.googlecode.cqengine.index.Index;
import lombok.Builder;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;


@Component(configurationPolicy = ConfigurationPolicy.REQUIRE,
           property = {"Journal.target=", "IndexEngine.target=", "LockProvider.target=", "jmx.objectname=com.eventsourcing:type=repository"})
@Slf4j
public class StandardRepository extends AbstractService implements Repository, RepositoryMBean {

    @SneakyThrows
    public StandardRepository() {
        setPhysicalTimeProvider(new NTPServerTimeProvider(new String[]{"localhost"}));
        setLockProvider(new LocalLockProvider());
    }

    @Builder
    @SneakyThrows
    public StandardRepository(Journal journal, PhysicalTimeProvider physicalTimeProvider,
                              IndexEngine indexEngine, LockProvider lockProvider) {
        setPhysicalTimeProvider(physicalTimeProvider == null ?
                                        new NTPServerTimeProvider(new String[]{"localhost"}) : physicalTimeProvider);
        setLockProvider(lockProvider == null ? new LocalLockProvider() : lockProvider);
        setJournal(journal);
        setIndexEngine(indexEngine);
    }

    @Getter
    private Journal journal;

    @Getter
    private Set<Class<? extends Command>> commands = new HashSet<>();
    @Getter
    private Set<Class<? extends Event>> events = new HashSet<>();

    @Getter
    private PhysicalTimeProvider physicalTimeProvider;
    @Getter
    private IndexEngine indexEngine;
    @Getter
    private LockProvider lockProvider;

    private ServiceManager services;
    private CommandConsumer commandConsumer;

    private List<EntitySubscriber> entitySubscribers = new ArrayList<>();

    @Activate
    protected void activate(ComponentContext ctx) {
        if (!isRunning()) {
            startAsync().awaitRunning();
        }
    }

    private List<Runnable> initialization = new ArrayList<>();

    @Override @SuppressWarnings("unchecked")
    protected void doStart() {
        if (journal == null) {
            notifyFailed(new IllegalStateException("journal == null"));
        }
        if (physicalTimeProvider == null) {
            notifyFailed(new IllegalStateException("physicalTimeProvider == null"));
        }
        if (indexEngine == null) {
            notifyFailed(new IllegalStateException("indexEngine == null"));
        }
        if (lockProvider == null) {
            notifyFailed(new IllegalStateException("lockProvider == null"));
        }

        addEventSetProvider(() -> {
            List<Class<? extends Event>> classes = Arrays
                    .asList(CommandTerminatedExceptionally.class, EventCausalityEstablished.class,
                            EntityLayoutIntroduced.class, JavaExceptionOccurred.class);
            return new HashSet<>(classes);
        });

        addCommandSetProvider(() -> {
            List<Class<? extends Command>> classes = Arrays
                    .asList(IntroduceEntityLayouts.class);
            return new HashSet<>(classes);
        });

        journal.setRepository(this);
        indexEngine.setJournal(journal);
        indexEngine.setRepository(this);

        services = new ServiceManager(Arrays.asList(journal, indexEngine, lockProvider, physicalTimeProvider).stream().
                filter(s -> !s.isRunning()).collect(Collectors.toSet()));
        services.startAsync().awaitHealthy();

        initialization.forEach(Runnable::run);
        initialization.clear();

        commandConsumer = new CommandConsumerImpl(commands, physicalTimeProvider, this, journal, indexEngine,
                                                  lockProvider);
        commandConsumer.startAsync().awaitRunning();

        journal.onCommandsAdded(commands);
        journal.onEventsAdded(events);

        publish(new IntroduceEntityLayouts(Iterables.concat(commands, events))).join();

        notifyStarted();
    }

    private Set<String> indicesConfiguredFor = new HashSet<>();

    @SneakyThrows
    private boolean configureIndices(Class<? extends Entity> klass) {
        try {
            if (!indicesConfiguredFor.contains(klass.getName())) {
                Iterable<Index> indices = indexEngine.getIndices(klass);
                for (Index i : indices) {
                    try {
                        indexEngine.getIndexedCollection(klass).addIndex(i);
                    } catch (IllegalStateException e) {
                        if (e.getMessage().contains("has already been added")) {
                            // ignore the re-addition of the index
                        } else {
                            throw e;
                        }
                    }
                }
                indicesConfiguredFor.add(klass.getName());
            }
        } catch (IndexEngine.IndexNotSupported | IllegalAccessException e) {
            notifyFailed(e);
            return true;
        }
        return false;
    }

    @Override
    protected void doStop() {
        commandConsumer.stopAsync().awaitTerminated();
        services.stopAsync().awaitStopped();
        // Try stopping services that were started beforehand and didn't
        // make it into `services`
        Arrays.asList(journal, indexEngine, lockProvider, physicalTimeProvider).stream().
                filter(Service::isRunning).forEach(s -> s.stopAsync().awaitTerminated());
        notifyStopped();
    }

    @Reference
    @Override
    public void setJournal(Journal journal) throws IllegalStateException {
        if (isRunning()) {
            throw new IllegalStateException();
        }
        this.journal = journal;
    }

    @Reference
    @Override
    public void setIndexEngine(IndexEngine indexEngine) throws IllegalStateException {
        if (isRunning()) {
            throw new IllegalStateException();
        }
        this.indexEngine = indexEngine;
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    @Override
    public void addCommandSetProvider(CommandSetProvider provider) {
        final Set<Class<? extends Command>> newCommands = provider.getCommands();
        Runnable runnable = () -> {
            for (Class<? extends Entity> klass : newCommands) {
                if (configureIndices(klass)) return;
            }
        };
        this.commands.addAll(newCommands);
        if (isRunning()) {
            // apply immediately
            runnable.run();
            journal.onCommandsAdded(newCommands);
            publish(new IntroduceEntityLayouts(Iterables.concat(newCommands))).join();
        } else {
            initialization.add(runnable);
        }
    }

    @Override
    public void removeCommandSetProvider(CommandSetProvider provider) {
        final Set<Class<? extends Command>> providedCommands = provider.getCommands();
        commands.removeAll(providedCommands);
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    @Override
    public void addEntitySubscriber(EntitySubscriber subscriber) {
        entitySubscribers.add(subscriber);
    }

    @Override
    public void removeEntitySubscriber(EntitySubscriber subscriber) {
        entitySubscribers.remove(subscriber);
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    @Override
    public void addEventSetProvider(EventSetProvider provider) {
        final Set<Class<? extends Event>> newEvents = provider.getEvents();
        Runnable runnable = () -> {
            for (Class<? extends Entity> klass : newEvents) {
                if (configureIndices(klass)) return;
            }
        };
        this.events.addAll(newEvents);
        if (isRunning()) {
            // apply immediately
            runnable.run();
            journal.onEventsAdded(newEvents);;
            publish(new IntroduceEntityLayouts(Iterables.concat(newEvents))).join();
        } else {
            initialization.add(runnable);
        }
    }

    @Override
    public void removeEventSetProvider(EventSetProvider provider) {
        final Set<Class<? extends Event>> providedEvents = provider.getEvents();
        events.removeAll(providedEvents);
    }

    @Reference
    @Override
    public void setPhysicalTimeProvider(PhysicalTimeProvider timeProvider) throws IllegalStateException {
        if (isRunning()) {
            throw new IllegalStateException();
        }
        this.physicalTimeProvider = timeProvider;
    }

    @Reference
    @Override
    public void setLockProvider(LockProvider lockProvider) throws IllegalStateException {
        if (isRunning()) {
            throw new IllegalStateException();
        }
        this.lockProvider = lockProvider;
    }

    @Override
    public <T extends Command<?, C>, C> CompletableFuture<C> publish(T command) {
        return this.commandConsumer.publish(command, entitySubscribers);
    }

    @Override public HybridTimestamp getTimestamp() {
        return commandConsumer.getTimestamp();
    }


    @Override
    public String[] getInstalledCommands() {
        return commands.stream().map(Class::getName).toArray(String[]::new);
    }

    @Override
    public String[] getInstalledEvents() {
        return events.stream().map(Class::getName).toArray(String[]::new);
    }
}
