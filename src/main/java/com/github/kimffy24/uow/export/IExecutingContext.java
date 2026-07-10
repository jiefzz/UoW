package com.github.kimffy24.uow.export;

import java.util.List;
import java.util.Map;

import com.github.kimffy24.uow.export.skeleton.AbstractAggregateRoot;

public interface IExecutingContext {
	
	/**
	 * 通过给定Key值，找到目标对象
	 * @param prototype
	 * @param id
	 * @param <T>
	 * @return
	 */
	public <T extends AbstractAggregateRoot<?>> T fetch(Class<T> prototype, Object id);

	/**
	 * 把当前的UoW业务对象加入到提交上下文中，commit执行时会提交到数据库的了。<br />
	 * 
	 * @param <T>
	 * @param aggr
	 * @return &lt;T extends AbstractAggregateRoot&gt; 会把当前add提交的对象直接返回；方便编码
	 */
	public <T extends AbstractAggregateRoot<?>> T add(T aggr);
	
	/**
	 * 把当前执行上下文中跟踪的对象，提交到数据库中；
	 */
	public void commit();
	
}
