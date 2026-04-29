package com.semtaint.container.ap;

public class WildCardPattern extends AccessPattern {

    private static final WildCardPattern INSTANCE = new WildCardPattern();

    private WildCardPattern() {
        super(String.valueOf('*'));
    }

    public static WildCardPattern getInstance() {
        return INSTANCE;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WildCardPattern;
    }

    @Override
    public int hashCode() {
        return 1999;
    }
    @Override
    public String toString() {
        return "WILDCARD";
    }
}
