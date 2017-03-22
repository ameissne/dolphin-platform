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
package org.opendolphin.core.comm

import core.client.comm.InMemoryClientConnector
import org.junit.Ignore
import org.opendolphin.LogConfig
import org.opendolphin.core.PresentationModel
import org.opendolphin.core.client.ClientAttribute
import org.opendolphin.core.client.ClientDolphin
import org.opendolphin.core.client.comm.BlindCommandBatcher
import org.opendolphin.core.client.comm.OnFinishedHandler
import org.opendolphin.core.client.comm.RunLaterUiThreadHandler
import org.opendolphin.core.client.comm.SynchronousInMemoryClientConnector
import org.opendolphin.core.server.*
import org.opendolphin.core.server.action.DolphinServerAction
import org.opendolphin.core.server.comm.ActionRegistry
import org.opendolphin.core.server.comm.CommandHandler
import org.opendolphin.util.DirectExecutor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Level
/**
 * Showcase for how to test an application without the GUI by
 * issuing the respective commands and model changes against the
 * ClientModelStore
 */

class FunctionalPresentationModelTests extends GroovyTestCase {

    private final class CreateCommand extends Command {}
    private final class PerformanceCommand extends Command {}
    private final class CheckNotificationReachedCommand extends Command {}
    private final class JavaCommand extends Command {}
    private final class ArbitraryCommand extends Command {}
    private final class DeleteCommand extends Command {}
    private final class FetchDataCommand extends Command {}
    private final class LoginCommand extends Command {}
    private final class SomeCommand extends Command {}
    private final class NoSuchActionRegisteredCommand extends Command {}
    private final class Set2Command extends Command {}
    private final class Assert3Command extends Command {}
    private final class CheckTagIsKnownOnServerSideCommand extends Command {}


    volatile TestInMemoryConfig context
    DefaultServerDolphin serverDolphin
    ClientDolphin clientDolphin

    @Override
    protected void setUp() {
        context = new TestInMemoryConfig()
        serverDolphin = context.serverDolphin
        clientDolphin = context.clientDolphin
        LogConfig.logOnLevel(Level.OFF);
    }

    @Override
    protected void tearDown() {
        assert context.done.await(10, TimeUnit.SECONDS)
    }

    void testQualifiersInClientPMs() {
        PresentationModel modelA = clientDolphin.presentationModel("1", new ClientAttribute("a", 0, "QUAL"))
        PresentationModel modelB = clientDolphin.presentationModel("2", new ClientAttribute("b", 0, "QUAL"))

        modelA.getAttribute("a").setValue(1)

        assert modelB.getAttribute("b").getValue() == 1
        context.assertionsDone() // make sure the assertions are really executed
    }

    void testPerformanceWithStandardCommandBatcher() {
        doTestPerformance()
    }

    void testPerformanceWithBlindCommandBatcher() {
        def batcher = new BlindCommandBatcher(mergeValueChanges: true, deferMillis: 100)
        def connector = new InMemoryClientConnector(context.clientDolphin, serverDolphin.serverConnector, batcher, new RunLaterUiThreadHandler())
        context.clientDolphin.clientConnector = connector
        doTestPerformance()
    }

    void testPerformanceWithSynchronousConnector() {
        def connector = new SynchronousInMemoryClientConnector(context.clientDolphin, serverDolphin.serverConnector)
        context.clientDolphin.clientConnector = connector
        doTestPerformance()
    }


    void doTestPerformance() {
        long id = 0
        registerAction serverDolphin, PerformanceCommand.class, { cmd, response ->
            100.times { attr ->
                serverDolphin.presentationModelCommand(response, "id_${id++}".toString(), null, new DTO(new Slot("attr_$attr", attr)))
            }
        }
        def start = System.nanoTime()
        100.times { soOften ->
            clientDolphin.send new PerformanceCommand(), new OnFinishedHandler() {
                @Override
                void onFinished() {
                }
            }
        }
        clientDolphin.send new PerformanceCommand(), new OnFinishedHandler() {

            @Override
            void onFinished() {
                println((System.nanoTime() - start).intdiv(1_000_000))
                context.assertionsDone() // make sure the assertions are really executed
            }
        }
    }

