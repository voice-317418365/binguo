package com.veyra.gybeyond;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * 验证 0.31.20/0.31.21 合入的「红果匿名身份落盘复用」逻辑。
 * 不依赖真实网络：预先把一台假设备写进 SharedPreferences，init() 应当把它加载为
 * 现用身份，identity() 应当直接复用而不去注册新设备。
 */
public class HongguoIdentityTest {
    private static final String PREFS = "hongguo-identity";

    private Context ctx() {
        return androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field f = HongguoDirect.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    @Test public void initLoadsPersistedIdentityAndReusesItWithoutRegister() throws Exception {
        // 清空内存缓存，模拟冷启动；注入一台已知设备到磁盘。
        setStatic("appContext", null);
        setStatic("cached", null);
        setStatic("cachedAt", 0L);
        SharedPreferences prefs = ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        prefs.edit()
                .putString("device_id", "1234567890123")
                .putString("iid", "9876543210987")
                .putString("cdid", "11111111-2222-3333-4444-555555555555")
                .putLong("saved_at", System.currentTimeMillis())
                .apply();

        // init 应当从磁盘加载，identity 应当直接返回这台设备（不发注册请求）。
        Method init = HongguoDirect.class.getDeclaredMethod("init", Context.class);
        init.setAccessible(true);
        init.invoke(null, ctx());

        Method identity = HongguoDirect.class.getDeclaredMethod("identity");
        identity.setAccessible(true);
        Object device = identity.invoke(null);
        assertNotNull("身份不应为空", device);

        Field did = device.getClass().getDeclaredField("deviceId");
        did.setAccessible(true);
        Field iid = device.getClass().getDeclaredField("iid");
        iid.setAccessible(true);
        assertEquals("应复用落盘的 device_id，而非新注册", "1234567890123", did.get(device));
        assertEquals("应复用落盘的 iid，而非新注册", "9876543210987", iid.get(device));
    }

    @Test public void realRegisterPersistsIdentity() throws Exception {
        // 真实走一次匿名注册（含 activate），验证「注册 -> 落盘」链路，并确认 activate 不阻断注册。
        setStatic("appContext", ctx());
        setStatic("cached", null);
        setStatic("cachedAt", 0L);
        SharedPreferences prefs = ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();

        Method identity = HongguoDirect.class.getDeclaredMethod("identity");
        identity.setAccessible(true);
        Object device;
        try {
            device = identity.invoke(null);
        } catch (java.lang.reflect.InvocationTargetException error) {
            // 注册依赖红果服务端；模拟器无真实设备指纹可能被拒。打印诊断后跳过断言以免误判为代码缺陷。
            android.util.Log.w("VeyraIdentityTest", "真实注册未成功（环境/风控）: " + error.getCause());
            org.junit.Assume.assumeNoException("真实注册不可用，跳过", error.getCause());
            return;
        }
        assertNotNull("注册应返回设备", device);
        Field did = device.getClass().getDeclaredField("deviceId");
        did.setAccessible(true);
        String diskId = prefs.getString("device_id", "");
        assertFalse("device_id 不应为空", ((String) did.get(device)).isEmpty());
        assertEquals("注册身份应已落盘", did.get(device), diskId);
        assertTrue("saved_at 应写入", prefs.getLong("saved_at", 0L) > 0L);
    }

    @Test public void expiredIdentityIsNotLoaded() throws Exception {
        setStatic("appContext", null);
        setStatic("cached", null);
        setStatic("cachedAt", 0L);
        SharedPreferences prefs = ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        prefs.edit()
                .putString("device_id", "1234567890123")
                .putString("iid", "9876543210987")
                .putLong("saved_at", 0L)   // 1970 年，远超 7 天 TTL
                .apply();

        Method load = HongguoDirect.class.getDeclaredMethod("loadIdentity");
        load.setAccessible(true);
        Method init = HongguoDirect.class.getDeclaredMethod("init", Context.class);
        init.setAccessible(true);
        init.invoke(null, ctx());
        Object loaded = load.invoke(null);
        assertNull("过期身份不应被加载", loaded);
    }
}
