package com.ksiu.plugin.protocol.builder;

import java.nio.charset.StandardCharsets;

public class PluginMessageBuilder
{
    private final StringBuilder builder = new StringBuilder();

    public PluginMessageBuilder add(String message, String... args)
    {
        builder.append(message);

        for (String arg : args)
        {
            builder.append(' ')
                    .append(arg);
        }

        builder.append('\n');

        return this;
    }

    public byte[] build()
    {
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    public void clear()
    {
        builder.setLength(0);
    }
}