    void testCreationRoundtripDefaultBehavior() {
        registerAction serverDolphin, CreateCommand.class, { cmd, response ->
            serverDolphin.presentationModelCommand(response, "id".toString(), null, new DTO(new Slot("attr", 'attr')))
        }
        registerAction serverDolphin, CheckNotificationReachedCommand.class, { cmd, response ->
            assert 1 == serverDolphin.listPresentationModels().size()
            assert serverDolphin.getPresentationModel("id")
        }

        clientDolphin.send new CreateCommand(), new OnFinishedHandler() {

            @Override
            void onFinished() {
                clientDolphin.send new CheckNotificationReachedCommand(), new OnFinishedHandler() {

                    @Override
                    void onFinished() {
                        context.assertionsDone() // make sure the assertions are really executed
                    }
                }
            }
        }
    }

    void testCreationRoundtripForTags() {
        registerAction serverDolphin, CreateCommand.class, { cmd, response ->
            def NO_TYPE = null
            def NO_QUALIFIER = null
            serverDolphin.presentationModelCommand(response, "id".toString(), NO_TYPE, new DTO(new Slot("attr", true, NO_QUALIFIER)))
        }
        registerAction serverDolphin, CheckTagIsKnownOnServerSideCommand.class, { cmd, response ->
        }

        clientDolphin.send new CreateCommand(), new OnFinishedHandler() {

            @Override
            void onFinished() {
                clientDolphin.send new CheckTagIsKnownOnServerSideCommand(), new OnFinishedHandler() {

                    @Override
                    void onFinished() {
                        context.assertionsDone()
                    }
                }
            }
        }
    }

    void testFetchingAnInitialListOfData() {
        registerAction serverDolphin, FetchDataCommand.class, { cmd, response ->
            ('a'..'z').each {
                DTO dto = new DTO(new Slot('char', it))
                // sending CreatePresentationModelCommand _without_ adding the pm to the server model store
                serverDolphin.presentationModelCommand(response, it, null, dto)
            }
        }
        clientDolphin.send new FetchDataCommand(), new OnFinishedHandler() {

            @Override
            void onFinished() {
                // pmIds from a single action should come in sequence
                assert 'a' == context.clientDolphin.getPresentationModel('a').getAttribute("char").value
                assert 'z' == context.clientDolphin.getPresentationModel('z').getAttribute("char").value
                context.assertionsDone() // make sure the assertions are really executed
            }
        }
    }

    public <T extends Command> void registerAction(ServerDolphin serverDolphin, Class<T> commandClass, CommandHandler<T> handler) {
        serverDolphin.register(new DolphinServerAction() {

            @Override
            void registerIn(ActionRegistry registry) {
                registry.register(commandClass, handler);
            }
        });
    }

    void testLoginUseCase() {
        registerAction serverDolphin, LoginCommand.class, { cmd, response ->
            def user = context.serverDolphin.getPresentationModel('user')
            if (user.getAttribute("name").value == 'Dierk' && user.getAttribute("password").value == 'Koenig') {
                DefaultServerDolphin.changeValueCommand(response, user.getAttribute("loggedIn"), 'true')
            }
        }
        def user = clientDolphin.presentationModel 'user', name: null, password: null, loggedIn: null
        clientDolphin.send new LoginCommand(), new OnFinishedHandler() {

            @Override
            void onFinished() {
                assert !user.getAttribute("loggedIn").value
            }
        }
        user.getAttribute("name").value = "Dierk"
        user.getAttribute("password").value = "Koenig"

        clientDolphin.send new LoginCommand(), new OnFinishedHandler() {

            @Override
            void onFinished() {
                assert user.getAttribute("loggedIn").value
                context.assertionsDone()
            }
        }
    }

    void testAsynchronousExceptionOnTheServer() {
        LogConfig.logOnLevel(Level.INFO);
        def count = 0
        clientDolphin.clientConnector.onException = { count++ }

        registerAction serverDolphin, SomeCommand.class, { cmd, response ->
            throw new RuntimeException("EXPECTED: some arbitrary exception on the server")
        }

        clientDolphin.send new SomeCommand(), new OnFinishedHandler() {

            @Override
            void onFinished() {
                fail "the onFinished handler will not be reached in this case"
            }
        }
        clientDolphin.sync {
            assert count == 1
        }

        // provoke a second exception
        clientDolphin.send new SomeCommand(), new OnFinishedHandler() {

            @Override
            void onFinished() {
                fail "the onFinished handler will not be reached either"
            }
        }
        clientDolphin.sync {
            assert count == 2
        }
        clientDolphin.sync {
            context.assertionsDone()
        }
    }

