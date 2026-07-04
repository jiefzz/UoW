package com.github.kimffy24.uow.export.skeleton;

public class SimpleAggregateRoot<TID> extends AbstractAggregateRoot<TID> {

	public SimpleAggregateRoot() {
		super();
	}

	public SimpleAggregateRoot(TID id) {
		super(id);
	}

	private TID id;
	
	@Override
	protected final void setId(TID id) {
		this.id = id;
	}

	@Override
	public final TID getId() {
		return id;
	}

	@Override
	public final String getIdFieldName() {
		return "id";
	}

	@Override
	public String getVersionFieldName() {
		return "version";
	}
}
