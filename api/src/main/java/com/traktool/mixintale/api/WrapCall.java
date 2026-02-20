package com.traktool.mixintale.api;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface WrapCall {
    String targetMethod();
    String targetDesc();
    String owner();
    String name();
    String desc();
    int ordinal() default -1;
    int require() default 1;
}
