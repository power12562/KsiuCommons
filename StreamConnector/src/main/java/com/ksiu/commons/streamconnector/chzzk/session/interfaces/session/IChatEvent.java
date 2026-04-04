package com.ksiu.commons.streamconnector.chzzk.session.interfaces.session;

import org.json.JSONObject;

@FunctionalInterface
public interface IChatEvent extends ISessionEvent
{
    public void execute(JSONObject chat);
}
