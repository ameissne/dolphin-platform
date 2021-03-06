/*
 * Copyright 2015-2017 Canoo Engineering AG.
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
package com.canoo.dolphin.server.context;

import com.canoo.dolphin.BeanManager;
import com.canoo.platform.core.functional.Subscription;
import com.canoo.dolphin.impl.*;
import com.canoo.dolphin.impl.collections.ListMapperImpl;
import com.canoo.dolphin.impl.commands.*;
import com.canoo.dolphin.internal.ClassRepository;
import com.canoo.dolphin.internal.EventDispatcher;
import com.canoo.dolphin.internal.collections.ListMapper;
import com.canoo.dolphin.server.config.RemotingConfiguration;
import com.canoo.dolphin.server.controller.ControllerHandler;
import com.canoo.dolphin.server.controller.ControllerRepository;
import com.canoo.dolphin.server.impl.*;
import com.canoo.dolphin.server.impl.gc.GarbageCollectionCallback;
import com.canoo.dolphin.server.impl.gc.GarbageCollector;
import com.canoo.dolphin.server.impl.gc.Instance;
import com.canoo.dolphin.server.mbean.DolphinContextMBeanRegistry;
import com.canoo.impl.platform.core.Assert;
import com.canoo.platform.core.functional.Callback;
import com.canoo.impl.server.beans.ManagedBeanFactory;
import com.canoo.impl.server.client.ClientSessionProvider;
import com.canoo.platform.server.client.ClientSession;
import org.opendolphin.core.comm.Command;
import org.opendolphin.core.server.DefaultServerDolphin;
import org.opendolphin.core.server.action.DolphinServerAction;
import org.opendolphin.core.server.comm.ActionRegistry;
import org.opendolphin.core.server.comm.CommandHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * This class defines the central entry point for a Dolphin Platform session on the server.
 * Each Dolphin Platform client context on the client side is connected with one {@link DolphinContext}.
 */
public class DolphinContext {

    private static final Logger LOG = LoggerFactory.getLogger(DolphinContext.class);

    private final RemotingConfiguration configuration;

    private final DefaultServerDolphin dolphin;

    private final ServerBeanRepository beanRepository;

    private final Converters converters;

    private final BeanManager beanManager;

    private final ControllerHandler controllerHandler;

    private final EventDispatcher dispatcher;

    private ServerPlatformBeanRepository platformBeanRepository;

    private final DolphinContextMBeanRegistry mBeanRegistry;

    private final Callback<DolphinContext> onDestroyCallback;

    private final Subscription mBeanSubscription;

    private final GarbageCollector garbageCollector;

    private final DolphinContextTaskQueue taskQueue;

    private final ClientSession clientSession;

    private boolean hasResponseCommands = false;

    public DolphinContext(final RemotingConfiguration configuration, ClientSession clientSession, ClientSessionProvider clientSessionProvider, ManagedBeanFactory beanFactory, ControllerRepository controllerRepository, Callback<DolphinContext> onDestroyCallback) {
        this.configuration = Assert.requireNonNull(configuration, "configuration");
        Assert.requireNonNull(beanFactory, "beanFactory");
        Assert.requireNonNull(controllerRepository, "controllerRepository");
        this.onDestroyCallback = Assert.requireNonNull(onDestroyCallback, "onDestroyCallback");
        this.clientSession = Assert.requireNonNull(clientSession, "clientSession");

        //Init Open Dolphin
        dolphin = new OpenDolphinFactory().create();

        //Init Garbage Collection
        garbageCollector = new GarbageCollector(configuration, new GarbageCollectionCallback() {
            @Override
            public void onReject(Set<Instance> instances) {
                for (Instance instance : instances) {
                    beanRepository.onGarbageCollectionRejection(instance.getBean());
                }
            }
        });

        CommunicationManager manager = new CommunicationManager() {
            @Override
            public boolean hasResponseCommands() {
                return hasResponseCommands || dolphin.getModelStore().hasResponseCommands();
            }
        };
        taskQueue = new DolphinContextTaskQueue(clientSession.getId(), clientSessionProvider, manager, configuration.getMaxPollTime(), TimeUnit.MILLISECONDS);

        //Init BeanRepository
        dispatcher = new ServerEventDispatcher(dolphin);
        beanRepository = new ServerBeanRepositoryImpl(dolphin, dispatcher, garbageCollector);
        converters = new Converters(beanRepository);

        //Init BeanManager
        final PresentationModelBuilderFactory builderFactory = new ServerPresentationModelBuilderFactory(dolphin);
        final ClassRepository classRepository = new ClassRepositoryImpl(dolphin.getModelStore(), converters, builderFactory);
        final ListMapper listMapper = new ListMapperImpl(dolphin.getModelStore(), classRepository, beanRepository, builderFactory, dispatcher);
        final ServerBeanBuilder beanBuilder = new ServerBeanBuilderImpl(classRepository, beanRepository, listMapper, builderFactory, dispatcher, garbageCollector);
        beanManager = new BeanManagerImpl(beanRepository, beanBuilder);

        //Init MBean Support
        mBeanRegistry = new DolphinContextMBeanRegistry(clientSession.getId());

        //Init ControllerHandler
        controllerHandler = new ControllerHandler(mBeanRegistry, beanFactory, beanBuilder, beanRepository, controllerRepository);

        //Register commands
        registerDolphinPlatformDefaultCommands();
        mBeanSubscription = mBeanRegistry.registerDolphinContext(clientSession, garbageCollector);
    }

