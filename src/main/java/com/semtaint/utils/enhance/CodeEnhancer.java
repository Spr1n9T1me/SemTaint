/*
 * Tai-e: A Static Analysis Framework for Java
 * Code Enhancement Framework - Similar to Soot's capabilities
 */

package com.semtaint.utils.enhance;

import pascal.taie.World;
import pascal.taie.frontend.newfrontend.hierarchy.DefaultClassLoader;
import pascal.taie.ir.DefaultIR;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.ExceptionEntry;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.*;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.Sets;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Code Enhancement Framework for Tai-e
 * Provides Soot-like capabilities for dynamic class, method, and field creation/modification
 */
public class CodeEnhancer {

    private final Map<String, EnhancedClass> enhancedClasses = new ConcurrentHashMap<>();
    private final Map<JMethod, List<Stmt>> methodEnhancements = new ConcurrentHashMap<>();

    /**
     * Create a new class dynamically (similar to Soot's SootClass creation)
     */
    public EnhancedClass createClass(String className, JClass superClass) {
        EnhancedClass enhancedClass = new EnhancedClass(className, superClass);
        enhancedClasses.put(className, enhancedClass);
        return enhancedClass;
    }

    /**
     * Add a new method to an existing class
     */
    public JMethod addMethod(JClass targetClass, String methodName,
                           List<Type> paramTypes, Type returnType) {
        // Subsignature subsig = Subsignature.get(methodName, paramTypes, returnType);
        // Create method with all required parameters
        JMethod newMethod = new JMethod(
            targetClass,
            methodName,
            Set.of(Modifier.PUBLIC), // modifiers
            paramTypes,
            returnType,
            List.of(), // exceptions (List<ClassType>)
            null, // MethodGSignature
            null, // removed AnnotationHolder.emptyHolder()
            List.of(), // paramAnnotations
            List.of(), // paramNames
            null // nativeMethod
        );

        // Add to class's declared methods (need to extend JClass for this)
        // This would require modifying JClass or using reflection

        return newMethod;
    }

    /**
     * Add a new method to an EnhancedClass (for dynamic class creation)
     */
    public JMethod addMethod(EnhancedClass targetClass, String methodName,
                           List<Type> paramTypes, Type returnType) {
        JMethod newMethod = new JMethod(
            null, // declaringClass will be set during build
            methodName,
            Set.of(Modifier.PUBLIC),
            paramTypes,
            returnType,
            List.of(),
            null,
            null,
            List.of(),
            List.of(),
            null
        );
        targetClass.addMethod(newMethod);
        return newMethod;
    }

    /**
     * Add phantom field to any class (not just phantom classes)
     */
    public JField addField(JClass targetClass, String fieldName, Type fieldType) {
        JField newField = new JField(
            targetClass,
            fieldName,
            Set.of(Modifier.PRIVATE),
            fieldType,
            null, // no initial value
            null, // removed AnnotationHolder.emptyHolder()
                 null
        );

        // Use existing phantom field mechanism
        targetClass.addPhantomField(fieldName, fieldType, newField);
        return newField;
    }

    /**
     * Create method IR dynamically
     */
    public IR createMethodIR(JMethod method, List<Stmt> statements) {
        Var thisVar = method.isStatic() ? null : new Var(method, "this", method.getDeclaringClass().getType(), 0);
        List<Var> params = new ArrayList<>();

        // Create parameter variables
        for (int i = 0; i < method.getParamCount(); i++) {
            params.add(new Var(method, "param" + i, method.getParamType(i), i + (method.isStatic() ? 0 : 1)));
        }

        Set<Var> returnVars = Sets.newLinkedSet();
        List<Var> locals = new ArrayList<>(); // 新增locals参数
        List<ExceptionEntry> exceptionEntries = List.of();

        return new DefaultIR(method, thisVar, params, returnVars, locals, statements, exceptionEntries);
    }

    /**
     * Inject statements into existing method
     */
    public void injectStatements(JMethod method, List<Stmt> statements) {
        methodEnhancements.computeIfAbsent(method, k -> new ArrayList<>()).addAll(statements);
    }

    /**
     * Enhanced class builder for dynamic class creation
     */
    public static class EnhancedClass {
        private final String name;
        private final JClass superClass;
        private final List<JMethod> methods = new ArrayList<>();
        private final List<JField> fields = new ArrayList<>();
        private final Set<JClass> interfaces = new HashSet<>();

        public EnhancedClass(String name, JClass superClass) {
            this.name = name;
            this.superClass = superClass;
        }

        public void addMethod(JMethod method) {
            methods.add(method);
        }

        public void addField(JField field) {
            fields.add(field);
        }

        public void addInterface(JClass iface) {
            interfaces.add(iface);
        }

        /**
         * Build the actual JClass
         */
        public JClass build() {
            // Create a custom class builder
            EnhancedClassBuilder builder = new EnhancedClassBuilder(this);
            DefaultClassLoader loader = (DefaultClassLoader) World.get().getClassHierarchy().getDefaultClassLoader();
            // Create JClass
            JClass jclass = new JClass(loader,
                name,
                "sem.virtual" // module name
            );

            // Build the class
            jclass.build(builder);
            // build后再生成IR（如果需要）
            // 注意：不要在 build 里直接 setIR，setIR 必须在 build 之后、调用方手动完成！

            // Add to hierarchy
            loader.addMockedClass(jclass);
            // 修正每个方法的 declaringClass
            for (JMethod m : methods) {
                if (m.getDeclaringClass() == null) {
                    m.setDeclaringClass(jclass);
                }
            }
            return jclass;
        }
    }

    /**
     * Custom class builder for enhanced classes
     */
    private static class EnhancedClassBuilder implements JClassBuilder {
        private final EnhancedClass enhancedClass;

        public EnhancedClassBuilder(EnhancedClass enhancedClass) {
            this.enhancedClass = enhancedClass;
        }

        @Override
        public void build(JClass jclass) {
            jclass.build(this);
        }

        @Override
        public Set<Modifier> getModifiers() {
            return Set.of(Modifier.PUBLIC);
        }

        @Override
        public String getSimpleName() {
            return enhancedClass.name.substring(enhancedClass.name.lastIndexOf('.') + 1);
        }

        @Override
        public ClassType getClassType() {
            return World.get().getTypeSystem().getClassType(enhancedClass.name);
        }

        @Override
        public JClass getSuperClass() {
            return enhancedClass.superClass;
        }

        @Override
        public Collection<JClass> getInterfaces() {
            return enhancedClass.interfaces;
        }

        @Override
        public JClass getOuterClass() {
            return null;
        }

        @Override
        public Collection<JField> getDeclaredFields() {
            return enhancedClass.fields;
        }

        @Override
        public Collection<JMethod> getDeclaredMethods() {
            return enhancedClass.methods;
        }

        @Override
        public boolean isApplication() {
            return true;
        }

        @Override
        public boolean isPhantom() {
            return false;
        }

        @Override
        public pascal.taie.language.annotation.AnnotationHolder getAnnotationHolder() {
            return null;
        }

        @Override
        public pascal.taie.language.generics.ClassGSignature getGSignature() {
            return null;
        }
    }

}
