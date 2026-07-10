package com.github.kimffy24.uow.repos;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.kimffy24.uow.component.StdConverter;
import com.github.kimffy24.uow.core.AggregateActionBinder;
import com.github.kimffy24.uow.export.mapper.ILocatorMapper;
import com.github.kimffy24.uow.export.skeleton.AbstractAggregateRoot;
import com.github.kimffy24.uow.service.SpringUoWMapperProvider;
import com.github.kimffy24.uow.util.KeyMapperStore.Item;

import pro.jk.ejoker.common.system.functional.IFunction;
import pro.jk.ejoker.common.system.functional.IVoidFunction1;

public class Repository <T extends AbstractAggregateRoot<?>> {
	
	private final StdConverter stdConverter;
	
	private final SpringUoWMapperProvider springUoWMapperBinder;
	
	private final IFunction<IVoidFunction1<Map<String, Object>>> preModifierLocator;
	
	private final Class<T> aggrRootType;
	
	private final Set<String> keys;
	
	/**
	 * do not modify!
	 */
	private final List<Item> anaResult;
	
	public Repository(StdConverter stdConverter,
			SpringUoWMapperProvider springUoWMapperBinder,
			IFunction<IVoidFunction1<Map<String, Object>>> preModifierLocator,
			Class<T> aggrRootType,
			List<Item> anaResult) {
		this.stdConverter = stdConverter;
		this.springUoWMapperBinder = springUoWMapperBinder;
		this.preModifierLocator = preModifierLocator;
		this.aggrRootType = aggrRootType;
		this.anaResult = anaResult;

		// TODO 暂不支持多主键
		keys = new LinkedHashSet<>();
		try {
			T tempAggr = aggrRootType.newInstance();
			keys.add(tempAggr.getIdFieldName());
		} catch (InstantiationException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}
	
	private T revertToAggr(Map<String, Object> originalDict) {
		T revert = stdConverter.revert(originalDict, this.aggrRootType);
		AggregateActionBinder.adoptOriginalDict(revert, stdConverter.convert(revert));
		return revert;
	}
	
	public T fetch(Object id) {
		if(null == id)
			return null;
		Map<String, Object> reMap = new HashMap<>();
		{
			ILocatorMapper provideLocatorMapper = springUoWMapperBinder.getAggrMapper(aggrRootType);
			Map<String, Object> originalDict = provideLocatorMapper.fetchById(id);
			if(null == originalDict)
				return null;
//			List<Item> anaResult = KMInstance.getAnaResult(aggrRootType);
			for(Item i : anaResult) {
				Object sqlValue = originalDict.get(i.sqlClName);
				if(null != sqlValue)
					reMap.put(i.key, sqlValue);
			}
		}
		
		IVoidFunction1<Map<String, Object>> preModifier = preModifierLocator.trigger();
		if(null != preModifier) {
			preModifier.trigger(reMap);
		}
		
		Iterator<Map.Entry<String, Object>> iterator = reMap.entrySet().iterator();
		while(iterator.hasNext()) {
			Map.Entry<String, Object> entry = iterator.next();
			Object value = entry.getValue();
			// 针对mysql时间类型作调整
			if(value instanceof Timestamp)
				entry.setValue(new Date(((Timestamp )value).getTime()));
			if(keys.contains(entry.getKey())) {
				// mysql驱动返回的类型，不一定跟java内的一致
				entry.setValue(id);
			}
		}

		return revertToAggr(reMap);
	}

	
//	public void add(T aggr) {
//		Map<String, Object> convert = stdConverter.convert(aggr);
//		// 由于spring三层架构的基因问题 ，不好支持， Repository 的 收录对象的功能有context承担.
//	}
//
//	public Class<?> getIdType() {
//		return aggrRootType;
//	}
	
	public Map<String, Object> getAggrOriginalDict(AbstractAggregateRoot<?> aggr) {
		return AggregateActionBinder.getOriginalDict(aggr);
	}
	
	public ILocatorMapper provideLocatorMapper() {
		return springUoWMapperBinder.getAggrMapper(aggrRootType);
	}

}
