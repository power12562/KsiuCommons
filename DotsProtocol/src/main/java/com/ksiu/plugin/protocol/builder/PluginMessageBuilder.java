package com.ksiu.plugin.protocol.builder;

import java.nio.charset.StandardCharsets;

public class PluginMessageBuilder
{
    private final StringBuilder builder = new StringBuilder();

    public PluginMessageBuilder add(String... args)
    {
        builder.append(String.join(" ", args));
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
