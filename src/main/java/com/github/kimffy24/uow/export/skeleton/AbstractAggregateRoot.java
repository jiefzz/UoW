package com.github.kimffy24.uow.export.skeleton;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.github.kimffy24.uow.core.AggregateActionBinder;

import pro.jk.ejoker.common.context.annotation.persistent.PersistentIgnore;
import pro.jk.ejoker.common.system.enhance.StringUtilx;
import pro.jk.ejoker.common.system.functional.IFunction1;
import pro.jk.ejoker.common.utils.genericity.GenericExpression;
import pro.jk.ejoker.common.utils.genericity.GenericExpressionFactory;

public abstract class AbstractAggregateRoot<TID> implements AggregateRootLifeCycleAware {
	
	private final static Set<String> __ignore_fields__ = new HashSet<>(
			Arrays.asList(
					"_dirty_",
					"_originalStatus_",
					"_idType_",
					"VERSION_FIELD_CACHE",
					"__ignore_fields__",
					"arParseStore"
			));

	/**
	 * 版本字段反射对象缓存，按类+字段名保持，避免每次反射查找
	 */
	private static final Map<String, Field> VERSION_FIELD_CACHE = new ConcurrentHashMap<>();

	@Override
	public void postCreation() {}

	@Override
	public void preCommit() {}


	/**
	 * 获取版本字段的反射对象（带缓存，保持在类型成员级别）
	 */
	protected Field getVersionField() {
		Class<?> clazz = this.getClass();
		String versionFieldName = this.getVersionFieldName();
		if(StringUtilx.isNullOrEmpty(versionFieldName)) {
			throw new RuntimeException("Version field name is empty in class [" + clazz.getName() + "]");
		}
		String cacheKey = clazz.getName() + "#" + versionFieldName;

		Field cached = VERSION_FIELD_CACHE.get(cacheKey);
		if (cached != null) {
			return cached;
		}

		// 从当前类开始逐级向上查找
		Class<?> searchClazz = clazz;
		while (searchClazz != null) {
			try {
				Field versionField = searchClazz.getDeclaredField(versionFieldName);
				versionField.setAccessible(true);
				VERSION_FIELD_CACHE.put(cacheKey, versionField);
				return versionField;
			} catch (NoSuchFieldException e) {
				searchClazz = searchClazz.getSuperclass();
			}
		}
		throw new RuntimeException("Version field [" + versionFieldName + "] not found in class hierarchy of [" + clazz.getName() + "]");
	}

	@Override
	public Object currentVersionValue() {
		try {
			Field versionField = this.getVersionField();
			Object val = versionField.get(this);
			return val;
		} catch (IllegalAccessException e) {
			throw new RuntimeException("Cannot access version field [" + this.getVersionFieldName() + "] on [" + this.getClass().getName() + "]", e);
		}
	}

	@Override
	public String currentVersionStrValue() {
		try {
			Field versionField = this.getVersionField();
			Object val = versionField.get(this);
			return val == null ? null : String.valueOf(val);
		} catch (IllegalAccessException e) {
			throw new RuntimeException("Cannot access version field [" + this.getVersionFieldName() + "] on [" + this.getClass().getName() + "]", e);
		}
	}
	/**
	 * 版本号自增的默认实现：<br/>
	 * 1. 若版本字段为数值类型（int/Integer, long/Long），则默认加1<br/>
	 * 2. 若版本字段为 java.util.Date 类型，则设置为当前时间<br/>
	 * 3. 其他类型将抛出异常，提示需自行继承并覆写此方法
	 */
	@Override
	public void versionIncrement() {
		Field versionField = this.getVersionField();
		Class<?> fieldType = versionField.getType();

		try {
			if (fieldType == int.class || fieldType == Integer.class
					|| fieldType == long.class || fieldType == Long.class) {
				Object currentVal = versionField.get(this);
				long nextVal = (currentVal == null ? 0L : ((Number) currentVal).longValue()) + 1L;
				if (fieldType == int.class || fieldType == Integer.class) {
					versionField.set(this, (int) nextVal);
				} else {
					versionField.set(this, nextVal);
				}
			} else if (fieldType == Date.class) {
				versionField.set(this, new Date());
			} else {
				throw new UnsupportedOperationException(
					"Field [" + this.getVersionFieldName() + "] of type [" + fieldType.getName()
					+ "] is not supported as a version field. "
					+ "Please override versionIncrement() to implement your own increment logic."
				);
			}
		} catch (IllegalAccessException e) {
			throw new RuntimeException("Cannot access version field [" + this.getVersionFieldName() + "] on [" + this.getClass().getName() + "]", e);
		}
	}
////	@MappingComment("UoW对象版本。会随着业务变更递增")
//	protected int version = 0;

	@PersistentIgnore
	private boolean _dirty_ = false;
	
	@PersistentIgnore
	private Map<String, Object> _originalStatus_ = null;

	@PersistentIgnore
	protected final Class<TID> _idType_;

	@Override
	public String toString() {
		ARParse aarParse;
		return null != (aarParse = arParseStore.get(this.getClass())) ? aarParse._toStringFactor.trigger(this) : super.toString();
	}
	
	protected AbstractAggregateRoot(TID id) {
		this();
		this.setId(id);
	}

