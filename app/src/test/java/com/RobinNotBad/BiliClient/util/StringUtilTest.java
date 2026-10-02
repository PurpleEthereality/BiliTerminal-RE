package com.RobinNotBad.BiliClient.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;

public class StringUtilTest {

    @Test
    public void getTextHeightWithSize_firstCall_doesNotUnboxNull() {
        Throwable caught = null;
        try {
            StringUtil.getTextHeightWithSize(null);
        } catch (Throwable t) {
            caught = t;
        }
        assertFalse("首次调用触发 NullPointerException：" + caught, caught instanceof NullPointerException);
        // 审计 M13-d：原为 assertTrue(caught == null || caught instanceof RuntimeException)，
        // caught 为 null 时左边即为真，断言恒成立、等于没断言。
        // 改成有实际约束力的检查：非 NPE 的失败也只应是一般异常，不能是 Error。
        assertFalse("首次调用抛出了 Error：" + caught, caught instanceof Error);
    }
}
