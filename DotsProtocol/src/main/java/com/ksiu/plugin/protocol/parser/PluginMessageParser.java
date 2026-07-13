package com.ksiu.plugin.protocol.parser;

import com.ksiu.plugin.protocol.interfaces.IMessageParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PluginMessageParser
{
    private static final Map<String, IMessageParser> parsers = new ConcurrentHashMap<>();

    public static boolean registerParser(String message, IMessageParser parser)
    {
        return parsers.putIfAbsent(message, parser) == null;
    }

    public static void parse(byte[] bytes)
    {
        String rawStr = new String(bytes, StandardCharsets.UTF_8);
        int start = 0;
        for (int i = 0; i <= rawStr.length(); i++)
        {
            if (i == rawStr.length() || rawStr.charAt(i) == '\n')
            {
                if (i > start)
                    parseMessage(rawStr, start, i);

                start = i + 1;
            }
        }
    }

    private static void parseMessage(String str, int start, int end)
    {
        List<String> tokens = new ArrayList<>();
        int tokenStart = -1;

        for (int i = start; i < end; i++)
        {
            char c = str.charAt(i);

            if (c == ' ' || c == '\r')
            {
                if (tokenStart != -1)
                {
                    tokens.add(str.substring(tokenStart, i));
                    tokenStart = -1;
                }
            }
            else
            {
                if (tokenStart == -1)
                    tokenStart = i;
            }
        }

        if (tokenStart != -1)
            tokens.add(str.substring(tokenStart, end));

        if (tokens.isEmpty())
            return;

        String message = tokens.getFirst();
        IMessageParser parser = parsers.get(message);
        if (parser == null)
            return;

        String[] args = tokens.subList(1, tokens.size()).toArray(String[]::new);
        parser.parse(args);
    }
}