	protected AbstractAggregateRoot() {
		ARParse arParse;
		Class<? extends AbstractAggregateRoot<?>> aggrType = (Class<? extends AbstractAggregateRoot<?>> )this.getClass();
		if(null == (arParse = arParseStore.get(aggrType))) {
			GenericExpression genericExpress = GenericExpressionFactory.getGenericExpressDirectly(this.getClass());
			GenericExpression parent = genericExpress;
			while(!AbstractAggregateRoot.class.equals(parent.getDeclarePrototype())) {
				parent = parent.getParent();
			}
//			GenericDefinedType[] deliveryTypeMetasTableCopy = parent.getChild().genericDefination.getDeliveryTypeMetasTableCopy();
//			GenericDefinedType genericT = deliveryTypeMetasTableCopy[0];
//			Class<TID> idClass = (Class<TID> )genericT.rawClazz;
			Class<TID> idClass = null;
			{
				Type tidTypeDef = parent.typeOf("TID");
				if(tidTypeDef instanceof Class) {
					idClass = (Class<TID> )tidTypeDef;
				} else {
					// 暂时不接受泛型嵌套泛型
					throw new RuntimeException(StringUtilx.fmt(
							"Unsupport use a generic type as Aggregate Root id type!!! [target: {}, idType: {}]",
							genericExpress.expressSignature,
							Objects.toString(tidTypeDef)));
				}
			}
			if(!AcceptableIdType.contains(idClass)) {
				throw new RuntimeException(StringUtilx.fmt("Wrong Aggregate Root id type!!! [target: {}, idType: {}, acceptableType: {}]",
						genericExpress.expressSignature,
						idClass.toString(),
						Objects.toString(AcceptableIdType)));
			}

			if(StringUtilx.isNullOrEmpty(this.getVersionFieldName())) {
				throw new RuntimeException(StringUtilx.fmt("Version field name is empty in class [{}]",
						genericExpress.expressSignature));
			}

			ARParse previous = arParseStore.putIfAbsent(aggrType, arParse = new ARParse(idClass, aggr -> {
				String simpleName = aggr.getClass().getSimpleName();
				StringBuilder sb = new StringBuilder();
				sb.append(simpleName);
				sb.append("{");

				StringBuilder sbId = new StringBuilder();
				StringBuilder sbVersion = new StringBuilder();
				StringBuilder sbBizInfo = new StringBuilder();
				StringBuilder sbTypeInfo = new StringBuilder();
				genericExpress.forEachFieldExpressionsDeeply((name, gdf) -> {
					try {
						if("_originalStatus_".equals(name))
							return;
						StringBuilder sb_;
						if(name.equals(aggr.getVersionFieldName())) sb_=sbVersion;
						else if(name.equals(aggr.getIdFieldName())) sb_=sbId;
						else if(__ignore_fields__.contains(name)) sb_=sbTypeInfo;
						else sb_=sbBizInfo;
						Object target = gdf.field.get(aggr);
						if(null != target) {
							sb_.append(name);
							sb_.append(": ");
							sb_.append(target);
							sb_.append(", ");
						}
					} catch (IllegalAccessException e) {
						throw new RuntimeException(StringUtilx.fmt("Field is not accessible!!! [fieldName: {}, type: {}]",
								name,
								simpleName));
					}
				});
				sb.append(sbId);
				sb.append(sbVersion);
				sb.append(sbTypeInfo);
				sb.append(sbBizInfo);
				sb.append("}");
				return sb.toString();
			}));
			
			if(null != previous)
				arParse = previous;
		}

		this._idType_ = (Class<TID> )arParse._idType;
		
	}

	/**
	 * 用户侧自定义编码中的自增主键一定要是包装类啊，且不能设置初始值！<br />
	 * 不然数据库无法生成自增id
	 * 
	 * @param id
	 */
	abstract protected void setId(TID id);
	
	abstract public TID getId();

	/**
	 * 指定idField的名字 <br />
	 * 后期改为注解？ 感觉用注解也没特别大的提升(体验或效率)
	 * @return
	 */
	abstract public String getIdFieldName();
//
//	public int getVersion() {
//		return version;
//	}
	
	private final static Set<Class<?>> AcceptableIdType;

	static {
		AcceptableIdType = new HashSet<>();
		AcceptableIdType.add(Integer.class);
		AcceptableIdType.add(Short.class);
		AcceptableIdType.add(Long.class);
		AcceptableIdType.add(String.class);
		
		AggregateActionBinder.registerOriginalDictAccessor(
				ar -> ar._originalStatus_,
				(ar, d) -> ar._originalStatus_ = d,
				(ar, i) -> {
					Class<?> idType = ((AbstractAggregateRoot )ar)._idType_;
					Object idValue;
					if(idType.equals(i.getClass())) {
						idValue = i;
					} else {
						if(Long.class.equals(idType)) {
							idValue = Long.parseLong(i.toString());
						} else if(Integer.class.equals(idType)) {
							idValue = Integer.parseInt(i.toString());
						} else {
							throw new RuntimeException(StringUtilx.fmt(
									"Unsupport idValue!!! [type: {}, idType: {}, newIdValueType: {}, newIdValue: {}]",
									ar.getClass().getSimpleName(),
									idType.getName(),
									i.getClass().getName(),
									i
									)) ;
						}
					}
					((AbstractAggregateRoot )ar).setId(idValue);
				},
				ar -> ar._dirty_,
				ar -> ar._dirty_ = true
				);
	}
	
	private final static class ARParse {

		public final Class<?> _idType;
		public final IFunction1<String, AbstractAggregateRoot<?>> _toStringFactor;
		public ARParse(Class<?> _idType, IFunction1<String, AbstractAggregateRoot<?>> _toStringFactor) {
			this._idType = _idType;
			this._toStringFactor = _toStringFactor;
		}
		
	}
	
	private final static Map<Class<? extends AbstractAggregateRoot<?>>, ARParse> arParseStore = new ConcurrentHashMap<>();
}
