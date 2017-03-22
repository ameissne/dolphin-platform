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
package com.canoo.dolphin.client.impl;

import com.canoo.dolphin.util.Assert;
import org.opendolphin.core.client.ClientDolphin;
import org.opendolphin.core.client.comm.OnFinishedHandler;
import org.opendolphin.core.comm.Command;
import org.opendolphin.core.comm.NamedCommand;

import java.util.concurrent.CompletableFuture;

public class DolphinCommandHandler {
    private final ClientDolphin clientDolphin;

    public DolphinCommandHandler(ClientDolphin clientDolphin) {
        Assert.requireNonNull(clientDolphin, "clientDolphin");
        this.clientDolphin = clientDolphin;
    }

    @Deprecated
    public CompletableFuture<Void> invokeDolphinCommand(String commandName) {
        return invokeDolphinCommand(new NamedCommand(commandName));
    }

    public CompletableFuture<Void> invokeDolphinCommand(Command command) {
        Assert.requireNonNull(command, "command");
        final CompletableFuture<Void> result = new CompletableFuture<>();
        clientDolphin.send(command, new OnFinishedHandler() {
            @Override
            public void onFinished() {
                result.complete(null);
            }
        });
        return result;
    }
}
