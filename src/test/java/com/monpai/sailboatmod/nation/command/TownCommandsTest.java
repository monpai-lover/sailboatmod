package com.monpai.sailboatmod.nation.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class TownCommandsTest {
    private static CommandNode<CommandSourceStack> townChild(String sub) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        NationCommands.register(dispatcher);
        CommandNode<CommandSourceStack> town = dispatcher.getRoot().getChild("town");
        assertNotNull(town, "/town root should be registered");
        return town.getChild(sub);
    }

    @Test
    void registersTownSubcommands() {
        for (String sub : new String[]{"apply", "invite", "accept", "reject", "join", "decline", "leave", "kick", "list", "members", "requests"}) {
            assertNotNull(townChild(sub), "/town " + sub + " should be registered");
        }
    }
}
