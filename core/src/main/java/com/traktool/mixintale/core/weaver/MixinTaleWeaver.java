package com.traktool.mixintale.core.weaver;

import com.traktool.mixintale.core.asm.ClassInfoResolver;
import com.traktool.mixintale.core.asm.SafeClassWriter;
import com.traktool.mixintale.core.index.MixinTaleIndex;
import com.traktool.mixintale.core.reporting.MixinTaleApplyReport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public final class MixinTaleWeaver {
    public byte[] weave(byte[] originalClass, String className, List<MixinTaleIndex.PatchDescriptor> candidates,
                        ClassInfoResolver resolver, MixinTaleApplyReport report, boolean failHard) {
        if (candidates.isEmpty()) return originalClass;

        ClassReader reader = new ClassReader(originalClass);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        for (MixinTaleIndex.PatchDescriptor patch : candidates) {
            Map<String, Object> patchRow = new LinkedHashMap<>();
            patchRow.put("patch", patch.patchClass());
            patchRow.put("target", className);
            patchRow.put("sourceJar", patch.sourceJar());
            try {
                applyPatchDescriptor(node, patch, report, failHard);
                patchRow.put("status", "applied");
            } catch (RuntimeException exception) {
                patchRow.put("status", "failed");
                patchRow.put("error", exception.getMessage());
                report.addError("weave", exception);
                if (failHard) throw exception;
            }
            report.patches.add(patchRow);
        }

        SafeClassWriter writer = new SafeClassWriter(reader, org.objectweb.asm.ClassWriter.COMPUTE_FRAMES | org.objectweb.asm.ClassWriter.COMPUTE_MAXS, resolver);
        node.accept(writer);
        return writer.toByteArray();
    }

    private void applyPatchDescriptor(ClassNode classNode, MixinTaleIndex.PatchDescriptor patch,
                                      MixinTaleApplyReport report, boolean failHard) {
        Map<String, List<MixinTaleIndex.ActionDescriptor>> grouped = new TreeMap<>();
        for (MixinTaleIndex.ActionDescriptor action : patch.actions()) {
            grouped.computeIfAbsent(action.targetMethod() + action.targetDesc(), k -> new ArrayList<>()).add(action);
        }

        for (MethodNode method : classNode.methods) {
            List<MixinTaleIndex.ActionDescriptor> actions = grouped.get(method.name + method.desc);
            if (actions == null || actions.isEmpty()) continue;
            actions.sort(Comparator.comparing(MixinTaleIndex.ActionDescriptor::kind).thenComparing(MixinTaleIndex.ActionDescriptor::methodName));

            for (MixinTaleIndex.ActionDescriptor action : actions) {
                if ("REDIRECT".equals(action.kind()) || "WRAP".equals(action.kind())) {
                    int replaced = applyCallsiteTransform(method, patch, action, failHard);
                    Map<String, Object> callsite = new LinkedHashMap<>();
                    callsite.put("patch", patch.patchClass());
                    callsite.put("kind", action.kind());
                    callsite.put("target", method.name + method.desc);
                    callsite.put("replaced", replaced);
                    callsite.put("require", action.require());
                    callsite.put("ordinal", action.ordinal());
                    report.callsites.add(callsite);
                    if (replaced < action.require()) {
                        String message = "Callsite require failed: required=" + action.require() + " replaced=" + replaced;
                        if (failHard) throw new IllegalStateException(message);
                    }
                }
                if ("REPLACE".equals(action.kind())) {
                    method.instructions.clear();
                    method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                    method.instructions.add(new InsnNode(Opcodes.ARETURN));
                }
            }
        }
    }

    private String expectedHandlerDescriptor(MethodInsnNode call) {
        Type originalType = Type.getMethodType(call.desc);
        if (call.getOpcode() == Opcodes.INVOKESTATIC) {
            return call.desc;
        }

        Type[] originalArgs = originalType.getArgumentTypes();
        Type[] redirectedArgs = new Type[originalArgs.length + 1];
        redirectedArgs[0] = Type.getObjectType(call.owner);
        System.arraycopy(originalArgs, 0, redirectedArgs, 1, originalArgs.length);
        return Type.getMethodDescriptor(originalType.getReturnType(), redirectedArgs);
    }

    private int applyCallsiteTransform(MethodNode method, MixinTaleIndex.PatchDescriptor patch,
                                     MixinTaleIndex.ActionDescriptor action, boolean failHard) {
        int ordinal = 0;
        int replaced = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (!call.owner.equals(action.owner()) || !call.name.equals(action.name()) || !call.desc.equals(action.desc())) continue;
            boolean selected = action.ordinal() < 0 || action.ordinal() == ordinal;
            if (selected) {
                String expectedHandlerDesc = expectedHandlerDescriptor(call);
                if (!expectedHandlerDesc.equals(action.methodDesc())) {
                    if (failHard) {
                        throw new IllegalStateException("Redirect signature mismatch for " + patch.patchClass() + "#" + action.methodName()
                                + ": expected=" + expectedHandlerDesc + " actual=" + action.methodDesc());
                    }
                    ordinal++;
                    continue;
                }
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.itf = false;
                call.owner = patch.patchClass().replace('.', '/');
                call.name = action.methodName();
                call.desc = action.methodDesc();
                replaced++;
                if (action.ordinal() >= 0) break;
            }
            ordinal++;
        }
        return replaced;
    }
}
