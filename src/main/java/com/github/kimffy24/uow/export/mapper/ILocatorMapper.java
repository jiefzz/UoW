package com.github.kimffy24.uow.export.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

public interface ILocatorMapper {

	public Map<String, Object> fetchById(@Param(value = "id") Object id);
	
	public int updateById(@Param(value = "id") Object id, @Param(value = "data") Map<String, Object> u);
	
	public int updateByIdAndVersion(@Param(value = "id") Object id, @Param(value = "version") String version, @Param(value = "data") Map<String, Object> u);
	
	public int createNew(Map<String, Object> n);
	
	public int removePermanentlyById(@Param(value = "id") Object id);
	
}
