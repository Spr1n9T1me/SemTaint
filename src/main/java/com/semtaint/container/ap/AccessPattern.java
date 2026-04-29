package com.semtaint.container.ap;

import java.util.Objects;

public abstract class AccessPattern {
    /*
    type:
    1. str/int liertal
    2. reference location
    3. variable pointer
    4. wildcard
     */
    public Object pattern;

    protected AccessPattern(Object pattern) {
        this.pattern = pattern;
    }

    public Object getPattern() {
        return this.pattern;
    }

    /**
     * 对应论文中的 shift 操作。
     * 当容器发生插入操作时，计算当前访问模式的新位置。
     * 默认实现：只有 FixedPattern (ReferenceLocation) 支持 shift，其他类型返回自身。
     *
     * @param insertionAp 插入操作产生的访问模式 (ap_ins)
     * @return 平移后的新 AccessPattern，如果不需要平移则返回 this
     */
    public AccessPattern shift(AccessPattern insertionAp) {
        // 默认实现：非固定模式不受影响
        return this;
    }

    /**
     * 对应论文中的 unshift 操作。
     * 当容器发生删除操作时，计算当前访问模式的新位置。
     * 默认实现：只有 FixedPattern (ReferenceLocation) 支持 unshift，其他类型返回自身。
     *
     * @param deletionAp 删除操作产生的访问模式 (ap_del)
     * @return 平移后的新 AccessPattern
     */
    public AccessPattern unshift(AccessPattern deletionAp) {
        // 默认实现：非固定模式不受影响
        return this;
    }

        @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AccessPattern that = (AccessPattern) o;
        return Objects.equals(pattern, that.pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), pattern);
    }


}
