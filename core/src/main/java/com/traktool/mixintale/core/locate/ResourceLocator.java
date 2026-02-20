package com.traktool.mixintale.core.locate;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface ResourceLocator {
    List<String> jars();

    InputStream openResource(String jar, String path) throws IOException;

    default InputStream openClass(String internalName) throws IOException {
        String classPath = internalName + ".class";
        for (String jar : jars()) {
            InputStream in = openResource(jar, classPath);
            if (in != null) {
                return in;
            }
        }
        return null;
    }
}
