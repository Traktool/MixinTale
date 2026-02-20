package com.traktool.mixintale.processor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.traktool.mixintale.api.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.*;

@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedAnnotationTypes({
        "com.traktool.mixintale.api.Patch",
        "com.traktool.mixintale.api.Prefix",
        "com.traktool.mixintale.api.Postfix",
        "com.traktool.mixintale.api.Replace",
        "com.traktool.mixintale.api.RedirectCall",
        "com.traktool.mixintale.api.WrapCall",
        "com.traktool.mixintale.api.Accessor"
})
public final class MixinTaleProcessor extends AbstractProcessor {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, PatchEntry> entries = new TreeMap<>();
    private boolean written;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            if (!written) {
                writeIndex();
            }
            return false;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(Patch.class)) {
            if (element.getKind() != ElementKind.CLASS) continue;
            TypeElement patchType = (TypeElement) element;
            Patch patch = patchType.getAnnotation(Patch.class);
            PatchEntry entry = entries.computeIfAbsent(
                    patchType.getQualifiedName().toString(),
                    ignored -> new PatchEntry(patchType.getQualifiedName().toString(), patch.targetClass(), patch.priority())
            );
            for (Element enclosed : patchType.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) continue;
                ExecutableElement method = (ExecutableElement) enclosed;
                collectMethodAnnotations(entry, method);
            }
        }
        return false;
    }

    private void collectMethodAnnotations(PatchEntry patchEntry, ExecutableElement method) {
        Prefix prefix = method.getAnnotation(Prefix.class);
        if (prefix != null) {
            patchEntry.actions.add(new Action("PREFIX", method.getSimpleName().toString(), methodDescriptor(method), prefix.targetMethod(), prefix.targetDesc(), null, null, null, -1, 0));
        }
        Postfix postfix = method.getAnnotation(Postfix.class);
        if (postfix != null) {
            patchEntry.actions.add(new Action("POSTFIX", method.getSimpleName().toString(), methodDescriptor(method), postfix.targetMethod(), postfix.targetDesc(), null, null, null, -1, 0));
        }
        Replace replace = method.getAnnotation(Replace.class);
        if (replace != null) {
            patchEntry.actions.add(new Action("REPLACE", method.getSimpleName().toString(), methodDescriptor(method), replace.targetMethod(), replace.targetDesc(), null, null, null, -1, 0));
        }
        RedirectCall redirect = method.getAnnotation(RedirectCall.class);
        if (redirect != null) {
            patchEntry.actions.add(new Action("REDIRECT", method.getSimpleName().toString(), methodDescriptor(method), redirect.targetMethod(), redirect.targetDesc(), redirect.owner(), redirect.name(), redirect.desc(), redirect.ordinal(), redirect.require()));
        }
        WrapCall wrap = method.getAnnotation(WrapCall.class);
        if (wrap != null) {
            patchEntry.actions.add(new Action("WRAP", method.getSimpleName().toString(), methodDescriptor(method), wrap.targetMethod(), wrap.targetDesc(), wrap.owner(), wrap.name(), wrap.desc(), wrap.ordinal(), wrap.require()));
        }
        Accessor accessor = method.getAnnotation(Accessor.class);
        if (accessor != null) {
            patchEntry.actions.add(new Action("ACCESSOR", method.getSimpleName().toString(), methodDescriptor(method), accessor.value(), "", null, null, null, -1, 0));
        }
    }

    private String methodDescriptor(ExecutableElement method) {
        StringBuilder desc = new StringBuilder("(");
        for (var param : method.getParameters()) {
            desc.append(typeToDescriptor(param.asType()));
        }
        desc.append(')').append(typeToDescriptor(method.getReturnType()));
        return desc.toString();
    }

    private String typeToDescriptor(javax.lang.model.type.TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case CHAR -> "C";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            case ARRAY -> "[" + typeToDescriptor(((javax.lang.model.type.ArrayType) type).getComponentType());
            case DECLARED -> "L" + internalName((DeclaredType) type) + ";";
            default -> "Ljava/lang/Object;";
        };
    }

    private String internalName(DeclaredType type) {
        return ((TypeElement) type.asElement()).getQualifiedName().toString().replace('.', '/');
    }

    private void writeIndex() {
        try {
            Index index = new Index("1", Instant.now().toString(), new ArrayList<>(entries.values()));
            index.patches.sort(Comparator.comparing(p -> p.patchClass));
            for (PatchEntry patch : index.patches) {
                patch.actions.sort(Comparator
                        .comparing((Action a) -> a.kind)
                        .thenComparing(a -> a.targetMethod)
                        .thenComparing(a -> a.targetDesc)
                        .thenComparing(a -> a.methodName));
            }

            FileObject file = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    "mixintale.index.json"
            );
            try (Writer writer = file.openWriter()) {
                GSON.toJson(index, writer);
            }
            written = true;
        } catch (FilerException ignored) {
            written = true;
        } catch (IOException ioException) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Failed to write mixintale.index.json: " + ioException.getMessage());
        }
    }

    private record Index(String version, String generatedAt, List<PatchEntry> patches) {}

    private static final class PatchEntry {
        private final String patchClass;
        private final String targetClass;
        private final int priority;
        private final List<Action> actions = new ArrayList<>();

        private PatchEntry(String patchClass, String targetClass, int priority) {
            this.patchClass = patchClass;
            this.targetClass = targetClass;
            this.priority = priority;
        }
    }

    private record Action(
            String kind,
            String methodName,
            String methodDesc,
            String targetMethod,
            String targetDesc,
            String owner,
            String name,
            String desc,
            int ordinal,
            int require
    ) {}
}
