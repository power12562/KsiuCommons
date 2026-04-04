package com.ksiu.commons.streamconnector.session.interfaces;

import org.json.JSONObject;

@FunctionalInterface
public interface IChatEvent
{
    public void execute(JSONObject chatObject);
}
