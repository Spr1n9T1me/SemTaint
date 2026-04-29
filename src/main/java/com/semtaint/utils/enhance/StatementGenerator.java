/*
 * Tai-e Code Enhancement - Advanced Statement Generator
 * Provides utilities for dynamic IR generation similar to Soot's Jimple generation
 */

package com.semtaint.utils.enhance;

import pascal.taie.ir.exp.*;
import pascal.taie.ir.proginfo.FieldRef;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Advanced statement generator for creating Tai-e IR statements dynamically
 * Similar to Soot's Jimple statement creation capabilities
 */
public class StatementGenerator {

    private final JMethod container;
    private int varCounter = 0;

    public StatementGenerator(JMethod container) {
        this.container = container;
    }

    /**
     * Create a new local variable
     */
    public Var newLocal(String name, Type type) {
        return new Var(container, name + "$" + varCounter++, type, -1);
    }

    /**
     * Generate: lvalue = new ClassName();
     */
    public New generateNew(Var lvalue, ClassType type) {
        NewExp newExp = new NewInstance(type);
        return new New(container, lvalue, newExp);
    }

    /**
     * Generate: lvalue = rvalue;
     */
    public Copy generateCopy(Var lvalue, Var rvalue) {
        return new Copy(lvalue, rvalue);
    }

    /**
     * Generate: lvalue = obj.field;
     */
    public LoadField generateLoadField(Var lvalue, Var obj, JField field) {
        FieldRef fieldRef = field.getRef();
        InstanceFieldAccess fieldAccess = new InstanceFieldAccess(fieldRef, obj);
        return new LoadField(lvalue, fieldAccess);
    }

    /**
     * Generate: obj.field = rvalue;
     */
    public StoreField generateStoreField(Var obj, JField field, Var rvalue) {
        FieldRef fieldRef = field.getRef();
        InstanceFieldAccess fieldAccess = new InstanceFieldAccess(fieldRef, obj);
        return new StoreField( fieldAccess, rvalue);
    }

    /**
     * Generate: lvalue = obj.method(args);
     */
    public Invoke generateInvokeVirtual(Var lvalue, Var obj, JMethod method, List<Var> args) {
        MethodRef methodRef = method.getRef();
        InvokeVirtual invokeExp = new InvokeVirtual(methodRef, obj, args);
        return new Invoke(container, invokeExp, lvalue);
    }

    /**
     * Generate: lvalue = ClassName.method(args);
     */
    public Invoke generateInvokeStatic(Var lvalue, JMethod method, List<Var> args) {
        MethodRef methodRef = method.getRef();
        InvokeStatic invokeExp = new InvokeStatic(methodRef, args);
        return new Invoke(container, invokeExp, lvalue );
    }

    /**
     * Generate: lvalue = (TargetType) rvalue;
     */
    public Cast generateCast(Var lvalue, Var rvalue, Type targetType) {
        CastExp castExp = new CastExp(rvalue, targetType);
        return new Cast(lvalue, castExp);
    }

    /**
     * Generate: if (condition) goto target;
     */
    public If generateIf(ConditionExp condition) {
        return new If(condition);
    }

    /**
     * Generate: goto target;
     */
//    public Goto generateGoto(Stmt target) {
//        return new Goto(container, target);
//    }

    /**
     * Generate: return rvalue;
     */
    public Return generateReturn(Var rvalue) {
        return new Return(rvalue);
    }

    /**
     * Generate: return; (void return)
     */
    public Return generateVoidReturn() {
        return new Return();
    }

    /**
     * Generate: throw exception;
     */
    public Throw generateThrow(Var exception) {
        return new Throw(exception);
    }

    /**
     * Generate binary operation: lvalue = left op right;
     */
//    public Binary generateBinaryOp(Var lvalue, BinaryExp.Op operator, Var left, Var right) {
//        BinaryExp binaryExp = new BinaryExp(operator, left, right);
//        return new Binary(container, lvalue, binaryExp);
//    }

    /**
     * Generate unary operation: lvalue = op operand;
     */
//    public Unary generateUnaryOp(Var lvalue, UnaryExp.Op operator, Var operand) {
//        UnaryExp unaryExp = new UnaryExp(operator, operand);
//        return new Unary(container, lvalue, unaryExp);
//    }

    /**
     * Generate: lvalue = array[index];
     */
    public LoadArray generateLoadArray(Var lvalue, Var array, Var index) {
        ArrayAccess arrayAccess = new ArrayAccess(array, index);
        return new LoadArray(lvalue, arrayAccess);
    }

    /**
     * Generate: array[index] = rvalue;
     */
    public StoreArray generateStoreArray(Var array, Var index, Var rvalue) {
        ArrayAccess arrayAccess = new ArrayAccess(array, index);
        return new StoreArray(arrayAccess, rvalue);
    }

    /**
     * Generate constant assignment: lvalue = constant;
     */
    public AssignLiteral generateAssignLiteral(Var lvalue, Literal literal) {
        return new AssignLiteral(lvalue, literal);
    }

    /**
     * Helper: Create integer literal
     */
    public IntLiteral intLiteral(int value) {
        return IntLiteral.get(value);
    }

    /**
     * Helper: Create string literal
     */
    public StringLiteral stringLiteral(String value) {
        return StringLiteral.get(value);
    }

    /**
     * Helper: Create null literal
     */
    public NullLiteral nullLiteral() {
        return NullLiteral.get();
    }

    /**
     * Create a complete method implementation
     */
    public List<Stmt> createMethodImplementation(MethodTemplate template) {
        List<Stmt> statements = new ArrayList<>();

        switch (template) {
            case GETTER:
                return createGetterImplementation();
            case SETTER:
                return createSetterImplementation();
            case SIMPLE_CONSTRUCTOR:
                return createSimpleConstructorImplementation();
            case DELEGATION_METHOD:
                return createDelegationImplementation();
            default:
                return statements;
        }
    }

    private List<Stmt> createGetterImplementation() {
        // Assumes field named 'field' exists
        List<Stmt> statements = new ArrayList<>();
        // this.field should be loaded and returned
        // Implementation would depend on specific field information
        return statements;
    }

    private List<Stmt> createSetterImplementation() {
        // Setter implementation
        return new ArrayList<>();
    }

    private List<Stmt> createSimpleConstructorImplementation() {
        // Simple constructor calling super()
        return new ArrayList<>();
    }

    private List<Stmt> createDelegationImplementation() {
        // Method that delegates to another method
        return new ArrayList<>();
    }

    public enum MethodTemplate {
        GETTER,
        SETTER,
        SIMPLE_CONSTRUCTOR,
        DELEGATION_METHOD
    }
}
