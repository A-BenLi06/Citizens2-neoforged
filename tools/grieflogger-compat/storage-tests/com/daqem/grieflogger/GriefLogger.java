package com.daqem.grieflogger;

/** Standalone tests replace only the Minecraft bootstrap/logger owner. */
public final class GriefLogger {
    public static final java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
    public static final org.slf4j.Logger LOGGER = (org.slf4j.Logger) java.lang.reflect.Proxy.newProxyInstance(
            GriefLogger.class.getClassLoader(), new Class<?>[]{org.slf4j.Logger.class}, (proxy, method, args) -> {
                if (method.getName().equals("error")) {
                    errors.incrementAndGet();
                    System.err.println("EXPECTED PROVIDER DIAGNOSTIC: " + args[0]);
                }
                return method.getReturnType() == boolean.class ? false : null;
            });
}
