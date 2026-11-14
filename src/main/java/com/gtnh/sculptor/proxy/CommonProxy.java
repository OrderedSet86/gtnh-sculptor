package com.gtnh.sculptor.proxy;

import com.gtnh.sculptor.network.SculptorNetwork;

/** Registers the handlers that a dedicated server and a client both need. */
public class CommonProxy {

    public void registerHandlers() {
        // a commit goes from the client to the server, so both sides open the channel
        SculptorNetwork.init();
    }

}