    private <T extends Command> void registerCommand(final ActionRegistry registry, final Class<T> commandClass, final Callback<T> handler) {
        Assert.requireNonNull(registry, "registry");
        Assert.requireNonNull(commandClass, "commandClass");
        Assert.requireNonNull(handler, "handler");
        registry.register(commandClass, new CommandHandler() {
            @Override
            public void handleCommand(final Command command, final List response) {
                LOG.trace("Handling {} for DolphinContext {}", commandClass.getSimpleName(), getId());
                handler.call((T) command);
            }
        });
    }

    private void registerDolphinPlatformDefaultCommands() {
        dolphin.getServerConnector().register(new DolphinServerAction() {
            @Override
            public void registerIn(ActionRegistry registry) {

                registerCommand(registry, CreateContextCommand.class, new Callback<CreateContextCommand>() {
                    @Override
                    public void call(CreateContextCommand createContextCommand) {
                        onInitContext();
                    }
                });

                registerCommand(registry, DestroyContextCommand.class, new Callback<DestroyContextCommand>() {
                    @Override
                    public void call(DestroyContextCommand createContextCommand) {
                        onDestroyContext();
                    }
                });


                registerCommand(registry, CreateControllerCommand.class, new Callback<CreateControllerCommand>() {
                    @Override
                    public void call(CreateControllerCommand createContextCommand) {
                        onRegisterController();
                    }
                });


                registerCommand(registry, DestroyControllerCommand.class, new Callback<DestroyControllerCommand>() {
                    @Override
                    public void call(DestroyControllerCommand createContextCommand) {
                        onDestroyController();
                    }
                });

                registerCommand(registry, CallActionCommand.class, new Callback<CallActionCommand>() {
                    @Override
                    public void call(CallActionCommand createContextCommand) {
                        onCallControllerAction();
                    }
                });

                registerCommand(registry, StartLongPollCommand.class, new Callback<StartLongPollCommand>() {
                    @Override
                    public void call(StartLongPollCommand createContextCommand) {
                        if (configuration.isUseGc()) {
                            LOG.trace("Handling GarbageCollection for DolphinContext {}", getId());
                            onGarbageCollection();
                        }
                        onPollEventBus();
                    }
                });

                registerCommand(registry, InterruptLongPollCommand.class, new Callback<InterruptLongPollCommand>() {
                    @Override
                    public void call(InterruptLongPollCommand createContextCommand) {
                        onReleaseEventBus();
                    }
                });

            }
        });
    }

    private void onInitContext() {
        platformBeanRepository = new ServerPlatformBeanRepository(dolphin, beanRepository, dispatcher, converters);
    }

    private void onDestroyContext() {
        destroy();
    }

    public void destroy() {
        controllerHandler.destroyAllControllers();

        if (mBeanSubscription != null) {
            mBeanSubscription.unsubscribe();
        }

        onDestroyCallback.call(this);
    }

    private void onRegisterController() {
        if (platformBeanRepository == null) {
            throw new IllegalStateException("An action was called before the init-command was sent.");
        }
        final InternalAttributesBean bean = platformBeanRepository.getInternalAttributesBean();
        String controllerId = controllerHandler.createController(bean.getControllerName());
        bean.setControllerId(controllerId);
        Object model = controllerHandler.getControllerModel(controllerId);
        if (model != null) {
            bean.setModel(model);
        }
    }

    private void onDestroyController() {
        if (platformBeanRepository == null) {
            throw new IllegalStateException("An action was called before the init-command was sent.");
        }
        final InternalAttributesBean bean = platformBeanRepository.getInternalAttributesBean();
        controllerHandler.destroyController(bean.getControllerId());
    }

    private void onCallControllerAction() {
        if (platformBeanRepository == null) {
            throw new IllegalStateException("An action was called before the init-command was sent.");
        }
        final ServerControllerActionCallBean bean = platformBeanRepository.getControllerActionCallBean();
        try {
            controllerHandler.invokeAction(bean);
        } catch (Exception e) {
            LOG.error("Unexpected exception while invoking action {} on controller {}",
                    bean.getActionName(), bean.getControllerId(), e);
            bean.setError(true);
        }
    }

    private void onReleaseEventBus() {
        taskQueue.interrupt();
    }

    private void onPollEventBus() {
        taskQueue.executeTasks();
    }

    private void onGarbageCollection() {
        garbageCollector.gc();
    }

    public DefaultServerDolphin getDolphin() {
        return dolphin;
    }

    public BeanManager getBeanManager() {
        return beanManager;
    }

    public String getId() {
        return clientSession.getId();
    }

    public List<Command> handle(List<Command> commands) {
        List<Command> results = new LinkedList<>();
        for (Command command : commands) {
            results.addAll(dolphin.getServerConnector().receive(command));
            hasResponseCommands = !results.isEmpty();
        }
        return results;
    }

    public ClientSession getDolphinSession() {
        return clientSession;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DolphinContext that = (DolphinContext) o;

        return getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }

    public Future<Void> runLater(final Runnable runnable) {
        return taskQueue.addTask(runnable);
    }

    public <T> Future<T> callLater(final Callable<T> callable) {
        return taskQueue.addTask(callable);
    }
}
