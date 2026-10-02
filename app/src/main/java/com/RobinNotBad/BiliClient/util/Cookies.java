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
            // 必须按第一个 '=' 切分：Cookie 值本身允许含 '='（如 buvid3、bili_ticket 的 base64 填充），
            // 用 split("=") + length==2 会把这类 Cookie 整体丢弃（审计 S2 的漏修点）
            int eq = cookie.indexOf('=');
            if (eq <= 0 || eq == cookie.length() - 1) continue; // 无 '='、空键或空值都跳过
            cookieMap.put(cookie.substring(0, eq).trim(), cookie.substring(eq + 1));
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
