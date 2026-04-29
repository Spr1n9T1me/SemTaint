package com.semtaint.frame.lifecycle;

import pascal.taie.analysis.pta.core.cs.element.AbstractPointer;
import pascal.taie.language.type.Type;

import java.util.Objects;

/**
 * Bean 在 PFG 中的中间节点。
 * 所有注入同一个 Bean 的变量/字段共享此指针。
 */
public class BeanPointer extends AbstractPointer {

    private final Object key;
    private final Type type;

    public BeanPointer(Object key, Type type) {
        super(0);
        this.key = key;
        this.type = type;
    }

    public Object getKey() {
        return key;
    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BeanPointer that = (BeanPointer) o;
        return Objects.equals(key, that.key) && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, type);
    }

    @Override
    public String toString() {
        return "BeanPointer{" + key + ", " + type + '}';
    }
}