    @Ignore
    void testAsynchronousExceptionInOnFinishedHandler() {
        context = new TestInMemoryConfig(DirectExecutor.getInstance());
        serverDolphin = context.serverDolphin
        clientDolphin = context.clientDolphin

        // not "run later" we need it immediately here
        clientDolphin.clientConnector.onException = { context.assertionsDone() }

        registerAction serverDolphin, SomeCommand.class, { cmd, response ->
            // nothing to do
        }
        clientDolphin.send new SomeCommand(), new OnFinishedHandler() {

            @Override
            void onFinished() {
                throw new RuntimeException("EXPECTED: some arbitrary exception in the onFinished handler")
            }
        }
    }

    void testUnregisteredCommandWithLog() {
        serverDolphin.serverConnector.setLogLevel(Level.ALL);
        clientDolphin.send new NoSuchActionRegisteredCommand(), new OnFinishedHandler() {

            @Override
            void onFinished() {
// unknown actions are silently ignored and logged as warnings on the server side.
            }
        }
        context.assertionsDone()
    }

    void testUnregisteredCommandWithoutLog() {
        serverDolphin.serverConnector.setLogLevel(Level.OFF);
        clientDolphin.send(new NoSuchActionRegisteredCommand(), null)
        context.assertionsDone()
    }

    // silly and only for the coverage, we test behavior when id is wrong ...
    void testIdNotFoundInVariousCommands() {
        clientDolphin.clientConnector.send new ValueChangedCommand(attributeId: 0)
        DefaultServerDolphin.changeValueCommand(null, null, null)
        DefaultServerDolphin.changeValueCommand(null, new ServerAttribute('a', 42), 42)
        context.assertionsDone()
    }


    void testActionAndSendJavaLike() {
        boolean reached = false
        registerAction(serverDolphin, JavaCommand.class, new CommandHandler<JavaCommand>() {
            @Override
            void handleCommand(JavaCommand command, List<Command> response) {
                reached = true
            }
        });
        clientDolphin.send(new JavaCommand(), new OnFinishedHandler() {
            @Override
            void onFinished() {
                assert reached
                context.assertionsDone()
            }
        })
    }


    void testRemovePresentationModel() {
        clientDolphin.presentationModel('pm', attr: 1)

        registerAction serverDolphin, DeleteCommand.class, { cmd, response ->
//            serverDolphin.delete(response, serverDolphin['pm']) // deprecated
            serverDolphin.removePresentationModel(serverDolphin.getPresentationModel('pm'))
            assert serverDolphin.getPresentationModel('pm') == null
        }
        assert clientDolphin.getPresentationModel('pm')

        clientDolphin.send new DeleteCommand(), new OnFinishedHandler() {

            @Override
            void onFinished() {
                assert clientDolphin.getPresentationModel('pm') == null
                context.assertionsDone()
            }
        }
    }


    void testWithNullResponses() {
        clientDolphin.presentationModel('pm', attr: 1)

        registerAction serverDolphin, ArbitraryCommand.class, { cmd, response ->
            serverDolphin.deleteCommand([], null)
            serverDolphin.deleteCommand([], '')
            serverDolphin.deleteCommand(null, '')
            serverDolphin.presentationModelCommand(null, null, null, null)
            serverDolphin.changeValueCommand([], null, null)
        }
        clientDolphin.send(new ArbitraryCommand(), new OnFinishedHandler() {

            @Override
            void onFinished() {
                context.assertionsDone()
            }
        });
    }

    void testStateConflictBetweenClientAndServer() {
        LogConfig.logOnLevel(Level.INFO);
        def latch = new CountDownLatch(1)
        def pm = clientDolphin.presentationModel('pm', attr: 1)
        def attr = pm.getAttribute('attr')

        registerAction serverDolphin, Set2Command.class, { cmd, response ->
            latch.await() // mimic a server delay such that the client has enough time to change the value concurrently
            serverDolphin.getPresentationModel('pm').getAttribute('attr').value == 1
            serverDolphin.getPresentationModel('pm').getAttribute('attr').value = 2
            serverDolphin.getPresentationModel('pm').getAttribute('attr').value == 2 // immediate change of server state
        }
        registerAction serverDolphin, Assert3Command.class, { cmd, response ->
            assert serverDolphin.getPresentationModel('pm').getAttribute('attr').value == 3
        }

        clientDolphin.send(new Set2Command(), null) // a conflict could arise when the server value is changed ...
        attr.value = 3            // ... while the client value is changed concurrently
        latch.countDown()
        clientDolphin.send(new Assert3Command(), null)
        // since from the client perspective, the last change was to 3, server and client should both see the 3

        // in between these calls a conflicting value change could be transferred, setting both value to 2

        clientDolphin.send(new Assert3Command(), new OnFinishedHandler() {

            @Override
            void onFinished() {
                assert attr.value == 3
                context.assertionsDone()
            }
        });

    }

}