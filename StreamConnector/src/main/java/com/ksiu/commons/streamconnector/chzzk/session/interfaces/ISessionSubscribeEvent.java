package com.ksiu.commons.streamconnector.chzzk.session.interfaces;

@FunctionalInterface
public interface ISessionSubscribeEvent
{
    void execute(String eventType, String channelId);
}
