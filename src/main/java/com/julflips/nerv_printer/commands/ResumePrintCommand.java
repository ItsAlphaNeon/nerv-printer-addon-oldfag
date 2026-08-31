package com.julflips.nerv_printer.commands;

import com.julflips.nerv_printer.utils.SlaveSystem;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class ResumePrintCommand extends Command {
    public ResumePrintCommand() {
        super("resumeprint", "Resumes the whole hivemind (master + all slaves).");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            if (!SlaveSystem.isHiveActive()) {
                error("No printer module is active.");
                return SINGLE_SUCCESS;
            }
            SlaveSystem.resumeHive();
            info("Hivemind resumed.");
            return SINGLE_SUCCESS;
        });
    }
}
