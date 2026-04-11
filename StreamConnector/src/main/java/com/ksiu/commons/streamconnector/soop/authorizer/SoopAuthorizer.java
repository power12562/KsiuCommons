package com.ksiu.commons.streamconnector.soop.authorizer;

import com.ksiu.commons.streamconnector.soop.token.SoopToken;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SoopAuthorizer
{
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    public final CompletableFuture<SoopToken> requestToken(String bjId)
    {
        CompletableFuture<SoopToken> future = new CompletableFuture<>();
        String requestURL = String.format("https://live.sooplive.co.kr/afreeca/player_live_api.php?bjid=%s", bjId);

        Map<Object, Object> formData = new HashMap<>();
        formData.put("bid", bjId);
        formData.put("type", "live");
        formData.put("pwd", "");
        formData.put("player_type", "html5");
        formData.put("stream_type", "common");
        formData.put("quality", "HD");
        formData.put("mode", "landing");
        formData.put("is_revive", "false");
        formData.put("from_api", "0");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(requestURL))
                .header("User-Agent", "Mozilla/5.0")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(mapToBody(formData))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response ->
                {
                    if (response.statusCode() == 200)
                    {
                        try
                        {
                            JSONObject jsonObject = new JSONObject(response.body());
                            JSONObject channel = jsonObject.getJSONObject("CHANNEL");
                            SoopToken token = new SoopToken(channel);
                            future.complete(token);
                        }
                        catch (Exception e)
                        {
                            future.completeExceptionally(new RuntimeException("토큰 생성 중 오류 발생: " + e.getMessage()));
                        }
                    }
                    else
                    {
                        future.completeExceptionally(new RuntimeException("유효하지 않는 BJ ID 입니다."));
                    }
                })
                .exceptionally(throwable ->
                {
                    future.completeExceptionally(throwable);
                    return null;
                });

        return future;
    }

    public static HttpRequest.BodyPublisher mapToBody(Map<Object, Object> data)
    {
        var builder = new StringBuilder();
        for (Map.Entry<Object, Object> entry : data.entrySet())
        {
            if (!builder.isEmpty())
            {
                builder.append("&");
            }

            builder.append(URLEncoder.encode(entry.getKey().toString(), StandardCharsets.UTF_8));
            builder.append("=");
            builder.append(URLEncoder.encode(entry.getValue().toString(), StandardCharsets.UTF_8));
        }
        return HttpRequest.BodyPublishers.ofString(builder.toString());
    }
}
