package com.traktool.mixintale.api;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Replace {
    String targetMethod();
    String targetDesc();
}
