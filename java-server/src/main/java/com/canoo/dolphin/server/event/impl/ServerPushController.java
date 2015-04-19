package com.canoo.dolphin.server.event.impl;

import com.canoo.dolphin.server.DolphinAction;
import com.canoo.dolphin.server.DolphinController;

@DolphinController("ServerPushController")
public class ServerPushController {

    @DolphinAction
    public void longPoll() {
        try {
            DolphinEventBusImpl.getInstance().listenOnEventsForCurrentDolphinSession();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @DolphinAction
    public void release() {
        DolphinEventBusImpl.getInstance().release();
    }
}
