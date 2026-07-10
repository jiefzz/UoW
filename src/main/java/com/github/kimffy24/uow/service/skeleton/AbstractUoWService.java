package com.github.kimffy24.uow.service.skeleton;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import com.github.kimffy24.uow.core.ExecutingContextFactory;
import com.github.kimffy24.uow.export.skeleton.AbstractAggregateRoot;
import com.github.kimffy24.uow.service.CommittingService;
import com.github.kimffy24.uow.service.CommittingService.IClosure;

import pro.jk.ejoker.common.system.enhance.StringUtilx;
import pro.jk.ejoker.common.system.functional.IVoidFunction;

/**
 * 这是一个简单的UoW服务类骨架；<br />
 * 让你的服务类继承这个基类，即会注入UoW相应的服务对象；<br />
 * 但是需要注意，这并不能完全让你的业务类工作做UoW上下文中；<br />
 * 必须通过本类的保护方法doUnderContext来发起，把你的服务方法包装为函子传入即可<br /><br />
 *
 * 另外提供4组保护方法用于简化访问
 */
public abstract class AbstractUoWService {

	@Autowired
	protected ExecutingContextFactory executingContextFactory;
	
	@Autowired
	protected CommittingService autoCommittingService;

	/**
	 * 按照主键id在持久层查找出对象
	 * @param <T>
	 * @param id
	 * @param prototype
	 * @return
	 */
	protected final <T extends AbstractAggregateRoot<?>> T fetchFromContext(Object id, Class<T> prototype) {
		return executingContextFactory.getExecutingContext().fetch(prototype, id);
	}

	/**
	 * 新增对象到持久层，每次新增仅仅能添加1个对象，且不能影响其它对象
	 * @param aggr
	 */
	protected final void addToContext(AbstractAggregateRoot<?> aggr) {
		executingContextFactory.getExecutingContext().add(aggr);
	}
	
//	/**
//	 * 当不使用AutoCommit注解的时候，需要主动提交到数据库，则可以通过这个方法提交
//	 */
//	protected final void commitAndFlush() {
//		try {
//			getCurrentContext().commit();
//		} finally {
//			executingContextFactory.cleanContext();
//		}
//	}
	
	/**
	 * 当不使用AutoCommit注解的时候，需要在UoW上下文中执行自己的业务方法，用户只需传入业务函子即可。
	 * @param uf 用户执行执行的闭包函数
	 * @throws Throwable
	 */
	protected final void doUnderContext(IVoidFunction uf) throws Throwable {
		autoCommittingService.surroundExec(new IClosure() {
			@Override
			public Object exec() throws Throwable {
				uf.trigger();
				return null;
			}
		});
	}
}
