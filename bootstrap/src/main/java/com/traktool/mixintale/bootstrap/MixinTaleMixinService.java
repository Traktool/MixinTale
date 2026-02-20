package com.traktool.mixintale.bootstrap;

import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.*;
import org.spongepowered.asm.util.ReEntranceLock;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.Collection;

public final class MixinTaleMixinService implements IMixinService {
    private final ReEntranceLock reEntranceLock = new ReEntranceLock(1);

    private final IClassProvider classProvider = (IClassProvider) Proxy.newProxyInstance(
            IClassProvider.class.getClassLoader(),
            new Class<?>[]{IClassProvider.class},
            (proxy, method, args) -> {
                String name = method.getName();
                return switch (name) {
                    case "findClass" -> {
                        String className = (String) args[0];
                        boolean initialize = args != null && args.length > 1 && args[1] instanceof Boolean b && b;
                        yield Class.forName(className, initialize, Thread.currentThread().getContextClassLoader());
                    }
                    case "findAgentClass" -> {
                        String className = (String) args[0];
                        boolean initialize = args != null && args.length > 1 && args[1] instanceof Boolean b && b;
                        yield Class.forName(className, initialize, Thread.currentThread().getContextClassLoader());
                    }
                    case "getClassPath" -> new URL[0];
                    default -> defaultValue(method.getReturnType());
                };
            }
    );

    private final ILogger logger = (ILogger) Proxy.newProxyInstance(
            ILogger.class.getClassLoader(),
            new Class<?>[]{ILogger.class},
            (proxy, method, args) -> defaultValue(method.getReturnType())
    );

    @Override
    public String getName() {
        return "MixinTaleService";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void prepare() {
    }

    @Override
    public MixinEnvironment.Phase getInitialPhase() {
        return MixinEnvironment.Phase.PREINIT;
    }

    @Override
    public void offer(IMixinInternal internal) {
    }

    @Override
    public void init() {
    }

    @Override
    public void beginPhase() {
    }

    @Override
    public void checkEnv(Object bootSource) {
    }

    @Override
    public ReEntranceLock getReEntranceLock() {
        return reEntranceLock;
    }

    @Override
    public IClassProvider getClassProvider() {
        return classProvider;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public IClassTracker getClassTracker() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return java.util.List.of();
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return null;
    }

    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        return java.util.List.of();
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
    }

    @Override
    public String getSideName() {
        return "SERVER";
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_21;
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_21;
    }

    @Override
    public ILogger getLogger(String name) {
        return logger;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }
}
