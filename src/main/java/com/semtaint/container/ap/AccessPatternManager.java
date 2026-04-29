package com.semtaint.container.ap;

import pascal.taie.ir.exp.*;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @program: semtaint-newfront
 * @description: AccessPattern 的统一索引管理器
 * @author: springtime
 **/
public class AccessPatternManager {

    // 使用不同的 Map 缓存不同类型的 AccessPattern
    private final Map<LiteralKey, LiteralPattern> literalPatterns = new ConcurrentHashMap<>();
    private final Map<ReferenceKey, FixedPattern> fixedPatterns = new ConcurrentHashMap<>();
    private final Map<Var, VariablePattern> variablePatterns = new ConcurrentHashMap<>();

    /**
     * 获取或创建 LiteralPattern（基于 IntLiteral）
     */
    public LiteralPattern getLiteralPattern(IntLiteral literal) {
        LiteralKey key = new LiteralKey(literal.getValue(), LiteralType.INT);
        return literalPatterns.computeIfAbsent(key, k -> new LiteralPattern(literal));
    }

    /**
     * 获取或创建 LiteralPattern（基于 StringLiteral）
     */
    public LiteralPattern getLiteralPattern(StringLiteral literal) {
        LiteralKey key = new LiteralKey(literal.getString(), LiteralType.STRING);
        return literalPatterns.computeIfAbsent(key, k -> new LiteralPattern(literal));
    }

    /**
     * 获取或创建 LiteralPattern（通用方法）
     */
    public LiteralPattern getLiteralPattern(Literal literal) {
        if (literal instanceof IntLiteral intLit) {
            return getLiteralPattern(intLit);
        } else if (literal instanceof StringLiteral strLit) {
            return getLiteralPattern(strLit);
        }else if (literal instanceof ClassLiteral classLiteral){
            // 对于 ClassLiteral，我们可以使用它的类名作为唯一标识
            LiteralKey key = new LiteralKey(classLiteral.getTypeValue().getName(), LiteralType.STRING);
            return literalPatterns.computeIfAbsent(key, k -> new LiteralPattern(classLiteral));
        }else if (literal instanceof NullLiteral nullLiteral){
            // 对于 NullLiteral，我们可以使用一个固定的键来表示所有的 null 字面量
            LiteralKey key = new LiteralKey("NULL", LiteralType.STRING);
            return literalPatterns.computeIfAbsent(key, k -> new LiteralPattern(nullLiteral));
        }
        throw new IllegalArgumentException("Unsupported literal type: " + literal.getClass());
    }

    /**
     * 获取或创建 FixedPattern（基于 ReferenceLocation）
     */
    public FixedPattern getFixedPattern(ReferenceLocation refLoc) {
        ReferenceKey key = new ReferenceKey(refLoc.getAnchor(), refLoc.getOffset());
        return fixedPatterns.computeIfAbsent(key, k -> new FixedPattern(refLoc));
    }

    /**
     * 获取或创建 FixedPattern（基于 Anchor 和 offset）
     */
    public FixedPattern getFixedPattern(ReferenceLocation.Anchor anchor, int offset) {
        ReferenceKey key = new ReferenceKey(anchor, offset);
        return fixedPatterns.computeIfAbsent(key, k ->
            new FixedPattern(new ReferenceLocation(anchor, offset)));
    }

    /**
     * 获取或创建 VariablePattern
     */
    public VariablePattern getVariablePattern(Var var) {
        return variablePatterns.computeIfAbsent(var, VariablePattern::new);
    }

    /**
     * 清空所有缓存（用于测试或重置）
     */
    public void clear() {
        literalPatterns.clear();
        fixedPatterns.clear();
        variablePatterns.clear();
    }

    /**
     * 获取缓存统计信息
     */
    public String getStats() {
        return String.format("AccessPatternManager Stats: Literals=%d, Fixed=%d, Variables=%d",
                literalPatterns.size(), fixedPatterns.size(), variablePatterns.size());
    }

    // =========================================================================
    // 内部 Key 类型定义
    // =========================================================================

    /**
     * LiteralPattern 的缓存键
     */
    private static class LiteralKey {
        private final Object value;  // Integer 或 String
        private final LiteralType type;

        LiteralKey(Object value, LiteralType type) {
            this.value = value;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LiteralKey key)) return false;
            return Objects.equals(value, key.value) && type == key.type;
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, type);
        }
    }

    private enum LiteralType {
        INT, STRING
    }

    /**
     * FixedPattern 的缓存键（基于 ReferenceLocation）
     */
    private static class ReferenceKey {
        private final ReferenceLocation.Anchor anchor;
        private final int offset;

        ReferenceKey(ReferenceLocation.Anchor anchor, int offset) {
            this.anchor = anchor;
            this.offset = offset;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ReferenceKey key)) return false;
            return offset == key.offset && anchor == key.anchor;
        }

        @Override
        public int hashCode() {
            return Objects.hash(anchor, offset);
        }
    }
}
