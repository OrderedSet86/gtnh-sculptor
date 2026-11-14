package com.gtnh.sculptor;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentTranslation;

/** Send a chat line as a lang key. The key is translated on the client, into the language of the player. */
public final class SculptorChat {

    private SculptorChat() {}

    public static void sendTranslated(ICommandSender recipient, String langKey, Object... formatValues) {
        recipient.addChatMessage(new ChatComponentTranslation(langKey, formatValues));
    }
}
