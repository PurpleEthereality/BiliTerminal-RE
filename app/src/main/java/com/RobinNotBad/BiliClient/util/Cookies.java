package com.RobinNotBad.BiliClient.util;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

public class Cookies {
    private final Map<String, String> cookieMap = new HashMap<>();

    public Cookies(String cookieString) {
        parseCookieString(cookieString);
    }

    private void parseCookieString(String cookieString) {
        cookieMap.clear();
        String[] cookies = cookieString.split("; ");
        for (String cookie : cookies) {
            String[] parts = cookie.split("=");
            if (parts.length == 2) {
                cookieMap.put(parts[0], parts[1]);
            }
        }
    }

    public void set(String key, String value) {
        // value 为 null 时必须移除该键而不是 put(null)：后续 toString() 用字符串拼接，
        // put 进去会被拼成字面量 "key=null" 并污染整条 Cookie 串发给服务端（审计附带项）
        if (value == null) {
            cookieMap.remove(key);
            return;
        }
        cookieMap.put(key, value);
    }

    public String get(String key) {
        return cookieMap.get(key);
    }

    public String getOrDefault(String key, String defaultVal) {
        String val = cookieMap.get(key);
        return val != null ? val : defaultVal;
    }

    public boolean containsKey(String key) {
        return cookieMap.containsKey(key);
    }

    @NonNull
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookieMap.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
        }
        return sb.toString();
    }

}
