package com.traktool.mixintale.api;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Patch {
    String targetClass();
    int priority() default 1000;
}
