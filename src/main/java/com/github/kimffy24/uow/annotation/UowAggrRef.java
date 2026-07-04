package com.github.kimffy24.uow.annotation;

import com.github.kimffy24.uow.export.mapper.ILocatorMapper;
import com.github.kimffy24.uow.export.skeleton.AggregateRootLifeCycleAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.annotation.*;

/**
 * @deprecated 改为由uow-codegen承担这个职责
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
