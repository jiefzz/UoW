package com.github.kimffy24.uow.export.skeleton;

/**
 * 直接让业务类扩展此类，并实现对应方法即可
 */
public interface AggregateRootLifeCycleAware {

	/**
	 * 上下里获取业务对象的方法返回前，会调用此方法<br />
	 * 如果是用户new的业务对象，并自己add入UoW上下的，则不会调用此方法，可以根据需要主动调用
	 */
	public void postCreation();
	
	/**
	 * 在进入UoWCommit过程发生后会执行一次此方法，且一定会发生在diff过程之前
	 */
	public void preCommit();

	public String getVersionFieldName();

	public void versionIncrement();

	public Object currentVersionValue();

	public String currentVersionStrValue();
}
