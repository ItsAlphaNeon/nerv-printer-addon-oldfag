package com.julflips.nerv_printer.commands;

import com.julflips.nerv_printer.modules.CarpetPrinter;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class StartPrinterCommand extends Command {
    public StartPrinterCommand() {
        super("startprinter", "Starts the printing process when in the chest selection phase.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            CarpetPrinter printer = Modules.get().get(CarpetPrinter.class);
            if (printer == null || !printer.isActive()) {
                error("Carpet Printer module is not active.");
                return SINGLE_SUCCESS;
            }
            printer.startPrinting();
            return SINGLE_SUCCESS;
        });
    }
}