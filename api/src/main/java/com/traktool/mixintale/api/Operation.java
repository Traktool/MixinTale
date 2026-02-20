package com.traktool.mixintale.api;

@FunctionalInterface
public interface Operation<R> {
    R call(Object... args) throws Throwable;
}
