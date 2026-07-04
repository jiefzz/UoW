package com.github.kimffy24.uow.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Order(Ordered.HIGHEST_PRECEDENCE)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MappingTableAttribute {

	/**
	 * 映射的表名
	 * @return
	 */
	public String tableName() default "";

	/**
	 * 表的介绍，用于生成uow-gen-all.sql
	 * @return
	 */
	public String comment() default "";

	/**
	 * 在uow-gen-all.sql中的建表语句后追加的语句
	 * @return
	 */
	public String[] alterAppends() default {};
}
