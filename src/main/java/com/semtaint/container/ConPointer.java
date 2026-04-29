package com.semtaint.container;

import java.util.Objects;

import com.semtaint.container.ap.AccessPattern;

import pascal.taie.analysis.pta.core.cs.element.AbstractPointer;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.language.type.Type;

public class ConPointer extends AbstractPointer {
    private final CSObj containerObj;
    private final AccessPattern accessPattern;

    /**
     * Creates a container pointer with a concrete index.
     * The index must be assigned by {@link com.semtaint.container.mod.IContainerCSManager}
     * to ensure the pointer is indexable by Tai-e collections.
     */
    public ConPointer(CSObj containerObj, AccessPattern accessPattern, int index) {
        super(index);
        this.containerObj = containerObj;
        this.accessPattern = accessPattern;
    }

    @Override
    public Type getType() {
        return containerObj.getObject().getType();
    }

    public CSObj getContainerObj() {
        return containerObj;
    }

    public AccessPattern getAccessPattern() {
        return accessPattern;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConPointer that = (ConPointer) o;
        return Objects.equals(containerObj, that.containerObj) &&
                Objects.equals(accessPattern, that.accessPattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(containerObj, accessPattern);
    }

    @Override
    public String toString() {
        return "<" + containerObj + ", " + accessPattern + ">";
    }
}
