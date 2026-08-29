package com.julflips.nerv_printer.commands;

import com.julflips.nerv_printer.modules.CarpetPrinter;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class DebugWipeCommand extends Command {
    public DebugWipeCommand() {
        super("debugwipe", "Trigger the wipe sequence for testing (requires CarpetPrinter active with reset button and perimeter corners configured).");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            CarpetPrinter printer = Modules.get().get(CarpetPrinter.class);
            if (printer == null || !printer.isActive()) {
                error("CarpetPrinter module is not active.");
                return SINGLE_SUCCESS;
            }
            printer.triggerDebugWipe();
            return SINGLE_SUCCESS;
        });
    }
}
