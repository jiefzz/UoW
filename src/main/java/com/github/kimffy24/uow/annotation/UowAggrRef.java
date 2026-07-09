package com.github.kimffy24.uow.annotation;

import com.github.kimffy24.uow.export.mapper.ILocatorMapper;
import com.github.kimffy24.uow.export.skeleton.AggregateRootLifeCycleAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.annotation.*;

/**
 * 这个注解是为 {@link com.github.kimffy24.uow.export.mapper.ILocatorMapper} 的生成的映射类使用的，目的是为上下文中存入
 * Mapper类与聚合的关系
 * @author kimffy
 *
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UowAggrRef {

    Class<? extends AggregateRootLifeCycleAware> value();
	
}
